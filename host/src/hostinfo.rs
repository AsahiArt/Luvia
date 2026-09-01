use std::collections::HashSet;
use std::fs;
use std::net::ToSocketAddrs;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};

use crate::error::{Error, Result};
use crate::sshkey::parse_openssh_public_key;

const SSH_CONFIG: &str = "/etc/ssh/sshd_config";
const HOST_KEY_DIR: &str = "/etc/ssh";
const MAX_INCLUDE_FILES: usize = 32;
const MAX_INCLUDE_DEPTH: usize = 8;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HostFacts {
    pub name: String,
    pub user: String,
    pub port: u16,
    pub addrs: Vec<String>,
    pub hk: Vec<String>,
}

impl HostFacts {
    pub fn collect(address_override: &[String], port_override: Option<u16>) -> Result<Self> {
        Self::collect_from(
            Path::new(HOST_KEY_DIR),
            Path::new(SSH_CONFIG),
            address_override,
            port_override,
        )
    }

    pub fn collect_from(
        host_key_dir: &Path,
        sshd_config: &Path,
        address_override: &[String],
        port_override: Option<u16>,
    ) -> Result<Self> {
        let hk = host_key_fingerprints(host_key_dir)?;
        let name = hostname()?;
        let user = login_user()?;
        let port = port_override.unwrap_or_else(|| ssh_listen_port(sshd_config).unwrap_or(22));
        if port == 0 {
            return Err(Error::new("invalid_port", "SSH port must be non-zero"));
        }
        let addrs = if address_override.is_empty() {
            collect_addrs(&name)?
        } else {
            dedup(
                address_override
                    .iter()
                    .map(|addr| addr.to_string())
                    .collect(),
            )
        };
        if addrs.is_empty() {
            return Err(Error::new(
                "no_address",
                "could not determine a reachable host address; pass --address",
            ));
        }
        Ok(Self {
            name,
            user,
            port,
            addrs,
            hk,
        })
    }
}

pub fn host_key_fingerprints(dir: &Path) -> Result<Vec<String>> {
    if !dir.is_dir() {
        return Err(empty_host_keys(dir));
    }
    let mut names = Vec::new();
    let entries = fs::read_dir(dir).map_err(|_| empty_host_keys(dir))?;
    for entry in entries {
        let entry = match entry {
            Ok(entry) => entry,
            Err(_) => continue,
        };
        let name = entry.file_name();
        let Some(text) = name.to_str() else {
            continue;
        };
        if text.starts_with("ssh_host_") && text.ends_with("_key.pub") {
            names.push(entry.path());
        }
    }
    names.sort();
    let mut fingerprints = Vec::new();
    for path in names {
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        let Ok(key) = parse_openssh_public_key(&text) else {
            continue;
        };
        let Ok(fingerprint) = key.fingerprint() else {
            continue;
        };
        fingerprints.push(fingerprint);
    }
    fingerprints.sort();
    fingerprints.dedup();
    if fingerprints.is_empty() {
        return Err(empty_host_keys(dir));
    }
    Ok(fingerprints)
}

fn empty_host_keys(dir: &Path) -> Error {
    Error::new(
        "no_host_keys",
        format!(
            "cannot read any SSH host public keys from {}/ssh_host_*_key.pub; the pairing code would ship an empty pin set and silently disable host-key verification",
            dir.display()
        ),
    )
}

fn login_user() -> Result<String> {
    if let Ok(user) = std::env::var("USER") {
        if !user.is_empty() && !user.contains('\0') {
            return Ok(user);
        }
    }
    let uid = crate::paths::current_euid();
    unsafe {
        let passwd = libc::getpwuid(uid);
        if passwd.is_null() {
            return Err(Error::new("io", "cannot determine the current login user"));
        }
        let name = std::ffi::CStr::from_ptr((*passwd).pw_name)
            .to_str()
            .map_err(|_| Error::new("io", "login user is not valid UTF-8"))?;
        if name.is_empty() {
            return Err(Error::new("io", "cannot determine the current login user"));
        }
        Ok(name.to_string())
    }
}

fn hostname() -> Result<String> {
    let mut buf = [0u8; 256];
    let rc = unsafe { libc::gethostname(buf.as_mut_ptr() as *mut libc::c_char, buf.len()) };
    if rc != 0 {
        return Err(Error::new("io", "cannot determine the host name"));
    }
    let len = buf.iter().position(|byte| *byte == 0).unwrap_or(buf.len());
    let name = std::str::from_utf8(&buf[..len])
        .map_err(|_| Error::new("io", "host name is not valid UTF-8"))?
        .trim();
    if name.is_empty() {
        return Err(Error::new("io", "host name is empty"));
    }
    Ok(name.to_string())
}

fn collect_addrs(hostname: &str) -> Result<Vec<String>> {
    let mut addrs = Vec::new();
    if let Some(local) = ssh_connection_local_addr() {
        addrs.push(local);
    }
    let (lan_v4, overlay, lan_v6) = interface_addresses();
    addrs.extend(lan_v4);
    addrs.extend(overlay);
    addrs.extend(lan_v6);
    if hostname_resolves(hostname) && !is_loopback_name(hostname) {
        addrs.push(hostname.to_string());
    }
    let addrs = dedup(addrs);
    if addrs.is_empty() {
        return Err(Error::new(
            "no_address",
            "could not determine a reachable host address; pass --address",
        ));
    }
    Ok(addrs)
}

fn ssh_connection_local_addr() -> Option<String> {
    let value = std::env::var("SSH_CONNECTION").ok()?;
    let mut fields = value.split_whitespace();
    let _client = fields.next()?;
    let _client_port = fields.next()?;
    let local = fields.next()?;
    if local.is_empty() || is_loopback_name(local) {
        return None;
    }
    Some(local.to_string())
}

fn hostname_resolves(name: &str) -> bool {
    (name, 0u16).to_socket_addrs().is_ok()
}

fn is_loopback_name(name: &str) -> bool {
    matches!(name, "localhost" | "ip6-localhost" | "ip6-loopback")
        || name == "127.0.0.1"
        || name == "::1"
        || name.starts_with("127.")
}

fn is_virtual_interface(name: &str) -> bool {
    const PREFIXES: &[&str] = &[
        "docker", "br-", "bridge", "veth", "virbr", "vmnet", "vboxnet", "awdl", "llw",
    ];
    PREFIXES.iter().any(|prefix| name.starts_with(prefix))
}

fn is_overlay_interface(name: &str) -> bool {
    const PREFIXES: &[&str] = &["tun", "tap", "utun", "wg", "tailscale", "zt"];
    PREFIXES.iter().any(|prefix| name.starts_with(prefix))
}

fn interface_addresses() -> (Vec<String>, Vec<String>, Vec<String>) {
    let mut lan_v4 = Vec::new();
    let mut overlay_v4 = Vec::new();
    let mut overlay_v6 = Vec::new();
    let mut lan_v6 = Vec::new();
    unsafe {
        let mut ifap: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut ifap) != 0 {
            return (lan_v4, Vec::new(), lan_v6);
        }
        let mut ptr = ifap;
        while !ptr.is_null() {
            let ifa = &*ptr;
            let flags = ifa.ifa_flags as u32;
            let up = libc::IFF_UP as u32;
            let loopback = libc::IFF_LOOPBACK as u32;
            if flags & up == 0 || flags & loopback != 0 || ifa.ifa_addr.is_null() {
                ptr = ifa.ifa_next;
                continue;
            }
            let if_name = if ifa.ifa_name.is_null() {
                None
            } else {
                std::ffi::CStr::from_ptr(ifa.ifa_name).to_str().ok()
            };
            if if_name.is_some_and(is_virtual_interface) {
                ptr = ifa.ifa_next;
                continue;
            }
            let overlay = if_name.is_some_and(is_overlay_interface);
            let family = (*ifa.ifa_addr).sa_family as i32;
            if family == libc::AF_INET {
                let sin = &*(ifa.ifa_addr as *const libc::sockaddr_in);
                let ip = std::net::Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr));
                if !ip.is_loopback() && !ip.is_unspecified() {
                    if overlay {
                        overlay_v4.push(ip.to_string());
                    } else {
                        lan_v4.push(ip.to_string());
                    }
                }
            } else if family == libc::AF_INET6 {
                let sin6 = &*(ifa.ifa_addr as *const libc::sockaddr_in6);
                let ip = std::net::Ipv6Addr::from(sin6.sin6_addr.s6_addr);
                if !ip.is_loopback() && !ip.is_unspecified() && !ip.is_unicast_link_local() {
                    if overlay {
                        overlay_v6.push(ip.to_string());
                    } else {
                        lan_v6.push(ip.to_string());
                    }
                }
            }
            ptr = ifa.ifa_next;
        }
        libc::freeifaddrs(ifap);
    }
    let mut overlay = overlay_v4;
    overlay.extend(overlay_v6);
    (dedup(lan_v4), dedup(overlay), dedup(lan_v6))
}

fn dedup(values: Vec<String>) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for value in values {
        if value.is_empty() {
            continue;
        }
        if seen.insert(value.clone()) {
            out.push(value);
        }
    }
    out
}

pub fn ssh_listen_port(config: &Path) -> Option<u16> {
    let mut visited = HashSet::new();
    find_port(config, 0, &mut visited)
}

fn find_port(path: &Path, depth: usize, visited: &mut HashSet<PathBuf>) -> Option<u16> {
    if depth > MAX_INCLUDE_DEPTH || visited.len() >= MAX_INCLUDE_FILES {
        return None;
    }
    let Ok(canonical) = path.canonicalize() else {
        return None;
    };
    if !visited.insert(canonical.clone()) {
        return None;
    }
    let Ok(text) = fs::read_to_string(path) else {
        return None;
    };
    let base = path.parent().unwrap_or_else(|| Path::new("/"));
    for raw in text.lines() {
        let line = strip_ssh_comment(raw);
        if line.is_empty() {
            continue;
        }
        let mut parts = line.split_whitespace();
        let Some(key) = parts.next() else {
            continue;
        };
        if key.eq_ignore_ascii_case("Port") {
            if let Some(value) = parts.next() {
                if let Ok(port) = value.parse::<u16>() {
                    if port != 0 {
                        return Some(port);
                    }
                }
            }
        } else if key.eq_ignore_ascii_case("Include") {
            if let Some(pattern) = parts.next() {
                for included in expand_include(base, pattern) {
                    if let Some(port) = find_port(&included, depth + 1, visited) {
                        return Some(port);
                    }
                }
            }
        }
    }
    None
}

fn strip_ssh_comment(line: &str) -> &str {
    let trimmed = line.trim();
    match trimmed.find('#') {
        Some(index) => trimmed[..index].trim(),
        None => trimmed,
    }
}

fn expand_include(base: &Path, pattern: &str) -> Vec<PathBuf> {
    let unquoted = pattern.trim_matches('"').trim_matches('\'');
    let path = if unquoted.starts_with('/') {
        PathBuf::from(unquoted)
    } else {
        base.join(unquoted)
    };
    let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
        return vec![path];
    };
    if !name.contains('*') {
        return vec![path];
    }
    let Some(parent) = path.parent() else {
        return Vec::new();
    };
    let Ok(entries) = fs::read_dir(parent) else {
        return Vec::new();
    };
    let mut matches = Vec::new();
    for entry in entries.flatten() {
        let file_name = entry.file_name();
        if glob_match(name.as_bytes(), file_name.as_bytes()) {
            matches.push(entry.path());
        }
    }
    matches.sort();
    matches
}

fn glob_match(pattern: &[u8], name: &[u8]) -> bool {
    match (pattern.split_first(), name.split_first()) {
        (None, None) => true,
        (Some((b'*', rest)), None) => rest.iter().all(|byte| *byte == b'*'),
        (Some((b'*', rest)), Some(_)) => glob_match(rest, name) || glob_match(pattern, &name[1..]),
        (Some((byte, rest)), Some((got, rest_name))) if byte == got => glob_match(rest, rest_name),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sshkey::PublicKey;
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;

    fn sample_pub(comment: &str) -> String {
        let mut body = Vec::new();
        let algo = b"ssh-ed25519";
        body.extend_from_slice(&(algo.len() as u32).to_be_bytes());
        body.extend_from_slice(algo);
        body.extend_from_slice(&32u32.to_be_bytes());
        body.extend_from_slice(&[9u8; 32]);
        format!("ssh-ed25519 {} {comment}", STANDARD.encode(body))
    }

    #[test]
    fn empty_host_key_dir_is_a_hard_error() {
        let dir = tempfile::tempdir().unwrap();
        let err = host_key_fingerprints(dir.path()).unwrap_err();
        assert_eq!(err.code, "no_host_keys");
        assert!(err.message.contains("empty pin set"));
    }

    #[test]
    fn unreadable_host_keys_are_a_hard_error() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("ssh_host_ed25519_key.pub"), "not a key").unwrap();
        let err = host_key_fingerprints(dir.path()).unwrap_err();
        assert_eq!(err.code, "no_host_keys");
    }

    #[test]
    fn readable_host_keys_are_fingerprinted() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(
            dir.path().join("ssh_host_ed25519_key.pub"),
            sample_pub("host"),
        )
        .unwrap();
        let fingerprints = host_key_fingerprints(dir.path()).unwrap();
        assert_eq!(fingerprints.len(), 1);
        assert!(fingerprints[0].starts_with("SHA256:"));
        let key = parse_openssh_public_key(&sample_pub("host")).unwrap();
        assert_eq!(fingerprints[0], PublicKey::fingerprint(&key).unwrap());
    }

    #[test]
    fn sshd_port_reads_include() {
        let dir = tempfile::tempdir().unwrap();
        let main = dir.path().join("sshd_config");
        let nested = dir.path().join("sshd_config.d");
        fs::create_dir_all(&nested).unwrap();
        fs::write(&main, "Include sshd_config.d/*.conf\n").unwrap();
        fs::write(nested.join("listen.conf"), "Port 2222\n").unwrap();
        assert_eq!(ssh_listen_port(&main), Some(2222));
    }

    #[test]
    fn virtual_interface_names_are_skipped() {
        let skipped = [
            "docker0",
            "docker_gwbridge",
            "br-0123abcd",
            "bridge0",
            "veth1a2b3c",
            "virbr0",
            "vmnet8",
            "vboxnet0",
            "awdl0",
            "llw0",
        ];
        let kept = [
            "en0",
            "en1",
            "eth0",
            "wlan0",
            "wlp2s0",
            "em0",
            "bond0",
            "br0",
            "enp0s1",
            "tun0",
            "tap0",
            "utun3",
            "wg0",
            "tailscale0",
            "zt0",
        ];
        for name in skipped {
            assert!(is_virtual_interface(name), "{name} should be skipped");
            assert!(!is_overlay_interface(name), "{name} is not an overlay keep");
        }
        for name in kept {
            assert!(!is_virtual_interface(name), "{name} should be kept");
        }
        for name in ["tun0", "tap0", "utun3", "wg0", "tailscale0", "zt0"] {
            assert!(is_overlay_interface(name), "{name} is overlay");
        }
        assert!(!is_overlay_interface("en0"));
    }
}
