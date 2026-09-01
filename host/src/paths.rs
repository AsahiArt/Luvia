use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};

use crate::error::{Error, Result};

const HOST_LOCK: &str = "host.lock";
const DEVICES_DIR: &str = "devices";

#[derive(Clone, Debug)]
pub struct Paths {
    pub config_dir: PathBuf,
    pub devices_dir: PathBuf,
    pub lock_path: PathBuf,
    pub authorized_keys: PathBuf,
    pub luvus_home: PathBuf,
}

impl Paths {
    pub fn from_env() -> Result<Self> {
        let home = user_home()?;
        let config_dir = match std::env::var_os("LUVIA_HOME") {
            Some(value) => PathBuf::from(value).join("host"),
            None => {
                let base = std::env::var_os("XDG_CONFIG_HOME")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| home.join(".config"));
                base.join("luvia").join("host")
            }
        };
        let authorized_keys = match std::env::var_os("LUVIA_AUTHORIZED_KEYS") {
            Some(value) => PathBuf::from(value),
            None => home.join(".ssh").join("authorized_keys"),
        };
        let luvus_home = match std::env::var_os("LUVUS_HOME") {
            Some(value) => PathBuf::from(value),
            None => home.join(".luvus"),
        };
        Ok(Self::from_parts(config_dir, authorized_keys, luvus_home))
    }

    pub fn from_parts(config_dir: PathBuf, authorized_keys: PathBuf, luvus_home: PathBuf) -> Self {
        let devices_dir = config_dir.join(DEVICES_DIR);
        let lock_path = config_dir.join(HOST_LOCK);
        Self {
            config_dir,
            devices_dir,
            lock_path,
            authorized_keys,
            luvus_home,
        }
    }

    pub fn ensure_host_dirs(&self) -> Result<()> {
        ensure_private_dir(&self.config_dir)?;
        ensure_private_dir(&self.devices_dir)?;
        Ok(())
    }
}

pub fn user_home() -> Result<PathBuf> {
    std::env::var_os("HOME")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .ok_or_else(|| Error::new("io", "HOME is not set"))
}

pub fn current_euid() -> u32 {
    unsafe { libc::geteuid() }
}

pub fn lstat(path: &Path) -> io::Result<fs::Metadata> {
    fs::symlink_metadata(path)
}

pub fn reject_symlink(path: &Path, what: &str) -> Result<()> {
    match lstat(path) {
        Ok(meta) if meta.file_type().is_symlink() => Err(Error::new(
            "unsafe_path",
            format!("refusing symlink {what}: {}", path.display()),
        )),
        Ok(_) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

pub fn ensure_private_dir(path: &Path) -> Result<()> {
    if path.as_os_str().is_empty() {
        return Err(Error::new("unsafe_path", "refusing empty directory path"));
    }
    match lstat(path) {
        Ok(meta) => {
            if meta.file_type().is_symlink() {
                return Err(Error::new(
                    "unsafe_path",
                    format!("refusing symlink directory {}", path.display()),
                ));
            }
            if !meta.file_type().is_dir() {
                return Err(Error::new(
                    "unsafe_path",
                    format!("refusing non-directory {}", path.display()),
                ));
            }
            if meta.uid() != current_euid() {
                return Err(Error::new(
                    "unsafe_path",
                    format!(
                        "directory is not owned by the current user: {}",
                        path.display()
                    ),
                ));
            }
            let mut permissions = meta.permissions();
            if permissions.mode() & 0o777 != 0o700 {
                permissions.set_mode(0o700);
                fs::set_permissions(path, permissions)?;
            }
            Ok(())
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            if let Some(parent) = path
                .parent()
                .filter(|parent| !parent.as_os_str().is_empty())
            {
                if parent.exists() {
                    reject_symlink(parent, "directory parent")?;
                }
            }
            fs::DirBuilder::new()
                .mode(0o700)
                .recursive(true)
                .create(path)?;
            reject_symlink(path, "directory")?;
            let meta = lstat(path)?;
            if meta.uid() != current_euid() {
                return Err(Error::new(
                    "unsafe_path",
                    format!(
                        "created directory is not owned by the current user: {}",
                        path.display()
                    ),
                ));
            }
            let mut permissions = meta.permissions();
            permissions.set_mode(0o700);
            fs::set_permissions(path, permissions)?;
            Ok(())
        }
        Err(error) => Err(error.into()),
    }
}

pub fn open_nofollow_read(path: &Path) -> io::Result<File> {
    OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
}

pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let Some(parent) = path.parent() else {
        return Err(Error::new(
            "unsafe_path",
            "refusing a path without a parent",
        ));
    };
    if parent.as_os_str().is_empty() {
        return Err(Error::new(
            "unsafe_path",
            "refusing a path without a parent",
        ));
    }
    reject_symlink(parent, "parent directory")?;
    reject_symlink(path, "file")?;
    let tmp = parent.join(format!(
        ".{}.tmp.{}",
        path.file_name()
            .and_then(|name| name.to_str())
            .ok_or_else(|| Error::new("unsafe_path", "file name is not UTF-8"))?,
        std::process::id()
    ));
    let _ = fs::remove_file(&tmp);
    {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        let mut permissions = file.metadata()?.permissions();
        permissions.set_mode(0o600);
        file.set_permissions(permissions)?;
    }
    fs::rename(&tmp, path)?;
    if let Ok(dir) = File::open(parent) {
        let _ = dir.sync_all();
    }
    Ok(())
}

pub fn read_nofollow_to_string(path: &Path) -> Result<String> {
    reject_symlink(path, "file")?;
    match open_nofollow_read(path) {
        Ok(file) => {
            use std::io::Read;
            let mut buf = String::new();
            let mut file = file;
            file.read_to_string(&mut buf)?;
            Ok(buf)
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(String::new()),
        Err(error) => Err(error.into()),
    }
}

pub struct LockFile {
    file: File,
}

impl LockFile {
    pub fn exclusive(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            ensure_private_dir(parent)?;
        }
        reject_symlink(path, "lock file")?;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)?;
        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) };
        if rc != 0 {
            return Err(io::Error::last_os_error().into());
        }
        Ok(Self { file })
    }
}

impl Drop for LockFile {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_symlink_file_targets() {
        let dir = tempfile::tempdir().unwrap();
        let real = dir.path().join("real");
        fs::write(&real, "x").unwrap();
        let link = dir.path().join("link");
        std::os::unix::fs::symlink(&real, &link).unwrap();
        let err = reject_symlink(&link, "file").unwrap_err();
        assert_eq!(err.code, "unsafe_path");
    }
}
