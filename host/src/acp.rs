use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use serde_json::{json, Map, Value};

use crate::error::{Error, Result};
use crate::frames::{self, write_json_frame};
use crate::grant::Grant;
use crate::paths::{self, Paths};
use crate::role::Role;
use crate::unique_json::parse_unique_value;

const AGENTS_METHOD: &str = "luvia.acp.agents";
const SESSION_OPEN_METHOD: &str = "luvia.acp.session.open";
const MAX_PROMPT_BYTES: usize = 262144;
const PERMISSION_WAIT: Duration = Duration::from_secs(5);
const AGENT_EXIT_GRACE: Duration = Duration::from_secs(5);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AgentSpec {
    pub id: String,
    pub name: String,
    pub command: String,
    pub args: Vec<String>,
}

pub fn catalog(paths: &Paths) -> Vec<(AgentSpec, bool)> {
    let mut specs = builtins();
    if let Ok(entries) = load_overrides(paths) {
        for entry in entries {
            if let Some(existing) = specs.iter().position(|spec| spec.id == entry.id) {
                specs[existing] = entry;
            } else {
                specs.push(entry);
            }
        }
    }
    specs
        .into_iter()
        .map(|spec| {
            let available = command_is_available(&spec.command);
            (spec, available)
        })
        .collect()
}

pub fn any_available(paths: &Paths) -> bool {
    catalog(paths).iter().any(|(_, available)| *available)
}

pub fn augment_capabilities(result_frame: &[u8], paths: &Paths) -> Result<Vec<u8>> {
    if !any_available(paths) {
        return Ok(result_frame.to_vec());
    }
    let payload = strip_lf(result_frame);
    let mut value = parse_unique_value(payload)?;
    let Some(result) = value.get_mut("result").and_then(Value::as_object_mut) else {
        return Ok(result_frame.to_vec());
    };
    match result.get_mut("methods") {
        Some(Value::Array(methods)) => {
            append_method_name(methods, AGENTS_METHOD);
            append_method_name(methods, SESSION_OPEN_METHOD);
        }
        Some(_) => {}
        None => {
            result.insert(
                "methods".into(),
                json!([AGENTS_METHOD, SESSION_OPEN_METHOD]),
            );
        }
    }
    if let Some(Value::Array(contracts)) = result.get_mut("method_contracts") {
        append_contract(contracts, AGENTS_METHOD, "read", "read", true);
        append_contract(contracts, SESSION_OPEN_METHOD, "write", "agent", false);
    }
    let mut out = serde_json::to_vec(&value)?;
    out.push(b'\n');
    Ok(out)
}

pub fn handle_agents(id: &str, paths: &Paths, output: &mut impl Write) -> Result<()> {
    let agents: Vec<Value> = catalog(paths)
        .into_iter()
        .map(|(spec, available)| {
            json!({
                "id": spec.id,
                "name": spec.name,
                "command": spec.command,
                "available": available,
            })
        })
        .collect();
    write_json_frame(
        output,
        &json!({
            "id": id,
            "result": {
                "type": "acp_agents",
                "agents": agents,
            }
        }),
    )
}

pub fn serve_session(
    grant: &Grant,
    paths: &Paths,
    id: &str,
    params: &Value,
    input: &mut impl BufRead,
    output: &mut (impl Write + Send),
) -> Result<()> {
    if grant.role == Role::Observer {
        write_error(
            output,
            id,
            &Error::new(
                "forbidden",
                "method luvia.acp.session.open is not permitted for this device role",
            ),
        )?;
        return Ok(());
    }
    let OpenParams { agent, cwd } = match parse_open_params(params) {
        Ok(parsed) => parsed,
        Err(error) => {
            write_error(output, id, &error)?;
            return Ok(());
        }
    };
    let (spec, available) = match catalog(paths)
        .into_iter()
        .find(|(spec, _)| spec.id == agent)
    {
        Some(found) => found,
        None => {
            write_error(
                output,
                id,
                &Error::new("unknown_agent", format!("unknown ACP agent {agent}")),
            )?;
            return Ok(());
        }
    };
    if !available {
        write_error(
            output,
            id,
            &Error::new(
                "agent_unavailable",
                format!("ACP agent {} is not available", spec.id),
            ),
        )?;
        return Ok(());
    }
    let mut child = match Command::new(&spec.command)
        .args(&spec.args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .current_dir(&cwd)
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            write_error(
                output,
                id,
                &Error::new(
                    "spawn_failed",
                    format!("failed to spawn ACP agent {}: {error}", spec.id),
                ),
            )?;
            return Ok(());
        }
    };
    let mut agent_in = match child.stdin.take() {
        Some(stdin) => stdin,
        None => {
            let _ = child.kill();
            let _ = child.wait();
            write_error(
                output,
                id,
                &Error::new("spawn_failed", "ACP agent stdin was not piped"),
            )?;
            return Ok(());
        }
    };
    let mut agent_out = match child.stdout.take() {
        Some(stdout) => BufReader::new(stdout),
        None => {
            let _ = child.kill();
            let _ = child.wait();
            write_error(
                output,
                id,
                &Error::new("spawn_failed", "ACP agent stdout was not piped"),
            )?;
            return Ok(());
        }
    };
    let stderr_tail = child.stderr.take().map(spawn_stderr_tail);
    let mut child = ChildGuard { child: Some(child) };
    let handshake = handshake(&mut agent_in, &mut agent_out, &cwd);
    let Handshake {
        session_id,
        protocol_version,
        next_rpc_id,
    } = match handshake {
        Ok(ready) => ready,
        Err(error) => {
            child.kill_wait();
            std::thread::sleep(Duration::from_millis(50));
            // Agents that die before answering `initialize` usually explain why on
            // stderr (missing login, broken config). Surface that instead of a bare
            // "agent closed stdout" so the phone can show something actionable.
            let mut message = error.message;
            if let Some(tail) = stderr_tail.and_then(|tail| tail.lock().ok().map(|t| t.join("\n")))
            {
                if !tail.trim().is_empty() {
                    message = format!("{message}: {}", tail.trim());
                }
            }
            let error = Error::new("acp_error", message);
            write_error(output, id, &error)?;
            return Ok(());
        }
    };
    write_json_frame(
        output,
        &json!({
            "id": id,
            "result": {
                "type": "acp_session",
                "session_id": session_id,
                "agent": spec.id,
                "agent_name": spec.name,
                "protocol_version": protocol_version,
                "cwd": cwd,
            }
        }),
    )?;
    run_session(
        &session_id,
        next_rpc_id,
        input,
        output,
        agent_in,
        agent_out,
        &mut child,
    )
}

fn builtins() -> Vec<AgentSpec> {
    vec![
        AgentSpec {
            id: "codex".into(),
            name: "Codex".into(),
            command: "codex-acp".into(),
            args: Vec::new(),
        },
        AgentSpec {
            id: "claude".into(),
            name: "Claude Code".into(),
            command: "claude-code-acp".into(),
            args: Vec::new(),
        },
        AgentSpec {
            id: "gemini".into(),
            name: "Gemini CLI".into(),
            command: "gemini".into(),
            args: vec!["--experimental-acp".into()],
        },
    ]
}

fn load_overrides(paths: &Paths) -> Result<Vec<AgentSpec>> {
    let path = paths.config_dir.join("acp-agents.json");
    let text = match paths::read_nofollow_to_string(&path) {
        Ok(text) => text,
        Err(_) => return Ok(Vec::new()),
    };
    if text.trim().is_empty() {
        return Ok(Vec::new());
    }
    let value = parse_unique_value(text.as_bytes())?;
    let array = value
        .as_array()
        .ok_or_else(|| Error::new("invalid_json", "acp-agents.json must be a JSON array"))?;
    let mut out = Vec::new();
    for item in array {
        let Some(object) = item.as_object() else {
            continue;
        };
        let Some(id) = object
            .get("id")
            .and_then(Value::as_str)
            .filter(|id| !id.is_empty())
        else {
            continue;
        };
        let Some(name) = object.get("name").and_then(Value::as_str) else {
            continue;
        };
        let Some(command) = object
            .get("command")
            .and_then(Value::as_str)
            .filter(|command| !command.is_empty())
        else {
            continue;
        };
        let args = match object.get("args") {
            None => Vec::new(),
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect(),
            Some(_) => continue,
        };
        out.push(AgentSpec {
            id: id.to_string(),
            name: name.to_string(),
            command: command.to_string(),
            args,
        });
    }
    Ok(out)
}

fn command_is_available(command: &str) -> bool {
    let path = Path::new(command);
    if path.is_absolute() {
        return is_executable_file(path);
    }
    let Some(path_os) = std::env::var_os("PATH") else {
        return false;
    };
    std::env::split_paths(&path_os).any(|dir| is_executable_file(&dir.join(command)))
}

fn is_executable_file(path: &Path) -> bool {
    let Ok(metadata) = std::fs::metadata(path) else {
        return false;
    };
    metadata.is_file() && metadata.permissions().mode() & 0o111 != 0
}

fn append_method_name(methods: &mut Vec<Value>, method: &str) {
    if !methods.iter().any(|value| value.as_str() == Some(method)) {
        methods.push(Value::String(method.to_string()));
    }
}

fn append_contract(
    contracts: &mut Vec<Value>,
    method: &str,
    access: &str,
    scope: &str,
    idempotent: bool,
) {
    if contracts
        .iter()
        .any(|entry| entry.get("method").and_then(Value::as_str) == Some(method))
    {
        return;
    }
    contracts.push(json!({
        "method": method,
        "access": access,
        "scope": scope,
        "idempotent": idempotent,
    }));
}

fn strip_lf(frame: &[u8]) -> &[u8] {
    frame.strip_suffix(b"\n").unwrap_or(frame)
}

struct OpenParams {
    agent: String,
    cwd: String,
}

fn parse_open_params(params: &Value) -> Result<OpenParams> {
    let object = params.as_object().ok_or_else(|| {
        Error::new(
            "invalid_params",
            "luvia.acp.session.open params must be an object",
        )
    })?;
    for key in object.keys() {
        if !matches!(key.as_str(), "agent" | "cwd") {
            return Err(Error::new(
                "invalid_params",
                format!("luvia.acp.session.open contains unknown field {key}"),
            ));
        }
    }
    let agent = object
        .get("agent")
        .and_then(Value::as_str)
        .filter(|agent| !agent.is_empty())
        .ok_or_else(|| Error::new("invalid_params", "agent must be a non-empty string"))?
        .to_string();
    let cwd = object
        .get("cwd")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_params", "cwd must be a string"))?;
    let path = Path::new(cwd);
    if !path.is_absolute() || !path.is_dir() {
        return Err(Error::new(
            "invalid_params",
            "cwd must be an absolute existing directory",
        ));
    }
    Ok(OpenParams {
        agent,
        cwd: cwd.to_string(),
    })
}

struct Handshake {
    session_id: String,
    protocol_version: u64,
    next_rpc_id: u64,
}

fn handshake(
    agent_in: &mut ChildStdin,
    agent_out: &mut impl BufRead,
    cwd: &str,
) -> Result<Handshake> {
    write_json_frame(
        agent_in,
        &json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": 1,
                "clientCapabilities": {
                    "fs": {
                        "readTextFile": false,
                        "writeTextFile": false
                    },
                    "terminal": false
                }
            }
        }),
    )?;
    let initialize = rpc_wait(agent_in, agent_out, &json!(1))?;
    let protocol_version = initialize
        .get("protocolVersion")
        .and_then(Value::as_u64)
        .unwrap_or(1);
    write_json_frame(
        agent_in,
        &json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "session/new",
            "params": {
                "cwd": cwd,
                "mcpServers": []
            }
        }),
    )?;
    let created = rpc_wait(agent_in, agent_out, &json!(2))?;
    let session_id = created
        .get("sessionId")
        .and_then(Value::as_str)
        .filter(|id| !id.is_empty())
        .ok_or_else(|| Error::new("acp_error", "session/new did not return sessionId"))?
        .to_string();
    Ok(Handshake {
        session_id,
        protocol_version,
        next_rpc_id: 3,
    })
}

fn rpc_wait(
    agent_in: &mut ChildStdin,
    agent_out: &mut impl BufRead,
    expected_id: &Value,
) -> Result<Value> {
    loop {
        let value = read_agent_value(agent_out)?;
        if value.get("method").is_some() && value.get("id").is_some() {
            let method = value.get("method").and_then(Value::as_str).unwrap_or("");
            if method == "session/request_permission" {
                return Err(Error::new(
                    "acp_error",
                    "agent requested permission before session/new completed",
                ));
            }
            reject_rpc_method(agent_in, value.get("id").cloned().unwrap_or(Value::Null))?;
            continue;
        }
        if value.get("method").is_some() {
            continue;
        }
        if value.get("id") != Some(expected_id) {
            continue;
        }
        if let Some(error) = value.get("error") {
            return Err(Error::new("acp_error", rpc_error_message(error)));
        }
        return Ok(value.get("result").cloned().unwrap_or(Value::Null));
    }
}

fn read_agent_value(agent_out: &mut impl BufRead) -> Result<Value> {
    let frame = frames::read_frame(agent_out)
        .map_err(|error| Error::new("acp_error", error.into_error("agent").message))?;
    let payload = strip_lf(&frame);
    parse_unique_value(payload).map_err(|error| Error::new("acp_error", error.message))
}

fn rpc_error_message(error: &Value) -> String {
    error
        .get("message")
        .and_then(Value::as_str)
        .filter(|message| !message.is_empty())
        .unwrap_or("agent error")
        .to_string()
}

fn reject_rpc_method(agent_in: &mut impl Write, id: Value) -> Result<()> {
    write_json_frame(
        agent_in,
        &json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": {
                "code": -32601,
                "message": "method not supported"
            }
        }),
    )
}

/// Drains an agent's stderr on a background thread, keeping the last few lines.
/// The reader stops on EOF; nothing blocks session shutdown on it.
fn spawn_stderr_tail(stderr: std::process::ChildStderr) -> Arc<Mutex<Vec<String>>> {
    const KEEP: usize = 8;
    let tail = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&tail);
    std::thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines().map_while(std::result::Result::ok) {
            if let Ok(mut lines) = sink.lock() {
                if lines.len() == KEEP {
                    lines.remove(0);
                }
                lines.push(line);
            }
        }
    });
    tail
}

struct ChildGuard {
    child: Option<Child>,
}

impl ChildGuard {
    fn wait(&mut self) -> Option<i32> {
        let mut child = self.child.take()?;
        child.wait().ok().and_then(|status| status.code())
    }

    fn kill_wait(&mut self) -> Option<i32> {
        let mut child = self.child.take()?;
        let _ = child.kill();
        child.wait().ok().and_then(|status| status.code())
    }

    fn kill(&mut self) {
        if let Some(child) = &mut self.child {
            let _ = child.kill();
        }
    }
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        let _ = self.kill_wait();
    }
}

struct SessionState {
    pending: HashMap<String, Value>,
    active_prompt: Option<Value>,
    finished: bool,
}

fn run_session(
    session_id: &str,
    next_rpc_id: u64,
    input: &mut impl BufRead,
    output: &mut (impl Write + Send),
    agent_in: ChildStdin,
    mut agent_out: impl BufRead + Send,
    child: &mut ChildGuard,
) -> Result<()> {
    let output = Mutex::new(output);
    let agent_in = Mutex::new(Some(agent_in));
    let state = Mutex::new(SessionState {
        pending: HashMap::new(),
        active_prompt: None,
        finished: false,
    });
    let cv = Condvar::new();
    let next_rpc_id = AtomicU64::new(next_rpc_id);
    let child = Mutex::new(child);

    std::thread::scope(|scope| {
        let agent_thread = scope.spawn(|| {
            let result = agent_to_phone(
                session_id,
                &mut agent_out,
                &agent_in,
                &output,
                &state,
                &cv,
                &child,
            );
            mark_finished(&state, &cv);
            result
        });
        let phone_result = phone_to_agent(
            session_id,
            input,
            &agent_in,
            &output,
            &state,
            &cv,
            &next_rpc_id,
            &child,
        );
        let agent_result = match agent_thread.join() {
            Ok(result) => result,
            Err(_) => Err(Error::new("io", "bridge worker thread failed")),
        };
        match (phone_result, agent_result) {
            (Ok(()), Ok(())) => Ok(()),
            (Err(error), _) => Err(error),
            (Ok(()), Err(error)) => Err(error),
        }
    })
}

fn mark_finished(state: &Mutex<SessionState>, cv: &Condvar) {
    if let Ok(mut state) = state.lock() {
        state.finished = true;
    }
    cv.notify_all();
}

fn agent_to_phone(
    session_id: &str,
    agent_out: &mut impl BufRead,
    agent_in: &Mutex<Option<ChildStdin>>,
    output: &Mutex<&mut (impl Write + Send)>,
    state: &Mutex<SessionState>,
    cv: &Condvar,
    child: &Mutex<&mut ChildGuard>,
) -> Result<()> {
    let mut sequence = 0u64;
    loop {
        match frames::read_frame(agent_out) {
            Ok(frame) => {
                let payload = strip_lf(&frame);
                let Ok(value) = parse_unique_value(payload) else {
                    continue;
                };
                handle_agent_message(
                    session_id,
                    value,
                    agent_in,
                    output,
                    state,
                    cv,
                    &mut sequence,
                )?;
            }
            Err(frames::FrameError::Eof) | Err(frames::FrameError::MissingLf) => {
                let code = child.lock().ok().and_then(|mut child| child.wait());
                sequence += 1;
                emit(
                    output,
                    sequence,
                    "acp.exit",
                    json!({
                        "code": code,
                        "message": exit_message(code),
                    }),
                )?;
                return Ok(());
            }
            Err(frames::FrameError::Timeout) => {
                return Err(Error::new("idle_timeout", "ACP agent idle timeout"));
            }
            Err(error) => return Err(error.into_error("agent")),
        }
    }
}

fn exit_message(code: Option<i32>) -> &'static str {
    if code.is_some() {
        "agent exited"
    } else {
        "agent terminated"
    }
}

fn handle_agent_message(
    session_id: &str,
    value: Value,
    agent_in: &Mutex<Option<ChildStdin>>,
    output: &Mutex<&mut (impl Write + Send)>,
    state: &Mutex<SessionState>,
    cv: &Condvar,
    sequence: &mut u64,
) -> Result<()> {
    let has_method = value.get("method").is_some();
    let has_id = value.get("id").is_some();
    if has_method && has_id {
        let method = value.get("method").and_then(Value::as_str).unwrap_or("");
        let rpc_id = value.get("id").cloned().unwrap_or(Value::Null);
        if method == "session/request_permission" {
            let params = value.get("params").cloned().unwrap_or(Value::Null);
            let request_id = json_rpc_id_key(&rpc_id);
            {
                let mut state = lock_state(state)?;
                state.pending.insert(request_id.clone(), rpc_id);
            }
            cv.notify_all();
            *sequence += 1;
            emit(
                output,
                *sequence,
                "acp.permission",
                normalize_permission(session_id, &request_id, &params),
            )?;
            return Ok(());
        }
        write_agent(
            agent_in,
            &json!({
                "jsonrpc": "2.0",
                "id": rpc_id,
                "error": {
                    "code": -32601,
                    "message": "method not supported"
                }
            }),
        )?;
        return Ok(());
    }
    if has_method {
        let method = value.get("method").and_then(Value::as_str).unwrap_or("");
        if method == "session/update" {
            if let Some(update) = value.get("params").and_then(|params| params.get("update")) {
                *sequence += 1;
                emit(
                    output,
                    *sequence,
                    "acp.update",
                    json!({
                        "session_id": session_id,
                        "update": update,
                    }),
                )?;
            }
        }
        return Ok(());
    }
    if has_id && (value.get("result").is_some() || value.get("error").is_some()) {
        let rpc_id = value.get("id").cloned().unwrap_or(Value::Null);
        let is_active = {
            let mut state = lock_state(state)?;
            if state
                .active_prompt
                .as_ref()
                .is_some_and(|active| json_rpc_id_key(active) == json_rpc_id_key(&rpc_id))
            {
                state.active_prompt = None;
                true
            } else {
                false
            }
        };
        if is_active {
            cv.notify_all();
            let stop_reason = if value.get("error").is_some() {
                "error".to_string()
            } else {
                value
                    .get("result")
                    .and_then(|result| result.get("stopReason"))
                    .and_then(Value::as_str)
                    .unwrap_or("unknown")
                    .to_string()
            };
            *sequence += 1;
            emit(
                output,
                *sequence,
                "acp.turn",
                json!({ "stop_reason": stop_reason }),
            )?;
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn phone_to_agent(
    session_id: &str,
    input: &mut impl BufRead,
    agent_in: &Mutex<Option<ChildStdin>>,
    output: &Mutex<&mut (impl Write + Send)>,
    state: &Mutex<SessionState>,
    cv: &Condvar,
    next_rpc_id: &AtomicU64,
    child: &Mutex<&mut ChildGuard>,
) -> Result<()> {
    loop {
        match frames::read_frame(input) {
            Ok(frame) => {
                let payload = strip_lf(&frame);
                match parse_action(payload) {
                    Ok(action) => {
                        if let Err(error) = apply_action(
                            session_id,
                            action,
                            agent_in,
                            output,
                            state,
                            cv,
                            next_rpc_id,
                        ) {
                            let id = frame_id(payload);
                            write_locked_error(output, &id, &error)?;
                        }
                    }
                    Err(error) => {
                        let id = frame_id(payload);
                        write_locked_error(output, &id, &error)?;
                    }
                }
            }
            Err(frames::FrameError::Eof) => {
                close_agent_stdin(agent_in);
                wait_for_agent_exit(state, cv, child);
                return Ok(());
            }
            Err(frames::FrameError::Timeout) => {
                write_locked_error(
                    output,
                    "0",
                    &Error::new("idle_timeout", "bridge channel idle timeout"),
                )?;
                if let Ok(mut child) = child.lock() {
                    child.kill();
                }
                return Err(Error::new("idle_timeout", "bridge channel idle timeout"));
            }
            Err(error) => {
                let error = error.into_error("request");
                write_locked_error(output, "0", &error)?;
                if let Ok(mut child) = child.lock() {
                    child.kill();
                }
                return Err(error);
            }
        }
    }
}

fn apply_action(
    session_id: &str,
    action: DeviceAction,
    agent_in: &Mutex<Option<ChildStdin>>,
    output: &Mutex<&mut (impl Write + Send)>,
    state: &Mutex<SessionState>,
    cv: &Condvar,
    next_rpc_id: &AtomicU64,
) -> Result<()> {
    match action {
        DeviceAction::Prompt { id, text } => {
            {
                let state = lock_state(state)?;
                if state.active_prompt.is_some() {
                    return Err(Error::new("acp_busy", "an ACP turn is already active"));
                }
            }
            let rpc_id = next_rpc_id.fetch_add(1, Ordering::Relaxed);
            write_agent(
                agent_in,
                &json!({
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "method": "session/prompt",
                    "params": {
                        "sessionId": session_id,
                        "prompt": [{ "type": "text", "text": text }]
                    }
                }),
            )?;
            {
                let mut state = lock_state(state)?;
                state.active_prompt = Some(json!(rpc_id));
            }
            write_ok(output, &id)
        }
        DeviceAction::Permission {
            id,
            request_id,
            selected,
        } => {
            let rpc_id = take_pending(state, cv, &request_id)?;
            let result = match selected {
                Some(option_id) => json!({
                    "outcome": {
                        "outcome": "selected",
                        "optionId": option_id
                    }
                }),
                None => json!({
                    "outcome": {
                        "outcome": "cancelled"
                    }
                }),
            };
            write_agent(
                agent_in,
                &json!({
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "result": result
                }),
            )?;
            write_ok(output, &id)
        }
        DeviceAction::Cancel { id } => {
            write_agent(
                agent_in,
                &json!({
                    "jsonrpc": "2.0",
                    "method": "session/cancel",
                    "params": { "sessionId": session_id }
                }),
            )?;
            let pending = {
                let mut state = lock_state(state)?;
                std::mem::take(&mut state.pending)
            };
            for rpc_id in pending.into_values() {
                write_agent(
                    agent_in,
                    &json!({
                        "jsonrpc": "2.0",
                        "id": rpc_id,
                        "result": {
                            "outcome": { "outcome": "cancelled" }
                        }
                    }),
                )?;
            }
            cv.notify_all();
            write_ok(output, &id)
        }
    }
}

fn take_pending(state: &Mutex<SessionState>, cv: &Condvar, request_id: &str) -> Result<Value> {
    let started = Instant::now();
    let mut guard = lock_state(state)?;
    loop {
        if let Some(rpc_id) = guard.pending.remove(request_id) {
            return Ok(rpc_id);
        }
        if guard.finished || guard.active_prompt.is_none() {
            break;
        }
        let remaining = PERMISSION_WAIT.saturating_sub(started.elapsed());
        if remaining.is_zero() {
            break;
        }
        let (next, timeout) = cv
            .wait_timeout(guard, remaining)
            .map_err(|_| Error::new("io", "ACP session lock failed"))?;
        guard = next;
        if timeout.timed_out() {
            break;
        }
    }
    Err(Error::new(
        "not_found",
        format!("no pending permission request {request_id}"),
    ))
}

fn wait_for_agent_exit(state: &Mutex<SessionState>, cv: &Condvar, child: &Mutex<&mut ChildGuard>) {
    let started = Instant::now();
    let Ok(mut guard) = state.lock() else {
        if let Ok(mut child) = child.lock() {
            child.kill();
        }
        return;
    };
    while !guard.finished {
        let remaining = AGENT_EXIT_GRACE.saturating_sub(started.elapsed());
        if remaining.is_zero() {
            break;
        }
        match cv.wait_timeout(guard, remaining) {
            Ok((next, timeout)) => {
                guard = next;
                if timeout.timed_out() {
                    break;
                }
            }
            Err(_) => return,
        }
    }
    if !guard.finished {
        drop(guard);
        if let Ok(mut child) = child.lock() {
            child.kill();
        }
    }
}

fn close_agent_stdin(agent_in: &Mutex<Option<ChildStdin>>) {
    if let Ok(mut stdin) = agent_in.lock() {
        *stdin = None;
    }
}

fn write_agent(agent_in: &Mutex<Option<ChildStdin>>, value: &Value) -> Result<()> {
    let mut stdin = agent_in
        .lock()
        .map_err(|_| Error::new("io", "agent stdin lock failed"))?;
    let stdin = stdin
        .as_mut()
        .ok_or_else(|| Error::new("io", "agent stdin is closed"))?;
    write_json_frame(stdin, value)
}

fn emit(
    output: &Mutex<&mut (impl Write + Send)>,
    sequence: u64,
    event: &str,
    data: Value,
) -> Result<()> {
    write_locked(
        output,
        &json!({
            "event": event,
            "sequence": sequence,
            "data": data,
        }),
    )
}

fn write_ok(output: &Mutex<&mut (impl Write + Send)>, id: &str) -> Result<()> {
    write_locked(
        output,
        &json!({
            "id": id,
            "result": { "type": "ok" }
        }),
    )
}

fn write_locked(output: &Mutex<&mut (impl Write + Send)>, value: &Value) -> Result<()> {
    let mut output = output
        .lock()
        .map_err(|_| Error::new("io", "bridge output lock failed"))?;
    write_json_frame(&mut **output, value)
}

fn write_locked_error(
    output: &Mutex<&mut (impl Write + Send)>,
    id: &str,
    error: &Error,
) -> Result<()> {
    write_locked(
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

fn write_error(output: &mut impl Write, id: &str, error: &Error) -> Result<()> {
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

fn lock_state(state: &Mutex<SessionState>) -> Result<std::sync::MutexGuard<'_, SessionState>> {
    state
        .lock()
        .map_err(|_| Error::new("io", "ACP session lock failed"))
}

fn json_rpc_id_key(id: &Value) -> String {
    match id {
        Value::String(value) => value.clone(),
        Value::Number(value) => value.to_string(),
        Value::Bool(value) => value.to_string(),
        Value::Null => "null".into(),
        other => other.to_string(),
    }
}

fn normalize_permission(session_id: &str, request_id: &str, params: &Value) -> Value {
    let tool_call_value = params.get("toolCall");
    let title = tool_call_value
        .and_then(|tool| tool.get("title"))
        .and_then(Value::as_str)
        .or_else(|| params.get("title").and_then(Value::as_str))
        .unwrap_or("Permission requested");
    let description = params
        .get("description")
        .and_then(Value::as_str)
        .map(Value::from)
        .unwrap_or(Value::Null);
    let tool_call = match tool_call_value.and_then(Value::as_object) {
        Some(tool) => json!({
            "tool_call_id": tool.get("toolCallId").and_then(Value::as_str).unwrap_or(""),
            "title": tool.get("title").and_then(Value::as_str).unwrap_or(title),
            "kind": tool.get("kind").and_then(Value::as_str).unwrap_or(""),
            "status": tool.get("status").and_then(Value::as_str).unwrap_or(""),
        }),
        None => Value::Null,
    };
    let options = params
        .get("options")
        .and_then(Value::as_array)
        .map(|options| {
            options
                .iter()
                .filter_map(|option| {
                    let option_id = option.get("optionId").and_then(Value::as_str)?;
                    Some(json!({
                        "option_id": option_id,
                        "name": option.get("name").and_then(Value::as_str).unwrap_or(""),
                        "kind": option.get("kind").and_then(Value::as_str).unwrap_or(""),
                    }))
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    json!({
        "request_id": request_id,
        "session_id": session_id,
        "title": title,
        "description": description,
        "tool_call": tool_call,
        "options": options,
    })
}

#[derive(Debug)]
enum DeviceAction {
    Prompt {
        id: String,
        text: String,
    },
    Permission {
        id: String,
        request_id: String,
        selected: Option<String>,
    },
    Cancel {
        id: String,
    },
}

fn parse_action(payload: &[u8]) -> Result<DeviceAction> {
    let value = parse_unique_value(payload)?;
    let object = value
        .as_object()
        .ok_or_else(|| Error::new("invalid_params", "action frame must be a JSON object"))?;
    if object.contains_key("method") || object.contains_key("auth") {
        return Err(Error::new(
            "forbidden",
            "action frame must not include method or auth",
        ));
    }
    for key in object.keys() {
        if !matches!(key.as_str(), "id" | "action" | "params") {
            return Err(Error::new(
                "invalid_params",
                format!("action frame contains unknown field {key}"),
            ));
        }
    }
    let id = object
        .get("id")
        .and_then(Value::as_str)
        .map(str::to_string)
        .filter(|id| valid_request_id(id))
        .unwrap_or_else(|| "0".to_string());
    let action = object
        .get("action")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_params", "action frame is missing action"))?;
    let params = object.get("params").cloned().unwrap_or(Value::Null);
    match action {
        "prompt" => parse_prompt_action(id, &params),
        "permission" => parse_permission_action(id, &params),
        "cancel" => parse_cancel_action(id, &params),
        other => Err(Error::new(
            "invalid_params",
            format!("unknown action {other}"),
        )),
    }
}

fn parse_prompt_action(id: String, params: &Value) -> Result<DeviceAction> {
    let object = expect_params_object(params, "prompt")?;
    reject_unknown_keys(object, &["text"])?;
    let text = object
        .get("text")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_params", "prompt text must be a string"))?;
    if text.is_empty() || text.len() > MAX_PROMPT_BYTES {
        return Err(Error::new(
            "invalid_params",
            "prompt text must be non-empty and at most 262144 bytes",
        ));
    }
    Ok(DeviceAction::Prompt {
        id,
        text: text.to_string(),
    })
}

fn parse_permission_action(id: String, params: &Value) -> Result<DeviceAction> {
    let object = expect_params_object(params, "permission")?;
    reject_unknown_keys(object, &["request_id", "option_id", "cancelled"])?;
    let request_id = object
        .get("request_id")
        .and_then(Value::as_str)
        .filter(|id| !id.is_empty())
        .ok_or_else(|| Error::new("invalid_params", "permission request_id must be a string"))?
        .to_string();
    let cancelled = object.get("cancelled").and_then(Value::as_bool);
    let option_id = object.get("option_id").and_then(Value::as_str);
    let selected = match (cancelled, option_id) {
        (Some(true), _) => None,
        (_, Some(option_id)) if !option_id.is_empty() => Some(option_id.to_string()),
        _ => {
            return Err(Error::new(
                "invalid_params",
                "permission requires option_id or cancelled",
            ));
        }
    };
    Ok(DeviceAction::Permission {
        id,
        request_id,
        selected,
    })
}

fn parse_cancel_action(id: String, params: &Value) -> Result<DeviceAction> {
    match params {
        Value::Null => Ok(DeviceAction::Cancel { id }),
        Value::Object(object) => {
            if object.is_empty() {
                Ok(DeviceAction::Cancel { id })
            } else {
                Err(Error::new("invalid_params", "cancel params must be empty"))
            }
        }
        _ => Err(Error::new(
            "invalid_params",
            "cancel params must be an object",
        )),
    }
}

fn expect_params_object<'a>(params: &'a Value, action: &str) -> Result<&'a Map<String, Value>> {
    params.as_object().ok_or_else(|| {
        Error::new(
            "invalid_params",
            format!("{action} params must be an object"),
        )
    })
}

fn reject_unknown_keys(object: &Map<String, Value>, allowed: &[&str]) -> Result<()> {
    for key in object.keys() {
        if !allowed.contains(&key.as_str()) {
            return Err(Error::new("invalid_params", format!("unknown field {key}")));
        }
    }
    Ok(())
}

fn valid_request_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b':' | b'-'))
}

fn frame_id(payload: &[u8]) -> String {
    serde_json::from_slice::<Value>(payload)
        .ok()
        .and_then(|value| value.get("id").and_then(Value::as_str).map(str::to_string))
        .filter(|id| valid_request_id(id))
        .unwrap_or_else(|| "0".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::role::Role;
    use std::io::Cursor;

    fn test_paths() -> (tempfile::TempDir, Paths) {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::from_parts(
            dir.path().join("host"),
            dir.path().join("authorized_keys"),
            dir.path().join("luvus"),
        );
        std::fs::create_dir_all(&paths.config_dir).unwrap();
        (dir, paths)
    }

    fn write_agents(paths: &Paths, body: &str) {
        std::fs::write(paths.config_dir.join("acp-agents.json"), body).unwrap();
    }

    fn unavailable_overrides() -> &'static str {
        r#"[
            {"id":"codex","name":"Codex","command":"/nonexistent/luvia-test-codex-acp"},
            {"id":"claude","name":"Claude Code","command":"/nonexistent/luvia-test-claude-code-acp"},
            {"id":"gemini","name":"Gemini CLI","command":"/nonexistent/luvia-test-gemini"}
        ]"#
    }

    fn grant(role: Role) -> Grant {
        Grant {
            id: "ab".repeat(16),
            name: "phone".into(),
            role,
            fingerprint: "SHA256:test".into(),
            key_type: "ssh-ed25519".into(),
            key: "AAAA".into(),
            comment: String::new(),
            created_at: 0,
        }
    }

    fn frames_from(output: &[u8]) -> Vec<Value> {
        output
            .split(|byte| *byte == b'\n')
            .filter(|line| !line.is_empty())
            .map(|line| serde_json::from_slice(line).unwrap())
            .collect()
    }

    #[test]
    fn catalog_merges_overrides_by_id() {
        let (_dir, paths) = test_paths();
        write_agents(
            &paths,
            r#"[
                {"id":"codex","name":"Custom Codex","command":"/bin/sh","args":["-c","true"]},
                {"id":"goose","name":"Goose","command":"/bin/sh","args":["acp"]}
            ]"#,
        );
        let catalog = catalog(&paths);
        let ids: Vec<&str> = catalog.iter().map(|(spec, _)| spec.id.as_str()).collect();
        assert_eq!(ids, ["codex", "claude", "gemini", "goose"]);
        let codex = catalog.iter().find(|(spec, _)| spec.id == "codex").unwrap();
        assert_eq!(codex.0.name, "Custom Codex");
        assert_eq!(codex.0.command, "/bin/sh");
        assert_eq!(codex.0.args, ["-c", "true"]);
        assert!(codex.1);
        let goose = catalog.iter().find(|(spec, _)| spec.id == "goose").unwrap();
        assert_eq!(goose.0.args, ["acp"]);
        assert!(goose.1);
    }

    #[test]
    fn catalog_treats_invalid_config_as_empty() {
        let (_dir, paths) = test_paths();
        write_agents(&paths, "{not json");
        let catalog = catalog(&paths);
        let ids: Vec<&str> = catalog.iter().map(|(spec, _)| spec.id.as_str()).collect();
        assert_eq!(ids, ["codex", "claude", "gemini"]);
    }

    #[test]
    fn augment_capabilities_appends_methods_and_contracts() {
        let (_dir, paths) = test_paths();
        write_agents(
            &paths,
            r#"[{"id":"sh","name":"Shell","command":"/bin/sh"}]"#,
        );
        let mut frame = serde_json::to_vec(&json!({
            "id": "1",
            "result": {
                "methods": ["ping"],
                "method_contracts": [
                    {"method":"ping","access":"read","scope":"read","idempotent":true}
                ],
                "extra": {"kept": true}
            }
        }))
        .unwrap();
        frame.push(b'\n');
        let out = augment_capabilities(&frame, &paths).unwrap();
        assert!(out.ends_with(b"\n"));
        let value: Value = serde_json::from_slice(strip_lf(&out)).unwrap();
        let methods = value["result"]["methods"].as_array().unwrap();
        assert_eq!(methods[0], "ping");
        assert!(methods.iter().any(|m| m == AGENTS_METHOD));
        assert!(methods.iter().any(|m| m == SESSION_OPEN_METHOD));
        let contracts = value["result"]["method_contracts"].as_array().unwrap();
        assert_eq!(contracts[0]["method"], "ping");
        assert_eq!(
            contracts.iter().find(|c| c["method"] == AGENTS_METHOD),
            Some(&json!({
                "method": AGENTS_METHOD,
                "access": "read",
                "scope": "read",
                "idempotent": true
            }))
        );
        assert_eq!(
            contracts
                .iter()
                .find(|c| c["method"] == SESSION_OPEN_METHOD),
            Some(&json!({
                "method": SESSION_OPEN_METHOD,
                "access": "write",
                "scope": "agent",
                "idempotent": false
            }))
        );
        assert_eq!(value["result"]["extra"]["kept"], true);
    }

    #[test]
    fn augment_capabilities_creates_methods_and_skips_missing_contracts() {
        let (_dir, paths) = test_paths();
        write_agents(
            &paths,
            r#"[{"id":"sh","name":"Shell","command":"/bin/sh"}]"#,
        );
        let mut frame = serde_json::to_vec(&json!({
            "id": "1",
            "result": { "protocol": "uhp/1" }
        }))
        .unwrap();
        frame.push(b'\n');
        let value: Value =
            serde_json::from_slice(strip_lf(&augment_capabilities(&frame, &paths).unwrap()))
                .unwrap();
        assert_eq!(
            value["result"]["methods"],
            json!([AGENTS_METHOD, SESSION_OPEN_METHOD])
        );
        assert!(value["result"].get("method_contracts").is_none());
        assert_eq!(value["result"]["protocol"], "uhp/1");
    }

    #[test]
    fn augment_capabilities_is_a_no_op_when_no_agent_is_available() {
        let (_dir, paths) = test_paths();
        write_agents(&paths, unavailable_overrides());
        let frame = b"{\"id\":\"1\",\"result\":{\"methods\":[\"ping\"]}}\n";
        assert_eq!(augment_capabilities(frame, &paths).unwrap(), frame);
        assert!(!any_available(&paths));
    }

    #[test]
    fn normalize_permission_prefers_tool_call_title() {
        let params = json!({
            "title": "Allow edit?",
            "description": "Will edit file.rs",
            "toolCall": {
                "toolCallId": "tc1",
                "title": "Edit file.rs",
                "kind": "edit",
                "status": "pending"
            },
            "options": [
                {"optionId": "allow-once", "name": "Allow once", "kind": "allow_once"},
                {"name": "missing id"}
            ]
        });
        assert_eq!(
            normalize_permission("sess_1", "100", &params),
            json!({
                "request_id": "100",
                "session_id": "sess_1",
                "title": "Edit file.rs",
                "description": "Will edit file.rs",
                "tool_call": {
                    "tool_call_id": "tc1",
                    "title": "Edit file.rs",
                    "kind": "edit",
                    "status": "pending"
                },
                "options": [
                    {
                        "option_id": "allow-once",
                        "name": "Allow once",
                        "kind": "allow_once"
                    }
                ]
            })
        );
    }

    #[test]
    fn normalize_permission_defaults_title_and_null_tool_call() {
        let params = json!({ "options": [] });
        let value = normalize_permission("sess", "7", &params);
        assert_eq!(value["title"], "Permission requested");
        assert_eq!(value["description"], Value::Null);
        assert_eq!(value["tool_call"], Value::Null);
        assert_eq!(value["request_id"], "7");
    }

    #[test]
    fn action_frame_validation_rejects_method_auth_and_unknown_actions() {
        let err =
            parse_action(br#"{"id":"1","method":"ping","action":"prompt","params":{"text":"hi"}}"#)
                .unwrap_err();
        assert_eq!(err.code, "forbidden");
        let err =
            parse_action(br#"{"id":"1","auth":"x","action":"prompt","params":{"text":"hi"}}"#)
                .unwrap_err();
        assert_eq!(err.code, "forbidden");
        let err = parse_action(br#"{"id":"1","action":"explode","params":{}}"#).unwrap_err();
        assert_eq!(err.code, "invalid_params");
        let err =
            parse_action(br#"{"id":"1","action":"prompt","params":{"text":""}}"#).unwrap_err();
        assert_eq!(err.code, "invalid_params");
        let err = parse_action(br#"{"id":"1","action":"prompt","params":{"text":"hi","x":1}}"#)
            .unwrap_err();
        assert_eq!(err.code, "invalid_params");
        parse_action(br#"{"id":"1","action":"prompt","params":{"text":"hi"}}"#).unwrap();
        parse_action(
            br#"{"id":"1","action":"permission","params":{"request_id":"100","option_id":"allow"}}"#,
        )
        .unwrap();
        parse_action(
            br#"{"id":"1","action":"permission","params":{"request_id":"100","cancelled":true}}"#,
        )
        .unwrap();
        parse_action(br#"{"id":"1","action":"cancel","params":{}}"#).unwrap();
        let err = parse_action(br#"{"id":"1","action":"cancel","params":{"x":1}}"#).unwrap_err();
        assert_eq!(err.code, "invalid_params");
    }

    #[test]
    fn handle_agents_lists_catalog() {
        let (_dir, paths) = test_paths();
        write_agents(
            &paths,
            r#"[{"id":"sh","name":"Shell","command":"/bin/sh"}]"#,
        );
        let mut output = Vec::new();
        handle_agents("req", &paths, &mut output).unwrap();
        let value: Value = serde_json::from_slice(strip_lf(&output)).unwrap();
        assert_eq!(value["id"], "req");
        assert_eq!(value["result"]["type"], "acp_agents");
        let sh = value["result"]["agents"]
            .as_array()
            .unwrap()
            .iter()
            .find(|agent| agent["id"] == "sh")
            .unwrap();
        assert_eq!(sh["available"], true);
        assert_eq!(sh["command"], "/bin/sh");
    }

    #[test]
    fn observer_session_open_is_forbidden() {
        let (_dir, paths) = test_paths();
        let mut input = Cursor::new(Vec::new());
        let mut output = Vec::new();
        serve_session(
            &grant(Role::Observer),
            &paths,
            "1",
            &json!({"agent":"codex","cwd":"/tmp"}),
            &mut input,
            &mut output,
        )
        .unwrap();
        let value: Value = serde_json::from_slice(strip_lf(&output)).unwrap();
        assert_eq!(value["id"], "1");
        assert_eq!(value["error"]["code"], "forbidden");
    }

    #[test]
    fn unknown_and_unavailable_agents_are_ack_errors() {
        let (_dir, paths) = test_paths();
        write_agents(&paths, unavailable_overrides());
        let cwd = paths.config_dir.to_string_lossy().into_owned();
        let mut input = Cursor::new(Vec::new());
        let mut output = Vec::new();
        serve_session(
            &grant(Role::Controller),
            &paths,
            "1",
            &json!({"agent":"nope","cwd": cwd}),
            &mut input,
            &mut output,
        )
        .unwrap();
        let value: Value = serde_json::from_slice(strip_lf(&output)).unwrap();
        assert_eq!(value["error"]["code"], "unknown_agent");

        output.clear();
        serve_session(
            &grant(Role::Controller),
            &paths,
            "2",
            &json!({"agent":"codex","cwd": cwd}),
            &mut input,
            &mut output,
        )
        .unwrap();
        let value: Value = serde_json::from_slice(strip_lf(&output)).unwrap();
        assert_eq!(value["error"]["code"], "agent_unavailable");
    }

    #[test]
    fn session_open_rejects_relative_cwd() {
        let (_dir, paths) = test_paths();
        let mut input = Cursor::new(Vec::new());
        let mut output = Vec::new();
        serve_session(
            &grant(Role::Controller),
            &paths,
            "1",
            &json!({"agent":"codex","cwd":"relative"}),
            &mut input,
            &mut output,
        )
        .unwrap();
        let value: Value = serde_json::from_slice(strip_lf(&output)).unwrap();
        assert_eq!(value["error"]["code"], "invalid_params");
    }

    #[test]
    fn fake_acp_agent_session_emits_update_permission_turn_exit() {
        let (dir, paths) = test_paths();
        let script = dir.path().join("fake-agent.sh");
        std::fs::write(
            &script,
            r#"IFS= read -r line || exit 1
printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'
IFS= read -r line || exit 1
printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"sess_test"}}'
IFS= read -r line || exit 1
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}}}'
printf '%s\n' '{"jsonrpc":"2.0","id":100,"method":"session/request_permission","params":{"title":"Edit","options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]}}'
IFS= read -r line || exit 1
printf '%s\n' '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}'
"#,
        )
        .unwrap();
        write_agents(
            &paths,
            &serde_json::to_string(&json!([{
                "id": "fake",
                "name": "Fake",
                "command": "/bin/sh",
                "args": [script.to_string_lossy()]
            }]))
            .unwrap(),
        );
        let cwd = paths.config_dir.to_string_lossy().into_owned();
        let mut input = Vec::new();
        input.extend(
            serde_json::to_vec(&json!({
                "id": "p1",
                "action": "prompt",
                "params": { "text": "hello" }
            }))
            .unwrap(),
        );
        input.push(b'\n');
        input.extend(
            serde_json::to_vec(&json!({
                "id": "p2",
                "action": "permission",
                "params": { "request_id": "100", "option_id": "allow" }
            }))
            .unwrap(),
        );
        input.push(b'\n');
        let mut input = Cursor::new(input);
        let mut output = Vec::new();
        serve_session(
            &grant(Role::Controller),
            &paths,
            "open",
            &json!({"agent":"fake","cwd": cwd}),
            &mut input,
            &mut output,
        )
        .unwrap();
        let frames = frames_from(&output);
        assert_eq!(frames[0]["id"], "open");
        assert_eq!(frames[0]["result"]["type"], "acp_session");
        assert_eq!(frames[0]["result"]["session_id"], "sess_test");
        assert_eq!(frames[0]["result"]["agent"], "fake");
        assert_eq!(frames[0]["result"]["agent_name"], "Fake");
        assert_eq!(frames[0]["result"]["protocol_version"], 1);
        let events: Vec<&Value> = frames
            .iter()
            .filter(|frame| frame.get("event").is_some())
            .collect();
        let names: Vec<&str> = events
            .iter()
            .map(|frame| frame["event"].as_str().unwrap())
            .collect();
        assert_eq!(
            names,
            ["acp.update", "acp.permission", "acp.turn", "acp.exit"]
        );
        assert_eq!(events[0]["sequence"], 1);
        assert_eq!(events[1]["sequence"], 2);
        assert_eq!(events[2]["sequence"], 3);
        assert_eq!(events[3]["sequence"], 4);
        assert_eq!(events[0]["data"]["session_id"], "sess_test");
        assert_eq!(
            events[0]["data"]["update"]["sessionUpdate"],
            "agent_message_chunk"
        );
        assert_eq!(events[1]["data"]["request_id"], "100");
        assert_eq!(events[1]["data"]["title"], "Edit");
        assert_eq!(events[1]["data"]["options"][0]["option_id"], "allow");
        assert_eq!(events[2]["data"]["stop_reason"], "end_turn");
        assert_eq!(events[3]["data"]["code"], 0);
    }

    #[test]
    fn handshake_failure_carries_agent_stderr() {
        let (dir, paths) = test_paths();
        let script = dir.path().join("dying-agent.sh");
        std::fs::write(
            &script,
            "echo 'Error: error loading config: no models' >&2\nexit 1\n",
        )
        .unwrap();
        write_agents(
            &paths,
            &serde_json::to_string(&json!([{
                "id": "dying",
                "name": "Dying",
                "command": "/bin/sh",
                "args": [script.to_string_lossy()]
            }]))
            .unwrap(),
        );
        let cwd = paths.config_dir.to_string_lossy().into_owned();
        let mut input = Cursor::new(Vec::new());
        let mut output = Vec::new();
        serve_session(
            &grant(Role::Controller),
            &paths,
            "open",
            &json!({"agent":"dying","cwd": cwd}),
            &mut input,
            &mut output,
        )
        .unwrap();
        let frames = frames_from(&output);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0]["error"]["code"], "acp_error");
        let message = frames[0]["error"]["message"].as_str().unwrap();
        assert!(message.contains("error loading config"), "{message}");
    }
}
