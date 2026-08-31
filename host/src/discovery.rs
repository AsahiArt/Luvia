use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};

use crate::endpoint::{self, Evidence};
use crate::error::{Error, Result};
use crate::paths::Paths;
use crate::prelude::{self, DEFAULT_SESSION_NAME};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiscoveredSession {
    pub name: String,
    pub default: bool,
    pub address: PathBuf,
    pub evidence: Evidence,
}

impl DiscoveredSession {
    pub fn metadata(&self) -> serde_json::Map<String, serde_json::Value> {
        prelude::session_metadata(&self.name, self.default, true)
    }
}

pub fn discover_running(paths: &Paths) -> Result<Vec<DiscoveredSession>> {
    let mut sessions = Vec::new();
    if let Some(session) = probe_session(&paths.luvus_home, DEFAULT_SESSION_NAME, true) {
        sessions.push(session);
    }
    let sessions_dir = paths.luvus_home.join("sessions");
    let entries = match std::fs::read_dir(&sessions_dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(sessions),
        Err(error) => return Err(error.into()),
    };
    let mut names = Vec::new();
    for entry in entries {
        let entry = entry?;
        let file_type = match entry.file_type() {
            Ok(file_type) => file_type,
            Err(_) => continue,
        };
        if file_type.is_symlink() || !file_type.is_dir() {
            continue;
        }
        let Some(name) = entry.file_name().to_str().map(str::to_string) else {
            continue;
        };
        if name != DEFAULT_SESSION_NAME && prelude::validate_session_name(&name).is_ok() {
            names.push(name);
        }
    }
    names.sort_unstable();
    for name in names {
        let dir = sessions_dir.join(&name);
        if let Some(session) = probe_session(&dir, &name, false) {
            sessions.push(session);
        }
    }
    Ok(sessions)
}

pub fn select_session<'a>(
    sessions: &'a [DiscoveredSession],
    name: &str,
) -> Result<&'a DiscoveredSession> {
    sessions
        .iter()
        .find(|session| session.name == name)
        .ok_or_else(|| {
            Error::new(
                "unknown_session",
                format!("session {name} is not a running discovery result"),
            )
        })
}

fn probe_session(session_dir: &Path, name: &str, default: bool) -> Option<DiscoveredSession> {
    let address = api_socket_path(session_dir);
    let evidence = endpoint::validate_unix_endpoint(&address).ok()?;
    if std::os::unix::net::UnixStream::connect(&address).is_err() {
        return None;
    }
    let again = endpoint::validate_unix_endpoint(&address).ok()?;
    if again != evidence {
        return None;
    }
    Some(DiscoveredSession {
        name: name.to_string(),
        default,
        address,
        evidence,
    })
}

pub fn api_socket_path(session_dir: &Path) -> PathBuf {
    socket_alias_path(session_dir.join("luvus.sock"))
}

fn socket_alias_path(logical: PathBuf) -> PathBuf {
    if logical.as_os_str().as_bytes().len() >= 100 {
        let mut hash = 0xcbf29ce484222325u64;
        for byte in logical.as_os_str().as_bytes() {
            hash ^= u64::from(*byte);
            hash = hash.wrapping_mul(0x100000001b3);
        }
        let uid = crate::paths::current_euid();
        let temporary_root = if cfg!(target_os = "macos") {
            "/private/tmp"
        } else {
            "/tmp"
        };
        return PathBuf::from(format!(
            "{temporary_root}/luvus-{uid}/{hash:016x}-api.sock"
        ));
    }
    logical
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_logical_paths_use_tmp_alias() {
        let logical = PathBuf::from(format!(
            "/very/long/luvus/home/{}/luvus.sock",
            "x".repeat(120)
        ));
        let aliased = socket_alias_path(logical);
        let prefix = if cfg!(target_os = "macos") {
            "/private/tmp/luvus-"
        } else {
            "/tmp/luvus-"
        };
        assert!(aliased.to_string_lossy().starts_with(prefix));
        assert!(aliased.to_string_lossy().ends_with("-api.sock"));
    }

    #[test]
    fn short_paths_stay_logical() {
        let logical = PathBuf::from("/home/user/.luvus/luvus.sock");
        assert_eq!(socket_alias_path(logical.clone()), logical);
    }
}
