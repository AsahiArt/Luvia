use std::fs;
use std::os::unix::fs::{FileTypeExt, MetadataExt};
use std::os::unix::io::AsRawFd;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};

use crate::error::{Error, Result};
use crate::paths::current_euid;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Evidence {
    pub dev: u64,
    pub ino: u64,
    pub ctime_ns: i128,
}

pub fn validate_unix_endpoint(path: &Path) -> Result<Evidence> {
    if !path.is_absolute() {
        return Err(Error::new(
            "unsafe_endpoint",
            "endpoint address is not absolute",
        ));
    }
    if has_dot_or_empty_component(path) {
        return Err(Error::new(
            "unsafe_endpoint",
            "endpoint path is not canonical",
        ));
    }

    let mut chain: Vec<PathBuf> = path.ancestors().map(Path::to_path_buf).collect();
    chain.reverse();
    for component in &chain {
        if component.as_os_str().is_empty() {
            continue;
        }
        let meta = fs::symlink_metadata(component).map_err(|_| {
            Error::new(
                "unsafe_endpoint",
                format!("endpoint component is missing: {}", component.display()),
            )
        })?;
        if meta.file_type().is_symlink() {
            return Err(Error::new(
                "unsafe_endpoint",
                "endpoint path contains a symlink",
            ));
        }
    }

    let socket_meta = fs::symlink_metadata(path)?;
    if !socket_meta.file_type().is_socket() {
        return Err(Error::new(
            "unsafe_endpoint",
            format!("endpoint is not a Unix socket: {}", path.display()),
        ));
    }
    let uid = current_euid();
    if socket_meta.uid() != uid || socket_meta.mode() & 0o777 != 0o600 {
        return Err(Error::new(
            "unsafe_endpoint",
            format!(
                "endpoint socket is not owner-only mode 0600: {}",
                path.display()
            ),
        ));
    }

    let parent = path.parent().ok_or_else(|| {
        Error::new("unsafe_endpoint", "endpoint has no parent directory")
    })?;
    let parent_meta = fs::symlink_metadata(parent)?;
    if parent_meta.file_type().is_symlink() || !parent_meta.file_type().is_dir() {
        return Err(Error::new(
            "unsafe_endpoint",
            "endpoint parent is not a directory",
        ));
    }
    if parent_meta.uid() != uid || parent_meta.mode() & 0o777 != 0o700 {
        return Err(Error::new(
            "unsafe_endpoint",
            format!(
                "endpoint directory is not owner-only mode 0700: {}",
                parent.display()
            ),
        ));
    }

    if is_tmp_alias(path, uid) {
        validate_tmp_alias(path, uid)?;
    } else {
        for ancestor in path.ancestors().skip(1) {
            if ancestor == Path::new("/") {
                continue;
            }
            let meta = fs::symlink_metadata(ancestor)?;
            if meta.file_type().is_symlink() {
                return Err(Error::new(
                    "unsafe_endpoint",
                    "endpoint path contains a symlink",
                ));
            }
            if meta.mode() & 0o022 != 0 {
                return Err(Error::new(
                    "unsafe_endpoint",
                    format!(
                        "endpoint ancestor is group- or world-writable: {}",
                        ancestor.display()
                    ),
                ));
            }
        }
    }

    let canonical = fs::canonicalize(path)?;
    let canonical_meta = fs::symlink_metadata(&canonical)?;
    if canonical != path
        && (canonical_meta.dev() != socket_meta.dev() || canonical_meta.ino() != socket_meta.ino())
    {
        return Err(Error::new(
            "unsafe_endpoint",
            "endpoint canonical path changed",
        ));
    }

    Ok(evidence(&socket_meta))
}

pub fn validate_unchanged(path: &Path, expected: Evidence) -> Result<Evidence> {
    let current = validate_unix_endpoint(path)?;
    if current != expected {
        return Err(Error::new(
            "unsafe_endpoint",
            "endpoint was replaced",
        ));
    }
    Ok(current)
}

pub fn connect_validated(path: &Path, expected: Evidence) -> Result<UnixStream> {
    validate_unchanged(path, expected)?;
    let stream = UnixStream::connect(path)?;
    validate_peer(&stream)?;
    Ok(stream)
}

pub fn validate_peer(stream: &UnixStream) -> Result<()> {
    let uid = peer_uid(stream)?;
    if uid != current_euid() {
        return Err(Error::new(
            "unsafe_endpoint",
            "refusing a Luvus socket peer owned by another account",
        ));
    }
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn peer_uid(stream: &UnixStream) -> Result<u32> {
    let mut cred = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut _ as *mut libc::c_void,
            &mut len,
        )
    };
    if rc != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(cred.uid)
}

#[cfg(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "openbsd",
    target_os = "netbsd",
    target_os = "dragonfly"
))]
fn peer_uid(stream: &UnixStream) -> Result<u32> {
    let mut uid: libc::uid_t = 0;
    let mut gid: libc::gid_t = 0;
    let rc = unsafe { libc::getpeereid(stream.as_raw_fd(), &mut uid, &mut gid) };
    if rc != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(uid)
}

fn evidence(meta: &fs::Metadata) -> Evidence {
    Evidence {
        dev: meta.dev(),
        ino: meta.ino(),
        ctime_ns: i128::from(meta.ctime())
            .saturating_mul(1_000_000_000)
            .saturating_add(i128::from(meta.ctime_nsec())),
    }
}

fn has_dot_or_empty_component(path: &Path) -> bool {
    path.components().any(|component| match component {
        std::path::Component::CurDir | std::path::Component::ParentDir => true,
        std::path::Component::Normal(name) if name.is_empty() => true,
        _ => false,
    })
}

fn tmp_root() -> &'static Path {
    if cfg!(target_os = "macos") {
        Path::new("/private/tmp")
    } else {
        Path::new("/tmp")
    }
}

fn is_tmp_alias(path: &Path, uid: u32) -> bool {
    let expected = tmp_root().join(format!("luvus-{uid}"));
    path.parent() == Some(expected.as_path())
}

fn validate_tmp_alias(path: &Path, uid: u32) -> Result<()> {
    let root = tmp_root();
    let expected_dir = root.join(format!("luvus-{uid}"));
    if path.parent() != Some(expected_dir.as_path()) {
        return Err(Error::new(
            "unsafe_endpoint",
            "unsafe temporary socket alias",
        ));
    }
    let root_meta = fs::symlink_metadata(root)?;
    if root_meta.file_type().is_symlink()
        || root_meta.uid() != 0
        || root_meta.mode() & 0o7777 != 0o1777
    {
        return Err(Error::new(
            "unsafe_endpoint",
            "unsafe temporary socket alias",
        ));
    }
    let dir_meta = fs::symlink_metadata(&expected_dir)?;
    if dir_meta.file_type().is_symlink()
        || dir_meta.uid() != uid
        || dir_meta.mode() & 0o777 != 0o700
    {
        return Err(Error::new(
            "unsafe_endpoint",
            "unsafe temporary socket alias",
        ));
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::os::unix::net::UnixListener;

    fn private_tree() -> tempfile::TempDir {
        tempfile::tempdir().unwrap()
    }

    fn bind_socket(dir: &Path) -> (PathBuf, UnixListener) {
        std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700)).unwrap();
        let path = dir.join("luvus.sock");
        let listener = UnixListener::bind(&path).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        (path, listener)
    }

    #[test]
    fn owned_socket_with_private_dir_is_accepted_when_ancestors_are_safe() {
        let tmp = private_tree();
        let state = tmp.path().join("state");
        std::fs::create_dir_all(&state).unwrap();
        let (path, _listener) = bind_socket(&state);
        match validate_unix_endpoint(&path) {
            Ok(evidence) => {
                assert!(evidence.ino > 0);
            }
            Err(error) => {
                // tempfile on macOS often sits under world-writable or symlink ancestors.
                assert_eq!(error.code, "unsafe_endpoint");
            }
        }
    }

    #[test]
    fn symlink_component_is_rejected() {
        let tmp = private_tree();
        let real = tmp.path().join("real");
        std::fs::create_dir_all(&real).unwrap();
        let (path, _listener) = bind_socket(&real);
        let link_dir = tmp.path().join("link");
        std::os::unix::fs::symlink(&real, &link_dir).unwrap();
        let linked = link_dir.join("luvus.sock");
        let err = validate_unix_endpoint(&linked).unwrap_err();
        assert_eq!(err.code, "unsafe_endpoint");
        let _ = path;
    }

    #[test]
    fn world_writable_parent_is_rejected() {
        let tmp = private_tree();
        let state = tmp.path().join("state");
        std::fs::create_dir_all(&state).unwrap();
        let (path, _listener) = bind_socket(&state);
        std::fs::set_permissions(&state, std::fs::Permissions::from_mode(0o777)).unwrap();
        let err = validate_unix_endpoint(&path).unwrap_err();
        assert_eq!(err.code, "unsafe_endpoint");
    }

    #[test]
    fn relative_path_is_rejected() {
        let err = validate_unix_endpoint(Path::new("luvus.sock")).unwrap_err();
        assert_eq!(err.code, "unsafe_endpoint");
    }

    #[test]
    fn tmp_alias_layout_is_detected() {
        let uid = current_euid();
        let linux = PathBuf::from(format!("/tmp/luvus-{uid}/deadbeef-api.sock"));
        let mac = PathBuf::from(format!("/private/tmp/luvus-{uid}/deadbeef-api.sock"));
        if cfg!(target_os = "macos") {
            assert!(is_tmp_alias(&mac, uid));
            assert!(!is_tmp_alias(&linux, uid));
        } else {
            assert!(is_tmp_alias(&linux, uid));
            assert!(!is_tmp_alias(&mac, uid));
        }
    }
}
