use std::path::{Path, PathBuf};

use crate::error::{Error, Result};
use crate::grant::Grant;
use crate::paths::{self, Paths};

const MARKER_PREFIX: &str = "luvia-host:";

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ManagedKeyLine {
    pub device_id: String,
    pub command: String,
    pub key_type: String,
    pub blob: String,
}

impl ManagedKeyLine {
    pub fn from_grant(grant: &Grant, exe: &Path) -> Result<Self> {
        Ok(Self {
            device_id: grant.id.clone(),
            command: forced_command(exe, &grant.id)?,
            key_type: grant.key_type.clone(),
            blob: grant.key.clone(),
        })
    }

    pub fn render(&self) -> String {
        format!(
            "restrict,command=\"{}\" {} {} {}{}",
            escape_command(&self.command),
            self.key_type,
            self.blob,
            MARKER_PREFIX,
            self.device_id
        )
    }

    pub fn matches_key(&self, key_type: &str, blob: &str) -> bool {
        self.key_type == key_type && self.blob == blob
    }
}

pub fn forced_command(exe: &Path, device_id: &str) -> Result<String> {
    if !exe.is_absolute() {
        return Err(Error::new(
            "unsafe_path",
            "forced command must use an absolute luvia-host path",
        ));
    }
    let displayed = exe.to_str().ok_or_else(|| {
        Error::new("unsafe_path", "luvia-host path is not valid UTF-8")
    })?;
    if displayed.bytes().any(|byte| matches!(byte, b'\n' | b'\r' | 0)) {
        return Err(Error::new(
            "unsafe_path",
            "luvia-host path contains control characters",
        ));
    }
    Ok(format!("{displayed} bridge --device {device_id}"))
}

fn escape_command(command: &str) -> String {
    command.replace('\\', "\\\\").replace('"', "\\\"")
}

fn ssh_dir(authorized_keys: &Path) -> Result<PathBuf> {
    authorized_keys
        .parent()
        .map(Path::to_path_buf)
        .filter(|path| !path.as_os_str().is_empty())
        .ok_or_else(|| Error::new("unsafe_path", "authorized_keys has no parent directory"))
}

fn parse_key_material(line: &str) -> Option<(&str, &str)> {
    let line = line.trim();
    if line.is_empty() || line.starts_with('#') {
        return None;
    }
    let tokens: Vec<&str> = line.split_whitespace().collect();
    let start = tokens
        .iter()
        .position(|token| {
            *token == "ssh-ed25519"
                || *token == "ssh-rsa"
                || token.starts_with("ecdsa-sha2-")
                || token.starts_with("sk-")
        })?;
    let key_type = tokens.get(start)?;
    let blob = tokens.get(start + 1)?;
    Some((*key_type, *blob))
}

fn managed_device_id(line: &str) -> Option<&str> {
    line.split_whitespace().find_map(|token| {
        token.strip_prefix(MARKER_PREFIX).filter(|id| {
            id.len() == 32 && id.bytes().all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
        })
    })
}

fn is_managed_line_for(line: &str, device_id: &str) -> bool {
    managed_device_id(line) == Some(device_id)
}

pub fn assert_key_not_present(paths: &Paths, incoming: &ManagedKeyLine) -> Result<()> {
    let text = read_authorized_keys(paths)?;
    for line in text.lines() {
        if line.trim().is_empty() || line.trim_start().starts_with('#') {
            continue;
        }
        if is_managed_line_for(line, &incoming.device_id) {
            continue;
        }
        if let Some((key_type, blob)) = parse_key_material(line) {
            if incoming.matches_key(key_type, blob) {
                return Err(Error::new(
                    "duplicate_key",
                    "public key is already present in authorized_keys",
                ));
            }
        }
    }
    Ok(())
}

fn read_authorized_keys(paths: &Paths) -> Result<String> {
    paths::reject_symlink(&paths.authorized_keys, "authorized_keys")?;
    if let Some(parent) = paths.authorized_keys.parent() {
        if parent.exists() {
            paths::reject_symlink(parent, "ssh directory")?;
        }
    }
    paths::read_nofollow_to_string(&paths.authorized_keys)
}

fn write_authorized_keys(paths: &Paths, body: &str) -> Result<()> {
    let parent = ssh_dir(&paths.authorized_keys)?;
    if parent.exists() {
        paths::reject_symlink(&parent, "ssh directory")?;
    } else {
        paths::ensure_private_dir(&parent)?;
    }
    paths::reject_symlink(&paths.authorized_keys, "authorized_keys")?;
    let mut output = String::new();
    if !body.is_empty() {
        output.push_str(body);
        if !body.ends_with('\n') {
            output.push('\n');
        }
    }
    paths::write_atomic(&paths.authorized_keys, output.as_bytes())
}

pub fn install_line(paths: &Paths, line: &ManagedKeyLine) -> Result<()> {
    let rendered = line.render();
    let existing = read_authorized_keys(paths)?;
    let mut kept = Vec::new();
    let mut replaced = false;
    for current in existing.lines() {
        if is_managed_line_for(current, &line.device_id) {
            if current == rendered {
                replaced = true;
                kept.push(current.to_string());
            } else {
                replaced = true;
                kept.push(rendered.clone());
            }
            continue;
        }
        if let Some((key_type, blob)) = parse_key_material(current) {
            if line.matches_key(key_type, blob) {
                return Err(Error::new(
                    "duplicate_key",
                    "public key is already present in authorized_keys",
                ));
            }
        }
        kept.push(current.to_string());
    }
    if !replaced {
        kept.push(rendered);
    }
    // Drop accidental duplicate identical managed lines for this device.
    let mut seen_device = false;
    kept.retain(|current| {
        if is_managed_line_for(current, &line.device_id) {
            if seen_device {
                return false;
            }
            seen_device = true;
        }
        true
    });
    write_authorized_keys(paths, &kept.join("\n"))
}

pub fn remove_device(paths: &Paths, device_id: &str) -> Result<()> {
    let existing = read_authorized_keys(paths)?;
    let kept: Vec<String> = existing
        .lines()
        .filter(|line| !is_managed_line_for(line, device_id))
        .map(str::to_string)
        .collect();
    write_authorized_keys(paths, &kept.join("\n"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grant::{pair_device, revoke_device};
    use crate::role::Role;
    use crate::sshkey::parse_openssh_public_key;
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use std::fs;

    fn ed25519(seed: u8, comment: &str) -> crate::sshkey::PublicKey {
        let mut body = Vec::new();
        let algo = b"ssh-ed25519";
        body.extend_from_slice(&(algo.len() as u32).to_be_bytes());
        body.extend_from_slice(algo);
        body.extend_from_slice(&32u32.to_be_bytes());
        body.extend_from_slice(&[seed; 32]);
        parse_openssh_public_key(&format!("ssh-ed25519 {} {comment}", STANDARD.encode(body)))
            .unwrap()
    }

    fn test_paths(root: &Path) -> Paths {
        Paths::from_parts(
            root.join("host"),
            root.join("ssh").join("authorized_keys"),
            root.join("luvus"),
        )
    }

    #[test]
    fn install_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join("ssh")).unwrap();
        let paths = test_paths(dir.path());
        let exe = Path::new("/opt/luvia/luvia-host");
        let grant = pair_device(&paths, "pad", Role::Observer, &ed25519(1, "pad"), exe).unwrap();
        let line = ManagedKeyLine::from_grant(&grant, exe).unwrap();
        install_line(&paths, &line).unwrap();
        install_line(&paths, &line).unwrap();
        let text = paths::read_nofollow_to_string(&paths.authorized_keys).unwrap();
        let count = text
            .lines()
            .filter(|line| is_managed_line_for(line, &grant.id))
            .count();
        assert_eq!(count, 1);
        assert!(text.contains("restrict,command="));
        assert!(text.contains("bridge --device"));
    }

    #[test]
    fn revoke_removes_only_managed_line() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join("ssh")).unwrap();
        let paths = test_paths(dir.path());
        let foreign = "ssh-ed25519 AAAA foreign\n";
        fs::write(&paths.authorized_keys, foreign).unwrap();
        let exe = Path::new("/opt/luvia/luvia-host");
        let grant = pair_device(&paths, "pad", Role::Observer, &ed25519(2, "pad"), exe).unwrap();
        revoke_device(&paths, &grant.id).unwrap();
        let text = paths::read_nofollow_to_string(&paths.authorized_keys).unwrap();
        assert!(text.contains("foreign"));
        assert!(!text.contains(&grant.id));
    }

    #[test]
    fn symlink_authorized_keys_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let ssh = dir.path().join("ssh");
        fs::create_dir_all(&ssh).unwrap();
        let real = ssh.join("real_keys");
        fs::write(&real, "").unwrap();
        let linked = ssh.join("authorized_keys");
        std::os::unix::fs::symlink(&real, &linked).unwrap();
        let paths = test_paths(dir.path());
        let exe = Path::new("/opt/luvia/luvia-host");
        let err = pair_device(&paths, "pad", Role::Observer, &ed25519(3, "pad"), exe).unwrap_err();
        assert_eq!(err.code, "unsafe_path");
    }
}
