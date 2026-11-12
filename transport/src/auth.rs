use data_encoding::BASE64URL_NOPAD;
use ed25519_dalek::{Signer, SigningKey};
use serde_json::{json, Value};

use crate::error::TransportError;

const AUTH_LABEL: &[u8] = b"luvia-auth-v1";

/// SSH user authentication offered by the Luvia client. Public key only.
pub const CLIENT_SSH_AUTH: &[&str] = &["publickey"];

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Hello {
    pub nonce: [u8; 32],
    pub host_id: String,
}

pub fn auth_message(host_id: &str, nonce: &[u8; 32], device_id: &str) -> Vec<u8> {
    let mut message =
        Vec::with_capacity(AUTH_LABEL.len() + host_id.len() + nonce.len() + device_id.len());
    message.extend_from_slice(AUTH_LABEL);
    message.extend_from_slice(host_id.as_bytes());
    message.extend_from_slice(nonce);
    message.extend_from_slice(device_id.as_bytes());
    message
}

pub fn sign_message(seed: &[u8; 32], message: &[u8]) -> [u8; 64] {
    SigningKey::from_bytes(seed).sign(message).to_bytes()
}

/// Build a Prove frame. The signer runs only after the SSH host key has been
/// accepted and `Hello.host_id` matches the pairing record.
pub fn client_prove(
    host_key_verified: bool,
    expected_host_id: &str,
    device_id: &str,
    hello_frame: &[u8],
    mut sign: impl FnMut(&[u8]) -> Result<[u8; 64], TransportError>,
) -> Result<Vec<u8>, TransportError> {
    if !host_key_verified {
        return Err(TransportError::HostKeyUnverified);
    }
    let hello = parse_hello(hello_frame)?;
    if hello.host_id != expected_host_id {
        return Err(TransportError::HostIdMismatch);
    }
    let message = auth_message(&hello.host_id, &hello.nonce, device_id);
    let sig = sign(&message)?;
    let mut frame = serde_json::to_vec(&json!({
        "type": "prove",
        "device_id": device_id,
        "sig": BASE64URL_NOPAD.encode(&sig),
    }))
    .map_err(|_| TransportError::io("prove frame"))?;
    frame.push(b'\n');
    Ok(frame)
}

pub fn parse_hello(frame: &[u8]) -> Result<Hello, TransportError> {
    let text = std::str::from_utf8(frame)
        .map_err(|_| TransportError::io("hello is not utf-8"))?
        .trim_end_matches(['\n', '\r']);
    let value: Value =
        serde_json::from_str(text).map_err(|_| TransportError::io("hello is not json"))?;
    let object = value
        .as_object()
        .ok_or_else(|| TransportError::io("hello must be an object"))?;
    if object.get("type").and_then(Value::as_str) != Some("hello") {
        return Err(TransportError::io("hello type must be hello"));
    }
    let host_id = object
        .get("host_id")
        .and_then(Value::as_str)
        .ok_or_else(|| TransportError::io("hello host_id must be a string"))?
        .to_string();
    let nonce_b64 = object
        .get("nonce")
        .and_then(Value::as_str)
        .ok_or_else(|| TransportError::io("hello nonce must be a string"))?;
    let nonce_bytes = BASE64URL_NOPAD
        .decode(nonce_b64.as_bytes())
        .map_err(|_| TransportError::io("hello nonce is not base64url"))?;
    if nonce_bytes.len() != 32 {
        return Err(TransportError::io("hello nonce must be 32 bytes"));
    }
    let mut nonce = [0u8; 32];
    nonce.copy_from_slice(&nonce_bytes);
    Ok(Hello { nonce, host_id })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hello(host_id: &str) -> Vec<u8> {
        let mut frame = serde_json::to_vec(&json!({
            "type": "hello",
            "nonce": BASE64URL_NOPAD.encode(&[0x11u8; 32]),
            "host_id": host_id,
        }))
        .unwrap();
        frame.push(b'\n');
        frame
    }

    #[test]
    fn client_ssh_auth_is_publickey_only() {
        assert_eq!(CLIENT_SSH_AUTH, ["publickey"]);
        assert!(!CLIENT_SSH_AUTH.contains(&"none"));
        assert!(!CLIENT_SSH_AUTH.contains(&"password"));
    }

    #[test]
    fn does_not_sign_before_host_key_verification() {
        let mut signed = false;
        let err = client_prove(false, "host", "device", &hello("host"), |_| {
            signed = true;
            Ok([0u8; 64])
        })
        .unwrap_err();
        assert!(matches!(err, TransportError::HostKeyUnverified));
        assert!(!signed);
    }

    #[test]
    fn does_not_sign_when_hello_host_id_mismatches_pairing_record() {
        let mut signed = false;
        let err = client_prove(true, "paired-host", "device", &hello("other-host"), |_| {
            signed = true;
            Ok([7u8; 64])
        })
        .unwrap_err();
        assert!(matches!(err, TransportError::HostIdMismatch));
        assert!(!signed);
    }

    #[test]
    fn signs_the_canonical_message_after_host_key_verification() {
        let mut seen = Vec::new();
        let frame = client_prove(
            true,
            "paired-host",
            "dev",
            &hello("paired-host"),
            |message| {
                seen = message.to_vec();
                Ok(sign_message(&[3u8; 32], message))
            },
        )
        .unwrap();
        let expected = auth_message("paired-host", &[0x11u8; 32], "dev");
        assert_eq!(seen, expected);
        assert!(frame.ends_with(b"\n"));
        let value: Value = serde_json::from_slice(&frame[..frame.len() - 1]).unwrap();
        assert_eq!(value["type"], "prove");
        assert_eq!(value["device_id"], "dev");
        let sig = BASE64URL_NOPAD
            .decode(value["sig"].as_str().unwrap().as_bytes())
            .unwrap();
        assert_eq!(sig.len(), 64);
    }
}
