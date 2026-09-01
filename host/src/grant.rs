use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::authkeys::{self, ManagedKeyLine};
use crate::error::{Error, Result};
use crate::paths::{self, LockFile, Paths};
use crate::role::Role;
use crate::sshkey::PublicKey;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Grant {
    pub id: String,
    pub name: String,
    pub role: Role,
    pub fingerprint: String,
    pub key_type: String,
    pub key: String,
    #[serde(default)]
    pub comment: String,
    pub created_at: u64,
}

#[derive(Clone, Debug, Serialize)]
pub struct PublicGrant {
    pub id: String,
    pub name: String,
    pub role: Role,
    pub fingerprint: String,
    pub created_at: u64,
}

impl Grant {
    pub fn to_public(&self) -> PublicGrant {
        PublicGrant {
            id: self.id.clone(),
            name: self.name.clone(),
            role: self.role,
            fingerprint: self.fingerprint.clone(),
            created_at: self.created_at,
        }
    }

    pub fn public_key(&self) -> PublicKey {
        PublicKey {
            key_type: self.key_type.clone(),
            blob: self.key.clone(),
            comment: self.comment.clone(),
        }
    }
}

pub fn validate_device_name(name: &str) -> Result<()> {
    if name.is_empty() || name.len() > 64 {
        return Err(Error::new(
            "invalid_name",
            "device name must be 1 to 64 bytes",
        ));
    }
    if name
        .chars()
        .any(|ch| ch.is_control() || ch == '\n' || ch == '\r')
    {
        return Err(Error::new(
            "invalid_name",
            "device name must not contain control characters",
        ));
    }
    Ok(())
}

pub fn validate_device_id(id: &str) -> Result<()> {
    if id.len() != 32 || !id.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(Error::new(
            "invalid_device",
            "device id must be 32 lowercase hex characters",
        ));
    }
    if !id
        .bytes()
        .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(Error::new(
            "invalid_device",
            "device id must be 32 lowercase hex characters",
        ));
    }
    Ok(())
}

fn new_device_id() -> Result<String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|_| Error::new("io", "secure random is unavailable"))?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

fn grant_path(paths: &Paths, id: &str) -> PathBuf {
    paths.devices_dir.join(format!("{id}.json"))
}

pub fn load_grant(paths: &Paths, id: &str) -> Result<Grant> {
    validate_device_id(id)?;
    paths::reject_symlink(&grant_path(paths, id), "grant")?;
    let text = paths::read_nofollow_to_string(&grant_path(paths, id))?;
    if text.is_empty() {
        return Err(Error::new(
            "unknown_device",
            format!("no pairing grant for {id}"),
        ));
    }
    Ok(serde_json::from_str(&text)?)
}

pub fn list_grants(paths: &Paths) -> Result<Vec<Grant>> {
    paths.ensure_host_dirs()?;
    let mut grants = Vec::new();
    let entries = match fs::read_dir(&paths.devices_dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(grants),
        Err(error) => return Err(error.into()),
    };
    for entry in entries {
        let entry = entry?;
        paths::reject_symlink(&entry.path(), "grant")?;
        let Some(name) = entry.file_name().to_str().map(str::to_string) else {
            continue;
        };
        let Some(id) = name.strip_suffix(".json") else {
            continue;
        };
        if validate_device_id(id).is_err() {
            continue;
        }
        if let Ok(grant) = load_grant(paths, id) {
            grants.push(grant);
        }
    }
    grants.sort_by(|a, b| a.created_at.cmp(&b.created_at).then(a.id.cmp(&b.id)));
    Ok(grants)
}

pub fn pair_device(
    paths: &Paths,
    name: &str,
    role: Role,
    key: &PublicKey,
    exe: &Path,
) -> Result<Grant> {
    validate_device_name(name)?;
    paths.ensure_host_dirs()?;
    let _lock = LockFile::exclusive(&paths.lock_path)?;
    let fingerprint = key.fingerprint()?;
    for grant in list_grants(paths)? {
        if grant.fingerprint == fingerprint {
            return Err(Error::new(
                "duplicate_key",
                format!(
                    "public key is already paired as device {} ({}); run `luvia-host pair-code {}` to reprint the QR, or pick another --name",
                    grant.id, grant.name, grant.id
                ),
            ));
        }
        if grant.name == name {
            return Err(Error::new(
                "duplicate_name",
                format!(
                    "device name {name} is already paired as {}; run `luvia-host pair-code {}` to reprint the QR, or pick another --name",
                    grant.id, grant.id
                ),
            ));
        }
    }

    let id = new_device_id()?;
    let created_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0);
    let grant = Grant {
        id: id.clone(),
        name: name.to_string(),
        role,
        fingerprint,
        key_type: key.key_type.clone(),
        key: key.blob.clone(),
        comment: key.comment.clone(),
        created_at,
    };
    let line = ManagedKeyLine::from_grant(&grant, exe)?;
    authkeys::assert_key_not_present(paths, &line)?;
    let encoded = serde_json::to_vec_pretty(&grant)?;
    paths::write_atomic(&grant_path(paths, &id), &encoded)?;
    if let Err(error) = authkeys::install_line(paths, &line) {
        let _ = fs::remove_file(grant_path(paths, &id));
        return Err(error);
    }
    Ok(grant)
}

pub fn revoke_device(paths: &Paths, id: &str) -> Result<Grant> {
    validate_device_id(id)?;
    paths.ensure_host_dirs()?;
    let _lock = LockFile::exclusive(&paths.lock_path)?;
    let grant = load_grant(paths, id)?;
    authkeys::remove_device(paths, id)?;
    fs::remove_file(grant_path(paths, id))?;
    Ok(grant)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sshkey::parse_openssh_public_key;
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;

    fn sample_key(comment: &str) -> PublicKey {
        sample_key_fill(comment, 7)
    }

    fn sample_key_fill(comment: &str, fill: u8) -> PublicKey {
        let mut body = Vec::new();
        let algo = b"ssh-ed25519";
        body.extend_from_slice(&(algo.len() as u32).to_be_bytes());
        body.extend_from_slice(algo);
        body.extend_from_slice(&32u32.to_be_bytes());
        body.extend_from_slice(&[fill; 32]);
        parse_openssh_public_key(&format!("ssh-ed25519 {} {comment}", STANDARD.encode(body)))
            .unwrap()
    }
    #[test]
    fn duplicate_key_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join("ssh")).unwrap();
        let paths = test_paths(dir.path());
        let exe = Path::new("/usr/local/bin/luvia-host");
        pair_device(&paths, "one", Role::Observer, &sample_key("one"), exe).unwrap();
        let err = pair_device(&paths, "two", Role::Observer, &sample_key("two"), exe).unwrap_err();
        assert_eq!(err.code, "duplicate_key");
        assert!(err.message.contains("pair-code"));
    }

    #[test]
    fn duplicate_name_points_at_pair_code() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join("ssh")).unwrap();
        let paths = test_paths(dir.path());
        let exe = Path::new("/usr/local/bin/luvia-host");
        let grant = pair_device(
            &paths,
            "iPhone",
            Role::Controller,
            &sample_key_fill("one", 1),
            exe,
        )
        .unwrap();
        let err = pair_device(
            &paths,
            "iPhone",
            Role::Controller,
            &sample_key_fill("two", 2),
            exe,
        )
        .unwrap_err();
        assert_eq!(err.code, "duplicate_name");
        assert!(err.message.contains(&grant.id));
        assert!(err.message.contains("pair-code"));
        assert!(err.message.contains("pick another --name"));
    }

    fn test_paths(root: &Path) -> Paths {
        Paths::from_parts(
            root.join("host"),
            root.join("ssh").join("authorized_keys"),
            root.join("luvus"),
        )
    }

    #[test]
    fn pair_lists_and_revokes() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join("ssh")).unwrap();
        let paths = test_paths(dir.path());
        let exe = Path::new("/usr/local/bin/luvia-host");
        let grant =
            pair_device(&paths, "phone", Role::Controller, &sample_key("phone"), exe).unwrap();
        let listed = list_grants(&paths).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, grant.id);
        let keys = paths::read_nofollow_to_string(&paths.authorized_keys).unwrap();
        assert!(keys.contains(&format!("luvia-host:{}", grant.id)));
        assert!(keys.contains("restrict,command="));
        revoke_device(&paths, &grant.id).unwrap();
        assert!(list_grants(&paths).unwrap().is_empty());
        let keys = paths::read_nofollow_to_string(&paths.authorized_keys).unwrap();
        assert!(!keys.contains(&grant.id));
    }
}
