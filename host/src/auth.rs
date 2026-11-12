use std::collections::HashSet;
use std::io::{BufRead, Write};
use std::sync::Mutex;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde_json::{json, Value};

use crate::error::{Error, Result};
use crate::frames;
use crate::grant::{self, Grant};
use crate::paths::{self, LockFile, Paths};
use crate::role::Role;
use crate::sshkey::{self, PublicKey};
use crate::unique_json::parse_unique_value;

const AUTH_LABEL: &[u8] = b"luvia-auth-v1";
const HOST_ID_FILE: &str = "host_id";

const INPUT_METHODS: &[&str] = &[
    "agent.prompt",
    "agent.keys",
    "agent.start",
    "terminal.backend.control",
];

static USED_NONCES: std::sync::LazyLock<Mutex<HashSet<[u8; 32]>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashSet::new()));

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Hello {
    pub nonce: [u8; 32],
    pub host_id: String,
}

/// Shared encoding for signing and verifying.
/// `"luvia-auth-v1" ‖ u32be(len(host_id)) ‖ host_id ‖ nonce[32] ‖ u32be(len(device_id)) ‖ device_id`.
/// Both ids are UTF-8 and must be 1–64 bytes.
pub fn auth_message(host_id: &str, nonce: &[u8; 32], device_id: &str) -> Result<Vec<u8>> {
    let host = host_id.as_bytes();
    let device = device_id.as_bytes();
    if !(1..=64).contains(&host.len()) || !(1..=64).contains(&device.len()) {
        return Err(Error::new(
            "invalid_id",
            "host_id and device_id must be 1 to 64 UTF-8 bytes",
        ));
    }
    let mut message =
        Vec::with_capacity(AUTH_LABEL.len() + 8 + host.len() + nonce.len() + device.len());
    message.extend_from_slice(AUTH_LABEL);
    message.extend_from_slice(&(host.len() as u32).to_be_bytes());
    message.extend_from_slice(host);
    message.extend_from_slice(nonce);
    message.extend_from_slice(&(device.len() as u32).to_be_bytes());
    message.extend_from_slice(device);
    Ok(message)
}

pub fn openssh_pubkey_from_raw(raw: &[u8; 32]) -> String {
    use base64::engine::general_purpose::STANDARD;
    let mut body = Vec::with_capacity(4 + 11 + 4 + 32);
    let algo = b"ssh-ed25519";
    body.extend_from_slice(&(algo.len() as u32).to_be_bytes());
    body.extend_from_slice(algo);
    body.extend_from_slice(&32u32.to_be_bytes());
    body.extend_from_slice(raw);
    format!("ssh-ed25519 {}", STANDARD.encode(body))
}

pub fn require_ed25519(key: &PublicKey) -> Result<[u8; 32]> {
    if key.key_type != "ssh-ed25519" {
        return Err(Error::new("invalid_key", "device key must be ssh-ed25519"));
    }
    let blob = decode_standard(&key.blob)?;
    let mut input = blob.as_slice();
    let algo = read_ssh_string(&mut input)?;
    if algo != b"ssh-ed25519" {
        return Err(Error::new("invalid_key", "device key must be ssh-ed25519"));
    }
    let raw = read_ssh_string(&mut input)?;
    if raw.len() != 32 || !input.is_empty() {
        return Err(Error::new(
            "invalid_key",
            "ssh-ed25519 public key must be 32 bytes",
        ));
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(&raw);
    Ok(out)
}

fn decode_standard(blob: &str) -> Result<Vec<u8>> {
    use base64::engine::general_purpose::STANDARD;
    STANDARD
        .decode(blob.as_bytes())
        .map_err(|_| Error::new("invalid_key", "public key blob is not valid base64"))
}

fn read_ssh_string(input: &mut &[u8]) -> Result<Vec<u8>> {
    if input.len() < 4 {
        return Err(Error::new("invalid_key", "truncated ssh public key"));
    }
    let len_bytes: [u8; 4] = input[..4]
        .try_into()
        .map_err(|_| Error::new("invalid_key", "truncated ssh public key"))?;
    let len = u32::from_be_bytes(len_bytes) as usize;
    *input = &input[4..];
    if input.len() < len {
        return Err(Error::new("invalid_key", "truncated ssh public key"));
    }
    let (head, rest) = input.split_at(len);
    *input = rest;
    Ok(head.to_vec())
}

pub fn load_host_id(paths: &Paths) -> Result<String> {
    let path = paths.config_dir.join(HOST_ID_FILE);
    if let Some(existing) = read_host_id(&path)? {
        return Ok(existing);
    }
    paths.ensure_host_dirs()?;
    let _lock = LockFile::exclusive(&paths.lock_path)?;
    if let Some(existing) = read_host_id(&path)? {
        return Ok(existing);
    }
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|_| Error::new("io", "secure random is unavailable"))?;
    let id = bytes
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    paths::write_atomic(&path, format!("{id}\n").as_bytes())?;
    Ok(id)
}

fn read_host_id(path: &std::path::Path) -> Result<Option<String>> {
    paths::reject_symlink(path, "host_id")?;
    let text = paths::read_nofollow_to_string(path)?;
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    if !is_host_id(trimmed) {
        return Err(Error::new("invalid_host_id", "host_id file is corrupt"));
    }
    Ok(Some(trimmed.to_string()))
}

fn is_host_id(value: &str) -> bool {
    value.len() == 32
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

pub fn hello_frame(host_id: &str, nonce: &[u8; 32]) -> Result<Vec<u8>> {
    let mut bytes = serde_json::to_vec(&json!({
        "type": "hello",
        "nonce": URL_SAFE_NO_PAD.encode(nonce),
        "host_id": host_id,
    }))?;
    bytes.push(b'\n');
    Ok(bytes)
}

pub fn parse_hello(text: &str) -> Result<Hello> {
    let value = parse_unique_value(text.as_bytes())?;
    let object = value
        .as_object()
        .ok_or_else(|| Error::new("invalid_hello", "hello must be a JSON object"))?;
    for key in object.keys() {
        if !matches!(key.as_str(), "type" | "nonce" | "host_id") {
            return Err(Error::new(
                "invalid_hello",
                format!("hello contains unknown field {key}"),
            ));
        }
    }
    if object.get("type").and_then(Value::as_str) != Some("hello") {
        return Err(Error::new("invalid_hello", "hello type must be hello"));
    }
    let host_id = object
        .get("host_id")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_hello", "hello host_id must be a string"))?;
    if !is_host_id(host_id) {
        return Err(Error::new("invalid_hello", "hello host_id is invalid"));
    }
    let nonce_b64 = object
        .get("nonce")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_hello", "hello nonce must be a string"))?;
    let nonce = decode_nonce(nonce_b64)?;
    Ok(Hello {
        nonce,
        host_id: host_id.to_string(),
    })
}

pub fn prove_frame(device_id: &str, sig: &[u8]) -> Result<Vec<u8>> {
    if sig.len() != 64 {
        return Err(Error::new("invalid_prove", "signature must be 64 bytes"));
    }
    let mut bytes = serde_json::to_vec(&json!({
        "type": "prove",
        "device_id": device_id,
        "sig": URL_SAFE_NO_PAD.encode(sig),
    }))?;
    bytes.push(b'\n');
    Ok(bytes)
}

pub fn parse_prove(text: &str) -> Result<(String, Vec<u8>)> {
    let value = parse_unique_value(text.as_bytes())?;
    let object = value
        .as_object()
        .ok_or_else(|| Error::new("invalid_prove", "prove must be a JSON object"))?;
    for key in object.keys() {
        if !matches!(key.as_str(), "type" | "device_id" | "sig") {
            return Err(Error::new(
                "invalid_prove",
                format!("prove contains unknown field {key}"),
            ));
        }
    }
    if object.get("type").and_then(Value::as_str) != Some("prove") {
        return Err(Error::new("invalid_prove", "prove type must be prove"));
    }
    let device_id = object
        .get("device_id")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_prove", "prove device_id must be a string"))?
        .to_string();
    grant::validate_device_id(&device_id)?;
    let sig_b64 = object
        .get("sig")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_prove", "prove sig must be a string"))?;
    let sig = URL_SAFE_NO_PAD
        .decode(sig_b64.as_bytes())
        .map_err(|_| Error::new("invalid_prove", "prove sig is not valid base64url"))?;
    if sig.len() != 64 {
        return Err(Error::new("invalid_prove", "signature must be 64 bytes"));
    }
    Ok((device_id, sig))
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum AuthReply {
    Ok,
    Error { code: String, message: String },
}

/// Parse the one frame the client reads after Prove. Unknown fields fail.
pub fn parse_auth_reply(text: &str) -> Result<AuthReply> {
    let value = parse_unique_value(text.trim().as_bytes())?;
    let object = value
        .as_object()
        .ok_or_else(|| Error::new("invalid_auth", "auth reply must be a JSON object"))?;
    let kind = object
        .get("type")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_auth", "auth reply type must be a string"))?;
    match kind {
        "ok" => {
            for key in object.keys() {
                if key != "type" {
                    return Err(Error::new(
                        "invalid_auth",
                        format!("auth reply contains unknown field {key}"),
                    ));
                }
            }
            Ok(AuthReply::Ok)
        }
        "error" => {
            for key in object.keys() {
                if !matches!(key.as_str(), "type" | "code" | "message") {
                    return Err(Error::new(
                        "invalid_auth",
                        format!("auth reply contains unknown field {key}"),
                    ));
                }
            }
            let code = object
                .get("code")
                .and_then(Value::as_str)
                .ok_or_else(|| Error::new("invalid_auth", "auth reply code must be a string"))?
                .to_string();
            let message = object
                .get("message")
                .and_then(Value::as_str)
                .ok_or_else(|| Error::new("invalid_auth", "auth reply message must be a string"))?
                .to_string();
            if code.is_empty() {
                return Err(Error::new("invalid_auth", "auth reply code is empty"));
            }
            Ok(AuthReply::Error { code, message })
        }
        _ => Err(Error::new(
            "invalid_auth",
            "auth reply type must be ok or error",
        )),
    }
}

fn decode_nonce(text: &str) -> Result<[u8; 32]> {
    let bytes = URL_SAFE_NO_PAD
        .decode(text.as_bytes())
        .map_err(|_| Error::new("invalid_hello", "hello nonce is not valid base64url"))?;
    if bytes.len() != 32 {
        return Err(Error::new("invalid_hello", "hello nonce must be 32 bytes"));
    }
    let mut nonce = [0u8; 32];
    nonce.copy_from_slice(&bytes);
    Ok(nonce)
}

/// Verify `Prove` for this bridge process. A nonce is consumed once, even when
/// the signature is wrong. A missing grant is rejected before the nonce is
/// burned so deleting the grant alone blocks, independent of `authorized_keys`.
pub fn accept_prove(
    paths: &Paths,
    host_id: &str,
    nonce: &[u8; 32],
    device_id: &str,
    sig: &[u8],
) -> Result<Grant> {
    let grant = grant::load_grant(paths, device_id)?;
    consume_nonce(*nonce)?;
    verify_grant(&grant, host_id, nonce, device_id, sig)?;
    Ok(grant)
}

fn consume_nonce(nonce: [u8; 32]) -> Result<()> {
    let mut used = USED_NONCES
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if !used.insert(nonce) {
        return Err(Error::new("replay", "nonce replay rejected"));
    }
    Ok(())
}

fn verify_grant(
    grant: &Grant,
    host_id: &str,
    nonce: &[u8; 32],
    device_id: &str,
    sig: &[u8],
) -> Result<()> {
    if grant.id != device_id {
        return Err(Error::new(
            "auth_failed",
            "device id does not match the grant",
        ));
    }
    let key = sshkey::parse_openssh_public_key(&grant.pubkey_line())?;
    let raw = require_ed25519(&key)?;
    let verifying = VerifyingKey::from_bytes(&raw)
        .map_err(|_| Error::new("auth_failed", "device public key is not ed25519"))?;
    let signature = Signature::from_slice(sig)
        .map_err(|_| Error::new("auth_failed", "signature is malformed"))?;
    let message = auth_message(host_id, nonce, device_id)?;
    verifying
        .verify(&message, &signature)
        .map_err(|_| Error::new("auth_failed", "device signature was rejected"))?;
    Ok(())
}

pub fn handshake(
    paths: &Paths,
    input: &mut impl BufRead,
    output: &mut impl Write,
) -> Result<Grant> {
    let host_id = load_host_id(paths)?;
    let mut nonce = [0u8; 32];
    getrandom::fill(&mut nonce).map_err(|_| Error::new("io", "secure random is unavailable"))?;
    let hello = hello_frame(&host_id, &nonce)?;
    output.write_all(&hello)?;
    output.flush()?;
    let text = match frames::read_text_frame(input, "prove") {
        Ok(text) => text,
        Err(error) => {
            let _ = write_auth_error(output, &error);
            return Err(error);
        }
    };
    let (device_id, sig) = match parse_prove(&text) {
        Ok(parsed) => parsed,
        Err(error) => {
            let _ = write_auth_error(output, &error);
            return Err(error);
        }
    };
    match accept_prove(paths, &host_id, &nonce, &device_id, &sig) {
        Ok(grant) => {
            write_auth_ok(output)?;
            Ok(grant)
        }
        Err(error) => {
            let _ = write_auth_error(output, &error);
            Err(error)
        }
    }
}

fn write_auth_ok(output: &mut impl Write) -> Result<()> {
    frames::write_json_frame(output, &json!({"type": "ok"}))
}

fn write_auth_error(output: &mut impl Write, error: &Error) -> Result<()> {
    frames::write_json_frame(
        output,
        &json!({
            "type": "error",
            "code": error.code,
            "message": error.message,
        }),
    )
}

pub fn is_input_method(method: &str) -> bool {
    INPUT_METHODS.contains(&method)
}

pub fn reject_observer_input(role: Role, method: Option<&str>, control_action: bool) -> Result<()> {
    if role != Role::Observer {
        return Ok(());
    }
    if control_action || method.is_some_and(is_input_method) {
        return Err(Error::new("forbidden", "observer input is rejected"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grant::pair_device;
    use crate::sshkey::parse_openssh_public_key;
    use ed25519_dalek::Signer;
    use ed25519_dalek::SigningKey;
    use std::fs;
    use std::io::BufReader;
    use std::os::unix::net::UnixStream;
    use std::path::Path;
    use std::thread;
    use std::time::Duration;

    fn test_paths(root: &Path) -> Paths {
        Paths::from_parts(
            root.join("host"),
            root.join("ssh").join("authorized_keys"),
            root.join("luvus"),
        )
    }

    fn signing_key(seed_byte: u8) -> (SigningKey, PublicKey) {
        let signing = SigningKey::from_bytes(&[seed_byte; 32]);
        let raw = signing.verifying_key().to_bytes();
        let line = openssh_pubkey_from_raw(&raw);
        let key = parse_openssh_public_key(&line).unwrap();
        (signing, key)
    }

    fn paired(seed_byte: u8, role: Role) -> (tempfile::TempDir, Paths, Grant, SigningKey) {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join("ssh")).unwrap();
        let paths = test_paths(dir.path());
        let (signing, key) = signing_key(seed_byte);
        let grant = pair_device(
            &paths,
            "phone",
            role,
            &key,
            Path::new("/usr/local/bin/luvia-host"),
        )
        .unwrap();
        (dir, paths, grant, signing)
    }

    fn sign_for(signing: &SigningKey, host_id: &str, nonce: &[u8; 32], device_id: &str) -> Vec<u8> {
        signing
            .sign(&auth_message(host_id, nonce, device_id).unwrap())
            .to_bytes()
            .to_vec()
    }

    #[test]
    fn correct_signature_is_accepted() {
        let (_dir, paths, grant, signing) = paired(1, Role::Controller);
        let host_id = load_host_id(&paths).unwrap();
        let nonce = [1u8; 32];
        let sig = sign_for(&signing, &host_id, &nonce, &grant.id);
        let accepted = accept_prove(&paths, &host_id, &nonce, &grant.id, &sig).unwrap();
        assert_eq!(accepted.id, grant.id);
        assert_eq!(accepted.role, Role::Controller);
    }

    #[test]
    fn wrong_key_signature_is_rejected() {
        let (_dir, paths, grant, _signing) = paired(2, Role::Controller);
        let (other, _) = signing_key(9);
        let host_id = load_host_id(&paths).unwrap();
        let nonce = [2u8; 32];
        let sig = sign_for(&other, &host_id, &nonce, &grant.id);
        let err = accept_prove(&paths, &host_id, &nonce, &grant.id, &sig).unwrap_err();
        assert_eq!(err.code, "auth_failed");
    }

    #[test]
    fn deleted_grant_is_rejected_while_authorized_keys_remains() {
        let (_dir, paths, grant, signing) = paired(3, Role::Controller);
        let host_id = load_host_id(&paths).unwrap();
        let keys_before = paths::read_nofollow_to_string(&paths.authorized_keys).unwrap();
        assert!(keys_before.contains(&format!("luvia:{}", grant.id)));
        grant::remove_grant(&paths, &grant.id).unwrap();
        let keys_after = paths::read_nofollow_to_string(&paths.authorized_keys).unwrap();
        assert!(keys_after.contains(&format!("luvia:{}", grant.id)));
        let nonce = [3u8; 32];
        let sig = sign_for(&signing, &host_id, &nonce, &grant.id);
        let err = accept_prove(&paths, &host_id, &nonce, &grant.id, &sig).unwrap_err();
        assert_eq!(err.code, "unknown_device");
    }

    #[test]
    fn nonce_replay_is_rejected() {
        let (_dir, paths, grant, signing) = paired(4, Role::Controller);
        let host_id = load_host_id(&paths).unwrap();
        let nonce = [4u8; 32];
        let sig = sign_for(&signing, &host_id, &nonce, &grant.id);
        accept_prove(&paths, &host_id, &nonce, &grant.id, &sig).unwrap();
        let err = accept_prove(&paths, &host_id, &nonce, &grant.id, &sig).unwrap_err();
        assert_eq!(err.code, "replay");
        assert!(err.message.contains("replay"));
    }

    #[test]
    fn wrong_host_id_signature_is_rejected() {
        let (_dir, paths, grant, signing) = paired(5, Role::Controller);
        let host_id = load_host_id(&paths).unwrap();
        let nonce = [5u8; 32];
        let sig = sign_for(
            &signing,
            "cccccccccccccccccccccccccccccccc",
            &nonce,
            &grant.id,
        );
        let err = accept_prove(&paths, &host_id, &nonce, &grant.id, &sig).unwrap_err();
        assert_eq!(err.code, "auth_failed");
    }

    #[test]
    fn observer_input_is_rejected() {
        let err = reject_observer_input(Role::Observer, Some("agent.prompt"), false).unwrap_err();
        assert_eq!(err.code, "forbidden");
        assert!(err.message.contains("observer input is rejected"));
        let err = reject_observer_input(Role::Observer, Some("terminal.backend.control"), false)
            .unwrap_err();
        assert_eq!(err.code, "forbidden");
        let err = reject_observer_input(Role::Observer, None, true).unwrap_err();
        assert_eq!(err.code, "forbidden");
        reject_observer_input(Role::Observer, Some("ping"), false).unwrap();
        reject_observer_input(Role::Controller, Some("agent.prompt"), false).unwrap();
        reject_observer_input(Role::Controller, None, true).unwrap();
    }

    #[test]
    fn handshake_round_trip_accepts_the_signed_nonce() {
        let (_dir, paths, grant, signing) = paired(6, Role::Controller);
        let expected_host = load_host_id(&paths).unwrap();
        let (host_io, client_io) = UnixStream::pair().unwrap();
        host_io
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        client_io
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let host_paths = paths.clone();
        let host = thread::spawn(move || {
            let mut read = BufReader::new(host_io.try_clone().unwrap());
            let mut write = host_io;
            handshake(&host_paths, &mut read, &mut write)
        });
        let mut client_read = BufReader::new(client_io.try_clone().unwrap());
        let mut client_write = client_io;
        let hello_text = frames::read_text_frame(&mut client_read, "hello").unwrap();
        let hello = parse_hello(&hello_text).unwrap();
        assert_eq!(hello.nonce.len(), 32);
        assert_eq!(hello.host_id, expected_host);
        let sig = sign_for(&signing, &hello.host_id, &hello.nonce, &grant.id);
        let frame = prove_frame(&grant.id, &sig).unwrap();
        client_write.write_all(&frame).unwrap();
        let reply = frames::read_text_frame(&mut client_read, "auth").unwrap();
        assert_eq!(parse_auth_reply(&reply).unwrap(), AuthReply::Ok);
        let accepted = host.join().unwrap().unwrap();
        assert_eq!(accepted.id, grant.id);
    }

    #[test]
    fn deleted_grant_handshake_fails_with_the_host_reason() {
        let (_dir, paths, grant, signing) = paired(7, Role::Controller);
        grant::remove_grant(&paths, &grant.id).unwrap();
        let expected_host = load_host_id(&paths).unwrap();
        let (host_io, client_io) = UnixStream::pair().unwrap();
        host_io
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        client_io
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let host_paths = paths.clone();
        let host = thread::spawn(move || {
            let mut read = BufReader::new(host_io.try_clone().unwrap());
            let mut write = host_io;
            handshake(&host_paths, &mut read, &mut write)
        });
        let mut client_read = BufReader::new(client_io.try_clone().unwrap());
        let mut client_write = client_io;
        let hello_text = frames::read_text_frame(&mut client_read, "hello").unwrap();
        let hello = parse_hello(&hello_text).unwrap();
        assert_eq!(hello.host_id, expected_host);
        let sig = sign_for(&signing, &hello.host_id, &hello.nonce, &grant.id);
        client_write
            .write_all(&prove_frame(&grant.id, &sig).unwrap())
            .unwrap();
        let reply = frames::read_text_frame(&mut client_read, "auth").unwrap();
        let parsed = parse_auth_reply(&reply).unwrap();
        let AuthReply::Error { code, message } = parsed else {
            panic!("deleted grant must not ack ok: {reply}");
        };
        let err = host.join().unwrap().unwrap_err();
        assert_eq!(err.code, "unknown_device");
        assert_eq!(code, "unknown_device");
        assert!(message.contains("no pairing grant"), "{message}");
        assert_eq!(message, err.message);
        assert!(message.contains(&grant.id), "{message}");
    }

    #[test]
    fn ok_and_error_replies_reject_unknown_fields() {
        let frame = b"{\"type\":\"ok\"}\n";
        let text = std::str::from_utf8(frame).unwrap().trim();
        assert_eq!(parse_auth_reply(text).unwrap(), AuthReply::Ok);
        let err = parse_auth_reply(r#"{"type":"ok","extra":1}"#).unwrap_err();
        assert_eq!(err.code, "invalid_auth");
        assert!(err.message.contains("unknown field"));
        let err = parse_auth_reply(
            r#"{"type":"error","code":"unknown_device","message":"no pairing grant for x","extra":true}"#,
        )
        .unwrap_err();
        assert!(err.message.contains("unknown field"));
    }

    #[test]
    fn read_host_id_is_stable() {
        let dir = tempfile::tempdir().unwrap();
        let paths = test_paths(dir.path());
        let first = load_host_id(&paths).unwrap();
        let second = load_host_id(&paths).unwrap();
        assert_eq!(first, second);
        assert_eq!(first.len(), 32);
    }

    fn prefixed(host_id: &str, nonce: &[u8; 32], device_id: &str) -> Vec<u8> {
        let host = host_id.as_bytes();
        let device = device_id.as_bytes();
        let mut message = b"luvia-auth-v1".to_vec();
        message.extend_from_slice(&(host.len() as u32).to_be_bytes());
        message.extend_from_slice(host);
        message.extend_from_slice(nonce);
        message.extend_from_slice(&(device.len() as u32).to_be_bytes());
        message.extend_from_slice(device);
        message
    }

    #[test]
    fn length_prefix_splits_id_boundary() {
        let nonce = [0x11u8; 32];
        let left = auth_message("ab", &nonce, "c").unwrap();
        let right = auth_message("a", &nonce, "bc").unwrap();
        assert_ne!(left, right);
        assert_eq!(left, prefixed("ab", &nonce, "c"));
        assert_eq!(right, prefixed("a", &nonce, "bc"));
        let signed_left = sign_for(&SigningKey::from_bytes(&[1u8; 32]), "ab", &nonce, "c");
        let signed_right = sign_for(&SigningKey::from_bytes(&[1u8; 32]), "a", &nonce, "bc");
        assert_ne!(signed_left, signed_right);
    }

    #[test]
    fn out_of_range_id_lengths_are_rejected() {
        let nonce = [0x22u8; 32];
        let long = "x".repeat(65);
        for (host_id, device_id) in [
            ("", "ab"),
            ("ab", ""),
            (long.as_str(), "ab"),
            ("ab", long.as_str()),
        ] {
            let err = auth_message(host_id, &nonce, device_id).unwrap_err();
            assert_eq!(err.code, "invalid_id");
        }
        assert!(auth_message("y", &nonce, &"z".repeat(64)).is_ok());
        assert!(auth_message(&"y".repeat(64), &nonce, "z").is_ok());
    }
}
