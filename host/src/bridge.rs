use std::io::{self, BufRead, BufReader, IsTerminal, Read, Write};

use std::net::Shutdown;
use std::os::unix::io::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use serde_json::{json, Value};

use crate::capabilities::{self, Capabilities};
use crate::discovery::{self, DiscoveredSession};
use crate::error::{Error, Result};
use crate::frames::{self, write_json_frame};
use crate::grant::Grant;
use crate::paths::Paths;
use crate::prelude::{self, Prelude};
use crate::role::TokenKind;
use crate::uhp::{self, MintedToken};

pub const IDLE_TIMEOUT: Duration = Duration::from_secs(300);

/// Idle timeout for stdio. `SO_RCVTIMEO` only works on sockets; sshd feeds the
/// forced command a pipe, so `poll(2)` is used instead.
struct IdleTimeout<R> {
    inner: R,
    fd: RawFd,
    timeout: Duration,
}

impl<R> IdleTimeout<R> {
    fn new(inner: R, fd: RawFd, timeout: Duration) -> Self {
        Self { inner, fd, timeout }
    }
}

impl<R: Read> Read for IdleTimeout<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        loop {
            let mut pfd = libc::pollfd {
                fd: self.fd,
                events: libc::POLLIN,
                revents: 0,
            };
            let timeout_ms = i32::try_from(self.timeout.as_millis()).unwrap_or(i32::MAX);
            let rc = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
            if rc < 0 {
                let err = io::Error::last_os_error();
                if err.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(err);
            }
            if rc == 0 {
                return Err(io::Error::new(io::ErrorKind::TimedOut, "idle timeout"));
            }
            return self.inner.read(buf);
        }
    }
}

struct BridgeTokens {
    session: MintedToken,
    action: MintedToken,
}

#[derive(Debug)]
struct PreparedRequest {
    id: String,
    #[allow(dead_code)]
    method: String,
    injected: Vec<u8>,
    streaming: bool,
    bidirectional: bool,
    #[allow(dead_code)]
    token_kind: TokenKind,
}

fn reject_tty(stdin_is_tty: bool, stdout_is_tty: bool) -> Result<()> {
    if stdin_is_tty || stdout_is_tty {
        return Err(Error::new(
            "tty_refused",
            "luvia-host bridge speaks a machine protocol and cannot run on a terminal; the Luvia app connects with a non-interactive channel. If you are debugging, use: ssh -T <host>",
        ));
    }
    Ok(())
}

pub fn run(paths: &Paths, device_id: &str) -> Result<()> {
    let stdin = io::stdin();
    let stdout = io::stdout();
    reject_tty(stdin.is_terminal(), stdout.is_terminal())?;
    let grant = crate::grant::load_grant(paths, device_id)?;
    let stdin_fd = stdin.as_raw_fd();
    let mut input = BufReader::new(IdleTimeout::new(stdin, stdin_fd, IDLE_TIMEOUT));
    let mut output = stdout;
    serve(&grant, paths, &mut input, &mut output)
}

pub fn serve(
    grant: &Grant,
    paths: &Paths,
    input: &mut (impl BufRead + Send),
    output: &mut (impl Write + Send),
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
    output: &mut (impl Write + Send),
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

    let caps = match uhp::fetch_capabilities(&selected.address, selected.evidence) {
        Ok(caps) => caps,
        Err(error) => {
            write_prelude_error(output, &error)?;
            return Err(error);
        }
    };
    let _lease = match crate::channels::acquire(paths, &grant.id, caps.connection_capacity) {
        Ok(lease) => lease,
        Err(error) => {
            write_prelude_error(output, &error)?;
            return Err(error);
        }
    };

    let session_token = match uhp::mint_token(
        &selected.address,
        selected.evidence,
        crate::role::Role::session_scopes(),
    ) {
        Ok(token) => token,
        Err(error) => {
            write_prelude_error(output, &error)?;
            return Err(error);
        }
    };
    let action_token = match uhp::mint_token(
        &selected.address,
        selected.evidence,
        grant.role.action_scopes(),
    ) {
        Ok(token) => token,
        Err(error) => {
            let _ = uhp::revoke_token(&selected.address, selected.evidence, &session_token.id);
            write_prelude_error(output, &error)?;
            return Err(error);
        }
    };
    let tokens = BridgeTokens {
        session: session_token,
        action: action_token,
    };
    let outcome = proxy_channel(grant, paths, &selected, &caps, &tokens, input, output);
    let _ = uhp::revoke_token(&selected.address, selected.evidence, &tokens.session.id);
    let _ = uhp::revoke_token(&selected.address, selected.evidence, &tokens.action.id);
    outcome
}

fn proxy_channel(
    grant: &Grant,
    paths: &Paths,
    session: &DiscoveredSession,
    caps: &Capabilities,
    tokens: &BridgeTokens,
    input: &mut (impl BufRead + Send),
    output: &mut (impl Write + Send),
) -> Result<()> {
    loop {
        let request = match frames::read_frame(input) {
            Ok(frame) => frame[..frame.len() - 1].to_vec(),
            Err(frames::FrameError::Eof) => return Ok(()),
            Err(frames::FrameError::Timeout) => {
                let error = Error::new("idle_timeout", "bridge channel idle timeout");
                write_request_error(output, "0", &error)?;
                return Err(error);
            }
            Err(error) => {
                let error = error.into_error("request");
                write_request_error(output, "0", &error)?;
                return Err(error);
            }
        };
        match prepare_request(grant, caps, tokens, &request) {
            Ok(prepared) => {
                if prepared.streaming {
                    return proxy_stream(grant, paths, session, caps, &prepared, input, output);
                }
                proxy_unary(session, &prepared, output)?;
            }
            Err(error) => {
                let id = request_id(&request);
                let method = uhp::request_method(&request).unwrap_or_else(|_| "unknown".into());
                let _ = crate::audit::denied(paths, &grant.id, grant.role, &method, error.code);
                write_request_error(output, &id, &error)?;
            }
        }
    }
}

fn prepare_request(
    grant: &Grant,
    caps: &Capabilities,
    tokens: &BridgeTokens,
    payload: &[u8],
) -> Result<PreparedRequest> {
    if uhp::request_has_auth(payload)? {
        return Err(Error::new(
            "auth_rejected",
            "client-supplied auth is not allowed",
        ));
    }
    let method = uhp::request_method(payload)?;
    let token_kind = caps.authorize(grant.role, &method)?;
    let secret = match token_kind {
        TokenKind::Session => &tokens.session.secret,
        TokenKind::Action => &tokens.action.secret,
    };
    let injected = uhp::inject_auth(payload, secret)?;
    Ok(PreparedRequest {
        id: request_id(payload),
        method: method.clone(),
        injected,
        streaming: capabilities::is_streaming(&method),
        bidirectional: capabilities::is_bidirectional(&method),
        token_kind,
    })
}

fn proxy_unary(
    session: &DiscoveredSession,
    prepared: &PreparedRequest,
    output: &mut impl Write,
) -> Result<()> {
    let stream =
        match uhp::open_request_stream(&session.address, session.evidence, &prepared.injected) {
            Ok(stream) => stream,
            Err(error) => {
                write_request_error(output, &prepared.id, &error)?;
                return Err(error);
            }
        };
    let _ = stream.set_read_timeout(Some(IDLE_TIMEOUT));
    let mut local_read = BufReader::new(stream.try_clone()?);
    match frames::read_frame(&mut local_read) {
        Ok(frame) => {
            output.write_all(&frame)?;
            output.flush()?;
        }
        Err(frames::FrameError::Eof) => {}
        Err(frames::FrameError::Timeout) => {
            return Err(Error::new("idle_timeout", "Luvus response idle timeout"));
        }
        Err(error) => return Err(error.into_error("response")),
    }
    let _ = stream.shutdown(Shutdown::Both);
    Ok(())
}

fn proxy_stream(
    grant: &Grant,
    paths: &Paths,
    session: &DiscoveredSession,
    caps: &Capabilities,
    prepared: &PreparedRequest,
    input: &mut (impl BufRead + Send),
    output: &mut (impl Write + Send),
) -> Result<()> {
    let stream =
        match uhp::open_request_stream(&session.address, session.evidence, &prepared.injected) {
            Ok(stream) => stream,
            Err(error) => {
                write_request_error(output, &prepared.id, &error)?;
                return Err(error);
            }
        };
    let _ = stream.set_read_timeout(Some(IDLE_TIMEOUT));
    let mut local_read = BufReader::new(stream.try_clone()?);
    let mut local_write = stream.try_clone()?;
    let shutdown = stream;
    let bidirectional = prepared.bidirectional;
    let output = Mutex::new(output);

    thread::scope(|scope| {
        let remote_to_local = scope.spawn(|| {
            let result = copy_device_follow_ups(
                grant,
                paths,
                caps,
                bidirectional,
                input,
                &mut local_write,
                &output,
            );
            let _ = local_write.shutdown(Shutdown::Write);
            result
        });
        let local_to_remote = copy_locked_frames(&mut local_read, &output);
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

fn copy_locked_frames(
    reader: &mut impl BufRead,
    output: &Mutex<&mut (impl Write + Send)>,
) -> Result<()> {
    loop {
        match frames::read_frame(reader) {
            Ok(frame) => {
                let mut output = output
                    .lock()
                    .map_err(|_| Error::new("io", "bridge output lock failed"))?;
                output.write_all(&frame)?;
                output.flush()?;
            }
            Err(frames::FrameError::Eof) => return Ok(()),
            Err(frames::FrameError::Timeout) => {
                return Err(Error::new("idle_timeout", "bridge channel idle timeout"));
            }
            Err(error) => return Err(error.into_error("stream")),
        }
    }
}

fn copy_device_follow_ups(
    grant: &Grant,
    paths: &Paths,
    caps: &Capabilities,
    bidirectional: bool,
    input: &mut impl BufRead,
    local_write: &mut UnixStream,
    output: &Mutex<&mut (impl Write + Send)>,
) -> Result<()> {
    loop {
        match frames::read_frame(input) {
            Ok(frame) => {
                let payload = &frame[..frame.len() - 1];
                if bidirectional && is_control_action(payload) {
                    local_write.write_all(&frame)?;
                    local_write.flush()?;
                    continue;
                }
                let id = request_id(payload);
                let method = uhp::request_method(payload).unwrap_or_else(|_| "unknown".into());
                let error = stream_follow_up_error(grant, caps, payload, &method);
                let _ = crate::audit::denied(paths, &grant.id, grant.role, &method, error.code);
                let mut output = output
                    .lock()
                    .map_err(|_| Error::new("io", "bridge output lock failed"))?;
                write_request_error(&mut **output, &id, &error)?;
            }
            Err(frames::FrameError::Eof) => return Ok(()),
            Err(frames::FrameError::Timeout) => {
                return Err(Error::new("idle_timeout", "bridge channel idle timeout"));
            }
            Err(error) => return Err(error.into_error("stream")),
        }
    }
}

fn stream_follow_up_error(
    grant: &Grant,
    caps: &Capabilities,
    payload: &[u8],
    method: &str,
) -> Error {
    if uhp::request_has_auth(payload).unwrap_or(true) {
        return Error::new("auth_rejected", "client-supplied auth is not allowed");
    }
    if caps.lookup(method).is_none() && uhp::request_method(payload).is_err() {
        return Error::new("invalid_request", "UHP request must include a method");
    }
    let _ = caps.authorize(grant.role, method);
    Error::new(
        "forbidden",
        format!("method {method} is not permitted on an active stream"),
    )
}

fn is_control_action(payload: &[u8]) -> bool {
    let Ok(value) = crate::unique_json::parse_unique_value(payload) else {
        return false;
    };
    let Some(object) = value.as_object() else {
        return false;
    };
    object.contains_key("action") && !object.contains_key("method") && !object.contains_key("auth")
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

fn write_request_error(output: &mut impl Write, id: &str, error: &Error) -> Result<()> {
    write_json_frame(
        output,
        &json!({
            "id": id,
            "error": {
                "code": error.code,
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
    use std::os::unix::io::FromRawFd;

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

    fn tokens() -> BridgeTokens {
        BridgeTokens {
            session: MintedToken {
                id: "session-id".into(),
                secret: "luv_tok_session".into(),
            },
            action: MintedToken {
                id: "action-id".into(),
                secret: "luv_tok_action".into(),
            },
        }
    }

    fn frame(id: &str, method: &str) -> Vec<u8> {
        serde_json::to_vec(&json!({"id": id, "method": method, "params": {}})).unwrap()
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

    #[test]
    fn client_supplied_auth_is_refused() {
        let caps = capabilities::fixture();
        let tokens = tokens();
        let grant = grant();
        let request = br#"{"id":"1","method":"ping","params":{},"auth":"secret"}"#;
        let err = prepare_request(&grant, &caps, &tokens, request).unwrap_err();
        assert_eq!(err.code, "auth_rejected");
    }

    #[test]
    fn unknown_method_is_denied() {
        let caps = capabilities::fixture();
        let err =
            prepare_request(&grant(), &caps, &tokens(), &frame("1", "not.a.method")).unwrap_err();
        assert_eq!(err.code, "forbidden");
        assert!(err.message.contains("unknown"));
    }

    #[test]
    fn observer_second_frame_out_of_scope_is_denied() {
        let caps = capabilities::fixture();
        let tokens = tokens();
        let grant = grant();
        let first = prepare_request(&grant, &caps, &tokens, &frame("1", "ping")).unwrap();
        assert_eq!(first.token_kind, TokenKind::Action);
        let injected: Value = serde_json::from_slice(&first.injected).unwrap();
        assert_eq!(injected["auth"], "luv_tok_action");
        let err = prepare_request(&grant, &caps, &tokens, &frame("2", "agent.start")).unwrap_err();
        assert_eq!(err.code, "forbidden");
        let err = prepare_request(
            &grant,
            &caps,
            &tokens,
            &frame("3", "terminal.backend.control"),
        )
        .unwrap_err();
        assert_eq!(err.code, "forbidden");
    }

    #[test]
    fn stream_follow_up_never_routes_admin_to_session_token() {
        let caps = capabilities::fixture();
        let grant = grant();
        for method in ["session.snapshot", "server.stop", "config.patch"] {
            let payload = frame("2", method);
            let error = stream_follow_up_error(&grant, &caps, &payload, method);
            assert_eq!(error.code, "forbidden", "{method}");
            assert!(!error.message.contains("luv_tok"));
        }
        let snapshot =
            prepare_request(&grant, &caps, &tokens(), &frame("1", "session.snapshot")).unwrap();
        assert_eq!(snapshot.token_kind, TokenKind::Session);
        let injected: Value = serde_json::from_slice(&snapshot.injected).unwrap();
        assert_eq!(injected["auth"], "luv_tok_session");
        let follow = stream_follow_up_error(
            &grant,
            &caps,
            &frame("2", "session.snapshot"),
            "session.snapshot",
        );
        assert_eq!(follow.code, "forbidden");
        assert!(!follow.message.contains("luv_tok_session"));
    }

    #[test]
    fn ping_injects_action_token_not_session_token() {
        let prepared = prepare_request(
            &grant(),
            &capabilities::fixture(),
            &tokens(),
            &frame("1", "uhp.capabilities"),
        )
        .unwrap();
        assert_eq!(prepared.token_kind, TokenKind::Action);
        let injected: Value = serde_json::from_slice(&prepared.injected).unwrap();
        assert_eq!(injected["auth"], "luv_tok_action");
        assert_ne!(injected["auth"], "luv_tok_session");
    }

    #[test]
    fn idle_timeout_on_a_pipe_is_timed_out_not_enotsock() {
        let mut fds = [0i32; 2];
        assert_eq!(unsafe { libc::pipe(fds.as_mut_ptr()) }, 0);
        let (r, w) = (fds[0], fds[1]);
        let file = unsafe { std::fs::File::from_raw_fd(r) };
        let mut reader = IdleTimeout::new(file, r, Duration::from_millis(40));
        let mut buf = [0u8; 8];
        let err = reader.read(&mut buf).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert_ne!(err.raw_os_error(), Some(libc::ENOTSOCK));
        unsafe {
            libc::close(w);
        }
    }

    #[test]
    fn bridge_refuses_a_tty() {
        let err = reject_tty(true, false).unwrap_err();
        assert_eq!(err.code, "tty_refused");
        assert!(err.message.contains("ssh -T"));
        let err = reject_tty(false, true).unwrap_err();
        assert_eq!(err.code, "tty_refused");
        reject_tty(false, false).unwrap();
    }
}
