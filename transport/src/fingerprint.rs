// Copyright 2026 AsahiArt
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use russh::keys::{HashAlg, PublicKey};
use subtle::ConstantTimeEq;

use crate::error::TransportError;

const SHA256_LEN: usize = 32;

/// OpenSSH-style unpadded SHA-256 fingerprint (`SHA256:<base64>`).
pub fn format_sha256(digest: &[u8; SHA256_LEN]) -> String {
    let mut encoded = data_encoding::BASE64.encode(digest);
    while encoded.ends_with('=') {
        encoded.pop();
    }
    format!("SHA256:{encoded}")
}

pub fn public_key_sha256(key: &PublicKey) -> [u8; SHA256_LEN] {
    key.fingerprint(HashAlg::Sha256)
        .sha256()
        .unwrap_or([0u8; SHA256_LEN])
}

pub fn format_public_key_sha256(key: &PublicKey) -> String {
    format_sha256(&public_key_sha256(key))
}

pub fn fingerprints_equal(a: &[u8; SHA256_LEN], b: &[u8; SHA256_LEN]) -> bool {
    a.ct_eq(b).into()
}

/// Accept OpenSSH `SHA256:` base64 (padded or not), bare base64, or hex
/// (with optional colons). MD5 fingerprints are rejected.
pub fn parse_sha256_fingerprint(input: &str) -> Result<[u8; SHA256_LEN], TransportError> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err(TransportError::invalid_fingerprint("empty fingerprint"));
    }

    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("md5:") {
        return Err(TransportError::invalid_fingerprint(
            "md5 fingerprints are not accepted",
        ));
    }

    let body = if let Some(rest) = strip_sha256_prefix(trimmed) {
        rest.trim()
    } else {
        trimmed
    };

    if body.is_empty() {
        return Err(TransportError::invalid_fingerprint("empty fingerprint"));
    }

    if let Some(digest) = decode_hex_fingerprint(body) {
        return Ok(digest);
    }

    decode_base64_fingerprint(body)
}

fn strip_sha256_prefix(input: &str) -> Option<&str> {
    let bytes = input.as_bytes();
    if bytes.len() < 7 {
        return None;
    }
    let prefix = &input[..7];
    if prefix.eq_ignore_ascii_case("sha256:") {
        Some(&input[7..])
    } else {
        None
    }
}

fn decode_hex_fingerprint(body: &str) -> Option<[u8; SHA256_LEN]> {
    let mut hex = String::with_capacity(body.len());
    for ch in body.chars() {
        if ch == ':' || ch.is_ascii_whitespace() {
            continue;
        }
        if !ch.is_ascii_hexdigit() {
            return None;
        }
        hex.push(ch.to_ascii_lowercase());
    }
    if hex.len() != SHA256_LEN * 2 {
        return None;
    }
    let decoded = data_encoding::HEXLOWER.decode(hex.as_bytes()).ok()?;
    let mut out = [0u8; SHA256_LEN];
    out.copy_from_slice(&decoded);
    Some(out)
}

fn decode_base64_fingerprint(body: &str) -> Result<[u8; SHA256_LEN], TransportError> {
    let compact: String = body
        .chars()
        .filter(|ch| !ch.is_ascii_whitespace())
        .collect();
    let decoded = data_encoding::BASE64
        .decode(compact.as_bytes())
        .or_else(|_| {
            data_encoding::BASE64
                .decode(pad_base64(&compact).as_bytes())
        })
        .or_else(|_| data_encoding::BASE64_NOPAD.decode(compact.as_bytes()))
        .map_err(|_| TransportError::invalid_fingerprint("fingerprint is not valid base64"))?;
    if decoded.len() != SHA256_LEN {
        return Err(TransportError::invalid_fingerprint(
            "fingerprint must be a sha-256 digest",
        ));
    }
    let mut out = [0u8; SHA256_LEN];
    out.copy_from_slice(&decoded);
    Ok(out)
}

fn pad_base64(input: &str) -> String {
    let mut padded = input.to_string();
    while padded.len() % 4 != 0 {
        padded.push('=');
    }
    padded
}

#[cfg(test)]
mod tests {
    use super::*;
    use russh::keys::{Algorithm, PrivateKey};

    fn sample_digest() -> ([u8; 32], PublicKey) {
        let key = PrivateKey::random(&mut crate::rng::OsCryptoRng, Algorithm::Ed25519).unwrap();
        let public = key.public_key().clone();
        (public_key_sha256(&public), public)
    }

    #[test]
    fn normalizes_prefix_padding_hex_and_whitespace() {
        let (digest, public) = sample_digest();
        let canonical = format_sha256(&digest);
        assert_eq!(canonical, format_public_key_sha256(&public));
        assert!(canonical.starts_with("SHA256:"));
        assert!(!canonical.ends_with('='));

        let b64 = canonical.trim_start_matches("SHA256:").to_string();
        let padded = pad_base64(&b64);
        let hex = data_encoding::HEXLOWER.encode(&digest);
        let colon_hex = hex
            .as_bytes()
            .chunks(2)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect::<Vec<_>>()
            .join(":");

        let variants = [
            canonical.clone(),
            format!("  {canonical}  "),
            b64.clone(),
            padded,
            hex,
            colon_hex,
            format!("sha256:{b64}"),
        ];
        for variant in variants {
            let parsed = parse_sha256_fingerprint(&variant).expect(&variant);
            assert!(fingerprints_equal(&parsed, &digest), "{variant}");
        }
    }

    #[test]
    fn mismatch_and_md5_rejected() {
        let (digest, _) = sample_digest();
        let other = [0u8; 32];
        assert!(!fingerprints_equal(&digest, &other));
        assert!(parse_sha256_fingerprint("md5:aa:bb").is_err());
        assert!(parse_sha256_fingerprint("").is_err());
        assert!(parse_sha256_fingerprint("SHA256:$$$$").is_err());
    }
}
