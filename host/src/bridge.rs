use std::io::{self, BufRead, BufReader, Write};
use std::net::Shutdown;
use std::thread;

use serde_json::{json, Value};

use crate::discovery::{self, DiscoveredSession};
use crate::error::{Error, Result};
use crate::frames::{self, write_json_frame};
use crate::grant::Grant;
use crate::paths::Paths;
use crate::prelude::{self, Prelude};
use crate::uhp::{self, MintedToken};

pub fn run(paths: &Paths, device_id: &str) -> Result<()> {
    let grant = crate::grant::load_grant(paths, device_id)?;
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut input = BufReader::new(stdin);
    let mut output = stdout;
    serve(&grant, paths, &mut input, &mut output)
}

pub fn serve(
    grant: &Grant,
    paths: &Paths,
    input: &mut (impl BufRead + Send),
    output: &mut impl Write,
) -> Result<()> {
    let prelude = match frames::read_text_frame(input, "prelude") {
        Ok(text) => match prelude::parse_prelude(&text) {
            Ok(prelude) => prelude,
            Err(error) => {
                write_prelude_error(output, &error)?;
                return Err(error);
            }
        },
        Err(error) => {
            write_prelude_error(output, &error)?;
            return Err(error);
        }
    };
    match prelude {
        Prelude::Discover => {
            let sessions = discovery::discover_running(paths)?;
            let metadata: Vec<Value> = sessions
                .iter()
                .map(|session| Value::Object(session.metadata()))
                .collect();
            write_json_frame(output, &prelude::discover_response(metadata))?;
            Ok(())
        }
        Prelude::Open { session } => open_session(grant, paths, &session, input, output),
    }
}

fn open_session(
    grant: &Grant,
    paths: &Paths,
    session: &str,
    input: &mut (impl BufRead + Send),
    output: &mut impl Write,
) -> Result<()> {
    let sessions = discovery::discover_running(paths)?;
    let selected = match discovery::select_session(&sessions, session) {
        Ok(selected) => selected.clone(),
        Err(error) => {
            write_prelude_error(output, &error)?;
            return Err(error);
        }
    };
    write_json_frame(output, &prelude::ready_frame(&selected.name))?;
    let request = match frames::read_text_frame(input, "request") {
        Ok(request) => request,
        Err(error) => return Err(error),
    };
    proxy_one_request(grant, &selected, request.as_bytes(), input, output)
}

fn proxy_one_request(
    grant: &Grant,
    session: &DiscoveredSession,
    request: &[u8],
    input: &mut (impl BufRead + Send),
    output: &mut impl Write,
) -> Result<()> {
    if uhp::request_has_auth(request)? {
        let id = request_id(request);
        write_json_frame(
            output,
            &json!({"id": id, "error": {"code": "forbidden", "message": "client-supplied auth is not allowed"}}),
        )?;
        return Err(Error::new(
            "auth_rejected",
            "client-supplied auth is not allowed",
        ));
    }

    let token = match uhp::mint_token(&session.address, session.evidence, grant.role.scopes()) {
        Ok(token) => token,
        Err(error) => {
            write_token_failure(output, request, &error)?;
            return Err(error);
        }
    };

    let outcome = proxy_with_token(session, request, &token, input, output);
    let _ = uhp::revoke_token(&session.address, session.evidence, &token.id);
    outcome
}

fn proxy_with_token(
    session: &DiscoveredSession,
    request: &[u8],
    token: &MintedToken,
    input: &mut (impl BufRead + Send),
    output: &mut impl Write,
) -> Result<()> {
    let injected = match uhp::inject_auth(request, &token.secret) {
        Ok(injected) => injected,
        Err(error) => {
            write_token_failure(output, request, &error)?;
            return Err(error);
        }
    };
    let stream = match uhp::open_request_stream(&session.address, session.evidence, &injected) {
        Ok(stream) => stream,
        Err(error) => {
            write_token_failure(output, request, &error)?;
            return Err(error);
        }
    };

    let mut local_read = BufReader::new(stream.try_clone()?);
    let mut local_write = stream.try_clone()?;
    let shutdown = stream;
    let streaming = serde_json::from_slice::<Value>(request)
        .ok()
        .and_then(|value| {
            value.get("method").and_then(Value::as_str).map(|method| {
                matches!(
                    method,
                    "events.subscribe"
                        | "terminal.backend.events.subscribe"
                        | "terminal.backend.observe"
                        | "terminal.backend.control"
                )
            })
        })
        .unwrap_or(false);

    if !streaming {
        match frames::read_frame(&mut local_read) {
            Ok(frame) => {
                output.write_all(&frame)?;
                output.flush()?;
            }
            Err(frames::FrameError::Eof) => {}
            Err(error) => return Err(error.into_error("response")),
        }
        let _ = shutdown.shutdown(Shutdown::Both);
        return Ok(());
    }

    thread::scope(|scope| {
        let remote_to_local = scope.spawn(|| {
            let result = uhp::copy_frames(input, &mut local_write);
            let _ = local_write.shutdown(Shutdown::Write);
            result
        });
        let local_to_remote = uhp::copy_frames(&mut local_read, output);
        let _ = shutdown.shutdown(Shutdown::Both);
        match remote_to_local.join() {
            Ok(Ok(())) => local_to_remote,
            Ok(Err(error)) => {
                let _ = local_to_remote;
                Err(error)
            }
            Err(_) => {
                let _ = local_to_remote;
                Err(Error::new("io", "bridge worker thread failed"))
            }
        }
    })
}

fn request_id(payload: &[u8]) -> String {
    serde_json::from_slice::<Value>(payload)
        .ok()
        .and_then(|value| value.get("id").and_then(Value::as_str).map(str::to_string))
        .filter(|id| {
            !id.is_empty()
                && id.len() <= 128
                && id.bytes().all(|byte| {
                    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b':' | b'-')
                })
        })
        .unwrap_or_else(|| "0".to_string())
}

fn write_prelude_error(output: &mut impl Write, error: &Error) -> Result<()> {
    write_json_frame(output, &prelude::prelude_error(error.code, &error.message))
}

fn write_token_failure(output: &mut impl Write, request: &[u8], error: &Error) -> Result<()> {
    write_json_frame(
        output,
        &json!({
            "id": request_id(request),
            "error": {
                "code": "forbidden",
                "message": error.message,
            }
        }),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grant::Grant;
    use crate::role::Role;
    use std::io::Cursor;

    fn grant() -> Grant {
        Grant {
            id: "ab".repeat(16),
            name: "phone".into(),
            role: Role::Observer,
            fingerprint: "SHA256:test".into(),
            key_type: "ssh-ed25519".into(),
            key: "AAAA".into(),
            comment: String::new(),
            created_at: 0,
        }
    }

    fn paths() -> Paths {
        Paths::from_parts(
            std::path::PathBuf::from("/tmp/luvia-host-test-config"),
            std::path::PathBuf::from("/tmp/luvia-host-test-keys"),
            std::path::PathBuf::from("/tmp/luvia-host-test-luvus"),
        )
    }

    #[test]
    fn prelude_rejection_writes_error_frame() {
        let grant = grant();
        let paths = paths();
        let mut input = Cursor::new(br#"{"version":1,"operation":"connect"}"#.to_vec());
        input.get_mut().push(b'\n');
        let mut output = Vec::new();
        let err = serve(&grant, &paths, &mut input, &mut output).unwrap_err();
        assert_eq!(err.code, "invalid_prelude");
        assert!(output.ends_with(b"\n"));
        let frame = std::str::from_utf8(&output[..output.len() - 1]).unwrap();
        let value: Value = serde_json::from_str(frame).unwrap();
        assert_eq!(value["version"], 1);
        assert_eq!(value["error"]["code"], "invalid_prelude");
    }

    #[test]
    fn missing_lf_prelude_is_rejected() {
        let grant = grant();
        let paths = paths();
        let mut input = Cursor::new(br#"{"version":1,"operation":"discover"}"#.to_vec());
        let mut output = Vec::new();
        let err = serve(&grant, &paths, &mut input, &mut output).unwrap_err();
        assert_eq!(err.code, "missing_lf");
    }

    #[test]
    fn extra_socket_field_is_rejected() {
        let grant = grant();
        let paths = paths();
        let mut input = Cursor::new(
            br#"{"version":1,"operation":"open","session":"default","socket":"/tmp/x.sock"}"#
                .to_vec(),
        );
        input.get_mut().push(b'\n');
        let mut output = Vec::new();
        let err = serve(&grant, &paths, &mut input, &mut output).unwrap_err();
        assert_eq!(err.code, "invalid_prelude");
    }

    #[test]
    fn oversized_prelude_is_rejected() {
        let grant = grant();
        let paths = paths();
        let mut data = vec![b'x'; crate::frames::MAX_FRAME_BYTES];
        data.push(b'\n');
        let mut input = Cursor::new(data);
        let mut output = Vec::new();
        let err = serve(&grant, &paths, &mut input, &mut output).unwrap_err();
        assert_eq!(err.code, "frame_too_large");
    }

    #[test]
    fn open_unknown_session_is_rejected() {
        let grant = grant();
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::from_parts(
            dir.path().join("host"),
            dir.path().join("authorized_keys"),
            dir.path().join("luvus"),
        );
        let mut input =
            Cursor::new(br#"{"version":1,"operation":"open","session":"default"}"#.to_vec());
        input.get_mut().push(b'\n');
        let mut output = Vec::new();
        let err = serve(&grant, &paths, &mut input, &mut output).unwrap_err();
        assert_eq!(err.code, "unknown_session");
        let frame = std::str::from_utf8(&output[..output.len() - 1]).unwrap();
        let value: Value = serde_json::from_str(frame).unwrap();
        assert_eq!(value["error"]["code"], "unknown_session");
    }
}
