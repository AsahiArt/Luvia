use std::fs::OpenOptions;
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::Result;
use crate::grant::Grant;
use crate::paths::{self, Paths};
use crate::role::Role;

fn audit_path(paths: &Paths) -> std::path::PathBuf {
    paths.config_dir.join("audit.log")
}

pub fn append(paths: &Paths, line: &str) -> Result<()> {
    paths.ensure_host_dirs()?;
    paths::reject_symlink(&audit_path(paths), "audit log")?;
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(audit_path(paths))?;
    let mut permissions = file.metadata()?.permissions();
    std::os::unix::fs::PermissionsExt::set_mode(&mut permissions, 0o600);
    file.set_permissions(permissions)?;
    writeln!(file, "{} {line}", timestamp())?;
    file.flush()?;
    Ok(())
}

pub fn paired(paths: &Paths, grant: &Grant) -> Result<()> {
    append(
        paths,
        &format!(
            "pair device={} name={} role={} fingerprint={}",
            grant.id,
            sanitize(&grant.name),
            grant.role,
            grant.fingerprint
        ),
    )
}

pub fn revoked(paths: &Paths, grant: &Grant) -> Result<()> {
    append(
        paths,
        &format!(
            "revoke device={} name={} role={}",
            grant.id,
            sanitize(&grant.name),
            grant.role
        ),
    )
}

pub fn denied(
    paths: &Paths,
    device_id: &str,
    role: Role,
    method: &str,
    reason: &str,
) -> Result<()> {
    append(
        paths,
        &format!(
            "deny device={} role={} method={} reason={}",
            device_id,
            role,
            sanitize(method),
            sanitize(reason)
        ),
    )
}

fn timestamp() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0);
    format!("{secs}")
}

fn sanitize(value: &str) -> String {
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_graphic() && ch != '=' {
                ch
            } else {
                '_'
            }
        })
        .take(128)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::paths::Paths;

    #[test]
    fn writes_restricted_lines_without_secrets() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::from_parts(
            dir.path().join("host"),
            dir.path().join("authorized_keys"),
            dir.path().join("luvus"),
        );
        let grant = Grant {
            id: "ab".repeat(16),
            name: "phone".into(),
            role: Role::Observer,
            fingerprint: "SHA256:device".into(),
            key_type: "ssh-ed25519".into(),
            key: "SECRETPEM".into(),
            comment: String::new(),
            created_at: 0,
        };
        paired(&paths, &grant).unwrap();
        denied(&paths, &grant.id, grant.role, "server.stop", "forbidden").unwrap();
        let body = std::fs::read_to_string(audit_path(&paths)).unwrap();
        assert!(body.contains("pair device="));
        assert!(body.contains("deny device="));
        assert!(body.contains("method=server.stop"));
        assert!(!body.contains("SECRETPEM"));
        let mode = std::os::unix::fs::PermissionsExt::mode(
            &std::fs::metadata(audit_path(&paths)).unwrap().permissions(),
        );
        assert_eq!(mode & 0o777, 0o600);
    }
}
