use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;

use serde_json::{json, Value};

use crate::capabilities::Capabilities;
use crate::endpoint::{self, Evidence};
use crate::error::{Error, Result};
use crate::frames::{self, write_frame};
use crate::unique_json::{parse_unique_value, reject_duplicate_keys};

pub fn fetch_capabilities(path: &Path, evidence: Evidence) -> Result<Capabilities> {
    let mut stream = endpoint::connect_validated(path, evidence)?;
    let request = json!({
        "id": "luvia-capabilities",
        "method": "uhp.capabilities",
        "params": {}
    });
    let payload = serde_json::to_vec(&request)?;
    write_frame(&mut stream, &payload)?;
    let response = read_one(&mut stream, "capabilities response")?;
    let value = parse_unique_value(response.as_bytes())?;
    if let Some(error) = value.get("error") {
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("uhp.capabilities failed");
        return Err(Error::new("auth_failed", message));
    }
    Capabilities::parse(&value)
}

pub fn request_method(payload: &[u8]) -> Result<String> {
    let value = parse_unique_value(payload)?;
    let object = value
        .as_object()
        .ok_or_else(|| Error::new("invalid_request", "UHP request must be a JSON object"))?;
    for key in object.keys() {
        if !matches!(key.as_str(), "id" | "method" | "params" | "auth") {
            return Err(Error::new(
                "invalid_request",
                format!("UHP request contains unknown field {key}"),
            ));
        }
    }
    let method = object
        .get("method")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_request", "UHP request method must be a string"))?;
    if method.is_empty() || method.len() > 128 {
        return Err(Error::new(
            "invalid_request",
            "UHP request method is invalid",
        ));
    }
    Ok(method.to_string())
}

pub const TOKEN_TTL_S: u64 = 3600;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MintedToken {
    pub id: String,
    pub secret: String,
}

pub fn request_has_auth(payload: &[u8]) -> Result<bool> {
    let value = parse_unique_value(payload)?;
    Ok(value
        .as_object()
        .is_some_and(|object| object.contains_key("auth")))
}

pub fn inject_auth(payload: &[u8], token: &str) -> Result<Vec<u8>> {
    if token.is_empty() || token.len() > 256 || !token.bytes().all(|byte| byte.is_ascii_graphic()) {
        return Err(Error::new(
            "auth_failed",
            "minted token is not a valid UHP secret",
        ));
    }
    reject_duplicate_keys(payload)?;
    let mut value: Value = serde_json::from_slice(payload)?;
    let object = value
        .as_object_mut()
        .ok_or_else(|| Error::new("invalid_request", "UHP request must be a JSON object"))?;
    if object.contains_key("auth") {
        return Err(Error::new(
            "auth_rejected",
            "client-supplied auth is not allowed",
        ));
    }
    for key in object.keys() {
        if !matches!(key.as_str(), "id" | "method" | "params") {
            return Err(Error::new(
                "invalid_request",
                format!("UHP request contains unknown field {key}"),
            ));
        }
    }
    if !object.contains_key("id")
        || !object.contains_key("method")
        || !object.contains_key("params")
    {
        return Err(Error::new(
            "invalid_request",
            "UHP request must include id, method, and params",
        ));
    }
    object.insert("auth".into(), Value::String(token.to_string()));
    Ok(serde_json::to_vec(&value)?)
}

pub fn mint_token(path: &Path, evidence: Evidence, scopes: &[&str]) -> Result<MintedToken> {
    if scopes.is_empty() {
        return Err(Error::new(
            "auth_failed",
            "delegated token scopes are empty",
        ));
    }
    let mut stream = endpoint::connect_validated(path, evidence)?;
    let request = json!({
        "id": "luvia-token-create",
        "method": "uhp.token.create",
        "params": {
            "scopes": scopes,
            "ttl_s": TOKEN_TTL_S,
        }
    });
    let payload = serde_json::to_vec(&request)?;
    write_frame(&mut stream, &payload)?;
    let response = read_one(&mut stream, "token response")?;
    let value = parse_unique_value(response.as_bytes())?;
    if let Some(error) = value.get("error") {
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("delegated token creation failed");
        return Err(Error::new("auth_failed", message));
    }
    let result = value
        .get("result")
        .ok_or_else(|| Error::new("auth_failed", "token create returned no result"))?;
    let secret = result
        .get("token")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("auth_failed", "token create returned no secret"))?;
    let id = result
        .get("id")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("auth_failed", "token create returned no id"))?;
    if secret.is_empty() || id.is_empty() {
        return Err(Error::new(
            "auth_failed",
            "token create returned an empty token",
        ));
    }
    Ok(MintedToken {
        id: id.to_string(),
        secret: secret.to_string(),
    })
}

pub fn revoke_token(path: &Path, evidence: Evidence, token_id: &str) -> Result<()> {
    let mut stream = endpoint::connect_validated(path, evidence)?;
    let request = json!({
        "id": "luvia-token-revoke",
        "method": "uhp.token.revoke",
        "params": { "id": token_id }
    });
    let payload = serde_json::to_vec(&request)?;
    write_frame(&mut stream, &payload)?;
    let _ = read_one(&mut stream, "token revoke response");
    Ok(())
}

pub fn open_request_stream(path: &Path, evidence: Evidence, injected: &[u8]) -> Result<UnixStream> {
    let mut stream = endpoint::connect_validated(path, evidence)?;
    write_frame(&mut stream, injected)?;
    Ok(stream)
}

fn read_one(stream: &mut UnixStream, kind: &str) -> Result<String> {
    let mut reader = BufReader::new(stream);
    frames::read_text_frame(&mut reader, kind)
}

/// Copy bounded LF frames until EOF. Does not interpret payload contents.
pub fn copy_frames(reader: &mut impl BufRead, writer: &mut impl Write) -> Result<()> {
    loop {
        match frames::read_frame(reader) {
            Ok(frame) => {
                writer.write_all(&frame)?;
                writer.flush()?;
            }
            Err(frames::FrameError::Eof) => return Ok(()),
            Err(error) => return Err(error.into_error("stream")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_auth_is_rejected_and_not_injected() {
        let request = br#"{"id":"1","method":"ping","params":{},"auth":"secret"}"#;
        assert!(request_has_auth(request).unwrap());
        let err = inject_auth(request, "luv_tok_abc").unwrap_err();
        assert_eq!(err.code, "auth_rejected");
    }

    #[test]
    fn injects_minted_token_once() {
        let request = br#"{"id":"1","method":"uhp.capabilities","params":{}}"#;
        assert!(!request_has_auth(request).unwrap());
        let injected = inject_auth(request, "luv_tok_abc").unwrap();
        let value: Value = serde_json::from_slice(&injected).unwrap();
        assert_eq!(value["auth"], "luv_tok_abc");
        assert_eq!(value["method"], "uhp.capabilities");
        let err = inject_auth(&injected, "luv_tok_other").unwrap_err();
        assert_eq!(err.code, "auth_rejected");
    }

    #[test]
    fn never_omits_auth_on_empty_token() {
        let request = br#"{"id":"1","method":"ping","params":{}}"#;
        let err = inject_auth(request, "").unwrap_err();
        assert_eq!(err.code, "auth_failed");
    }

    #[test]
    fn duplicate_auth_key_is_rejected() {
        let request = br#"{"id":"1","method":"ping","params":{},"auth":"a","auth":"b"}"#;
        assert!(request_has_auth(request).is_err());
    }
}
