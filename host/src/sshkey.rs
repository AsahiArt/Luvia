use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine;
use sha2::{Digest, Sha256};

use crate::error::{Error, Result};

const MAX_KEY_BYTES: usize = 16 * 1024;

const KEY_TYPES: &[&str] = &[
    "ssh-ed25519",
    "ssh-rsa",
    "ecdsa-sha2-nistp256",
    "ecdsa-sha2-nistp384",
    "ecdsa-sha2-nistp521",
    "sk-ssh-ed25519@openssh.com",
    "sk-ecdsa-sha2-nistp256@openssh.com",
];

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PublicKey {
    pub key_type: String,
    pub blob: String,
    pub comment: String,
}

impl PublicKey {
    pub fn fingerprint(&self) -> Result<String> {
        let decoded = STANDARD
            .decode(self.blob.as_bytes())
            .map_err(|_| Error::new("invalid_key", "public key blob is not valid base64"))?;
        if decoded.is_empty() {
            return Err(Error::new("invalid_key", "public key blob is empty"));
        }
        let digest = Sha256::digest(&decoded);
        Ok(format!("SHA256:{}", STANDARD_NO_PAD.encode(digest)))
    }

    pub fn authorized_keys_material(&self) -> String {
        format!("{} {}", self.key_type, self.blob)
    }
}

pub fn parse_openssh_public_key(input: &str) -> Result<PublicKey> {
    if input.len() > MAX_KEY_BYTES {
        return Err(Error::new("invalid_key", "public key exceeds 16 KiB"));
    }
    if input.contains('\r') {
        return Err(Error::new("invalid_key", "public key must not contain CR"));
    }
    let mut lines = input.lines();
    let Some(line) = lines.next() else {
        return Err(Error::new(
            "invalid_key",
            "stdin did not contain a public key",
        ));
    };
    if line.trim().is_empty() {
        return Err(Error::new("invalid_key", "public key line is empty"));
    }
    for rest in lines {
        if !rest.trim().is_empty() {
            return Err(Error::new(
                "invalid_key",
                "stdin must contain exactly one OpenSSH public key",
            ));
        }
    }
    let mut parts = line.split_whitespace();
    let key_type = parts
        .next()
        .ok_or_else(|| Error::new("invalid_key", "public key is missing a type"))?;
    if !KEY_TYPES.contains(&key_type) {
        return Err(Error::new(
            "invalid_key",
            format!("unsupported OpenSSH key type {key_type}"),
        ));
    }
    let blob = parts
        .next()
        .ok_or_else(|| Error::new("invalid_key", "public key is missing a base64 blob"))?;
    if STANDARD.decode(blob.as_bytes()).is_err() {
        return Err(Error::new(
            "invalid_key",
            "public key blob is not valid base64",
        ));
    }
    let comment = parts.collect::<Vec<_>>().join(" ");
    Ok(PublicKey {
        key_type: key_type.to_string(),
        blob: blob.to_string(),
        comment,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // ssh-ed25519 with 32 zero bytes of key material after the algorithm prefix.
    fn sample_key() -> String {
        let mut body = Vec::new();
        let algo = b"ssh-ed25519";
        body.extend_from_slice(&(algo.len() as u32).to_be_bytes());
        body.extend_from_slice(algo);
        body.extend_from_slice(&32u32.to_be_bytes());
        body.extend_from_slice(&[0u8; 32]);
        format!("ssh-ed25519 {} device", STANDARD.encode(body))
    }

    #[test]
    fn parses_single_ed25519_key() {
        let key = parse_openssh_public_key(&sample_key()).unwrap();
        assert_eq!(key.key_type, "ssh-ed25519");
        assert_eq!(key.comment, "device");
        assert!(key.fingerprint().unwrap().starts_with("SHA256:"));
    }

    #[test]
    fn rejects_two_keys() {
        let key = sample_key();
        let err = parse_openssh_public_key(&format!("{key}\n{key}\n")).unwrap_err();
        assert_eq!(err.code, "invalid_key");
    }

    #[test]
    fn rejects_unknown_type() {
        let err = parse_openssh_public_key("ssh-unknown AAAA comment\n").unwrap_err();
        assert_eq!(err.code, "invalid_key");
    }
}
