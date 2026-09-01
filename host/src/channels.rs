use std::fs::{File, OpenOptions};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::io::AsRawFd;
use std::path::PathBuf;

use crate::error::{Error, Result};
use crate::paths::{self, Paths};

pub struct ChannelLease {
    _file: File,
    _path: PathBuf,
}

pub fn acquire(paths: &Paths, device_id: &str, limit: usize) -> Result<ChannelLease> {
    if limit == 0 {
        return Err(Error::new("limit_exceeded", "connection capacity is zero"));
    }
    paths.ensure_host_dirs()?;
    let dir = paths.config_dir.join("run");
    paths::ensure_private_dir(&dir)?;
    for index in 0..limit {
        let path = dir.join(format!("{device_id}.{index}"));
        paths::reject_symlink(&path, "channel slot")?;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&path)?;
        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        if rc == 0 {
            return Ok(ChannelLease {
                _file: file,
                _path: path,
            });
        }
        let err = std::io::Error::last_os_error();
        if err.kind() != std::io::ErrorKind::WouldBlock
            && err.raw_os_error() != Some(libc::EWOULDBLOCK)
            && err.raw_os_error() != Some(libc::EAGAIN)
        {
            return Err(err.into());
        }
    }
    Err(Error::new(
        "limit_exceeded",
        format!("device {device_id} already has {limit} concurrent bridge channels"),
    ))
}

impl Drop for ChannelLease {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self._file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fifth_slot_is_rejected_when_limit_is_four() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::from_parts(
            dir.path().join("host"),
            dir.path().join("authorized_keys"),
            dir.path().join("luvus"),
        );
        let id = "ab".repeat(16);
        let a = acquire(&paths, &id, 4).unwrap();
        let b = acquire(&paths, &id, 4).unwrap();
        let c = acquire(&paths, &id, 4).unwrap();
        let d = acquire(&paths, &id, 4).unwrap();
        let err = match acquire(&paths, &id, 4) {
            Ok(_) => panic!("expected limit_exceeded"),
            Err(error) => error,
        };

        assert_eq!(err.code, "limit_exceeded");
        drop(a);
        drop(b);
        drop(c);
        drop(d);
        acquire(&paths, &id, 4).unwrap();
    }
}
