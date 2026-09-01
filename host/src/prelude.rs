use serde_json::{Map, Value};

use crate::error::{Error, Result};
use crate::unique_json::parse_unique_value;

pub const PRELUDE_VERSION: u64 = 1;
pub const MAX_SESSION_NAME_LEN: usize = 64;
pub const DEFAULT_SESSION_NAME: &str = "default";

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Prelude {
    Discover,
    Open { session: String },
}

pub fn parse_prelude(payload: &str) -> Result<Prelude> {
    let value = parse_unique_value(payload.as_bytes())?;
    let object = value
        .as_object()
        .ok_or_else(|| Error::new("invalid_prelude", "prelude must be a JSON object"))?;
    for key in object.keys() {
        if !matches!(key.as_str(), "version" | "operation" | "session") {
            return Err(Error::new(
                "invalid_prelude",
                format!("prelude contains unknown field {key}"),
            ));
        }
    }
    match object.get("version") {
        Some(Value::Number(number)) if number.as_u64() == Some(PRELUDE_VERSION) => {}
        _ => {
            return Err(Error::new(
                "invalid_prelude",
                "prelude version must be the integer 1",
            ));
        }
    }
    let operation = object
        .get("operation")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_prelude", "prelude operation must be a string"))?;
    match operation {
        "discover" => {
            if object.contains_key("session") {
                return Err(Error::new(
                    "invalid_prelude",
                    "discover must not include a session field",
                ));
            }
            Ok(Prelude::Discover)
        }
        "open" => {
            let session = object
                .get("session")
                .and_then(Value::as_str)
                .ok_or_else(|| Error::new("invalid_prelude", "open requires a session string"))?;
            validate_session_name(session)?;
            Ok(Prelude::Open {
                session: session.to_string(),
            })
        }
        other => Err(Error::new(
            "invalid_prelude",
            format!("unknown prelude operation {other}"),
        )),
    }
}

pub fn validate_session_name(name: &str) -> Result<()> {
    if name.is_empty() {
        return Err(Error::new(
            "invalid_session",
            "session name cannot be empty",
        ));
    }
    if name.len() > MAX_SESSION_NAME_LEN {
        return Err(Error::new(
            "invalid_session",
            format!("session name cannot be longer than {MAX_SESSION_NAME_LEN} bytes"),
        ));
    }
    if matches!(name, "." | "..") {
        return Err(Error::new(
            "invalid_session",
            "session name cannot be `.` or `..`",
        ));
    }
    if !name
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        return Err(Error::new(
            "invalid_session",
            "session name may contain only ASCII letters, digits, `.`, `_`, and `-`",
        ));
    }
    Ok(())
}

pub fn discover_response(sessions: Vec<Value>) -> Value {
    serde_json::json!({
        "version": PRELUDE_VERSION,
        "sessions": sessions,
    })
}

pub fn session_metadata(name: &str, default: bool, running: bool) -> Map<String, Value> {
    let mut object = Map::new();
    object.insert("name".into(), Value::String(name.to_string()));
    object.insert("default".into(), Value::Bool(default));
    object.insert("running".into(), Value::Bool(running));
    object.insert("transport".into(), Value::String("unix_socket".into()));
    object
}

pub fn ready_frame(session: &str) -> Value {
    serde_json::json!({
        "version": PRELUDE_VERSION,
        "status": "ready",
        "session": session,
    })
}

pub fn prelude_error(code: &str, message: &str) -> Value {
    serde_json::json!({
        "version": PRELUDE_VERSION,
        "error": {
            "code": code,
            "message": message,
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discover_is_closed() {
        assert_eq!(
            parse_prelude(r#"{"version":1,"operation":"discover"}"#).unwrap(),
            Prelude::Discover
        );
    }

    #[test]
    fn open_requires_session() {
        let err = parse_prelude(r#"{"version":1,"operation":"open"}"#).unwrap_err();
        assert_eq!(err.code, "invalid_prelude");
    }

    #[test]
    fn extra_fields_are_rejected() {
        let err =
            parse_prelude(r#"{"version":1,"operation":"discover","socket":"/tmp/luvus.sock"}"#)
                .unwrap_err();
        assert_eq!(err.code, "invalid_prelude");
    }

    #[test]
    fn client_socket_path_is_rejected() {
        let err = parse_prelude(
            r#"{"version":1,"operation":"open","session":"default","address":"/tmp/x.sock"}"#,
        )
        .unwrap_err();
        assert_eq!(err.code, "invalid_prelude");
    }

    #[test]
    fn duplicate_keys_are_rejected() {
        let err = parse_prelude(r#"{"version":1,"operation":"discover","operation":"open"}"#)
            .unwrap_err();
        assert_eq!(err.code, "invalid_json");
    }

    #[test]
    fn version_must_be_integer_one() {
        let err = parse_prelude(r#"{"version":"1","operation":"discover"}"#).unwrap_err();
        assert_eq!(err.code, "invalid_prelude");
    }

    #[test]
    fn unknown_operation_is_rejected() {
        let err = parse_prelude(r#"{"version":1,"operation":"connect"}"#).unwrap_err();
        assert_eq!(err.code, "invalid_prelude");
    }

    #[test]
    fn session_name_rejects_path_chars() {
        assert!(validate_session_name("default").is_ok());
        assert!(validate_session_name("../etc").is_err());
        assert!(validate_session_name("a/b").is_err());
        assert!(validate_session_name("has space").is_err());
    }
}
