use std::collections::BTreeMap;
use std::io::{BufRead, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use serde_json::{json, Map, Value};

use crate::discovery::{Backend, DiscoveredSession};
use crate::endpoint::Evidence;
use crate::error::{Error, Result};
use crate::frames::{self, write_json_frame};
use crate::grant::Grant;
use crate::paths::Paths;
use crate::prelude::validate_session_name;
use crate::role::Role;
use crate::unique_json::parse_unique_value;

const POLL_INTERVAL: Duration = Duration::from_millis(1500);
const HERDR_HOMEBREW: &str = "/opt/homebrew/bin/herdr";
const HERDR_USR_LOCAL: &str = "/usr/local/bin/herdr";

const METHODS: &[&str] = &[
    "uhp.capabilities",
    "session.snapshot",
    "workspace.list",
    "workspace.focus",
    "pane.list",
    "pane.rename",
    "agent.list",
    "agent.get",
    "agent.read",
    "agent.prompt",
    "agent.keys",
    "events.subscribe",
];

const WRITE_METHODS: &[&str] = &[
    "workspace.focus",
    "pane.rename",
    "agent.prompt",
    "agent.keys",
];

/// Locate the `herdr` binary. `LUVIA_HERDR_BIN` wins and does not fall through
/// when set, so tests can isolate a fake CLI.
pub fn resolve_bin() -> Option<PathBuf> {
    if let Some(explicit) = std::env::var_os("LUVIA_HERDR_BIN") {
        let path = PathBuf::from(explicit);
        return is_executable_file(&path).then_some(path);
    }
    if let Some(path) = find_on_path("herdr") {
        return Some(path);
    }
    for candidate in [HERDR_HOMEBREW, HERDR_USR_LOCAL] {
        let path = PathBuf::from(candidate);
        if is_executable_file(&path) {
            return Some(path);
        }
    }
    None
}

pub fn discover() -> Vec<DiscoveredSession> {
    let Some(bin) = resolve_bin() else {
        return Vec::new();
    };
    discover_with_bin(&bin)
}

pub fn discover_with_bin(bin: &Path) -> Vec<DiscoveredSession> {
    session_list(bin).unwrap_or_default()
}

fn session_list(bin: &Path) -> Result<Vec<DiscoveredSession>> {
    let value = run_herdr(bin, None, &["session", "list", "--json"])?;
    let Some(entries) = value.get("sessions").and_then(Value::as_array) else {
        return Ok(Vec::new());
    };
    let mut sessions = Vec::new();
    for entry in entries {
        let Some(raw_name) = entry.get("name").and_then(Value::as_str) else {
            continue;
        };
        let default = entry
            .get("default")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let running = entry
            .get("running")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let (name, herdr_session) = if default {
            ("herdr".to_string(), None)
        } else {
            (format!("herdr-{raw_name}"), Some(raw_name.to_string()))
        };
        if validate_session_name(&name).is_err() {
            continue;
        }
        sessions.push(herdr_discovered(name, running, herdr_session));
    }
    Ok(sessions)
}

fn herdr_discovered(
    name: String,
    running: bool,
    herdr_session: Option<String>,
) -> DiscoveredSession {
    DiscoveredSession {
        name,
        default: false,
        address: PathBuf::new(),
        evidence: Evidence {
            dev: 0,
            ino: 0,
            ctime_ns: 0,
        },
        backend: Backend::Herdr,
        running,
        herdr_session,
    }
}

pub fn serve(
    grant: &Grant,
    paths: &Paths,
    session: &DiscoveredSession,
    input: &mut (impl BufRead + Send),
    output: &mut (impl Write + Send),
) -> Result<()> {
    let herdr_session = session.herdr_session.as_deref();
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
        let id = request_id(&request);
        if crate::uhp::request_has_auth(&request).unwrap_or(true) {
            let error = Error::new("auth_rejected", "client-supplied auth is not allowed");
            let _ = crate::audit::denied(paths, &grant.id, grant.role, "unknown", error.code);
            write_request_error(output, &id, &error)?;
            continue;
        }
        let method = match crate::uhp::request_method(&request) {
            Ok(method) => method,
            Err(error) => {
                write_request_error(output, &id, &error)?;
                continue;
            }
        };
        if method.starts_with("luvia.") {
            match handle_luvia(grant, paths, &method, &id, &request, input, output)? {
                LuviaOutcome::Handled => continue,
                LuviaOutcome::Stop(result) => return result,
            }
        }
        if let Err(error) = authorize(grant.role, &method) {
            let _ = crate::audit::denied(paths, &grant.id, grant.role, &method, error.code);
            write_request_error(output, &id, &error)?;
            continue;
        }
        let params = request_params(&request);
        if method == "events.subscribe" {
            return subscribe(grant, paths, herdr_session, &id, input, output);
        }
        match dispatch(paths, herdr_session, session, &id, &method, &params, output) {
            Ok(()) => {}
            Err(error) => write_request_error(output, &id, &error)?,
        }
    }
}

enum LuviaOutcome {
    Handled,
    Stop(Result<()>),
}

fn handle_luvia(
    grant: &Grant,
    paths: &Paths,
    method: &str,
    id: &str,
    request: &[u8],
    input: &mut (impl BufRead + Send),
    output: &mut (impl Write + Send),
) -> Result<LuviaOutcome> {
    if crate::push::try_handle(grant, paths, method, request, output)? {
        return Ok(LuviaOutcome::Handled);
    }
    match method {
        "luvia.acp.agents" => {
            crate::acp::handle_agents(id, paths, output)?;
            Ok(LuviaOutcome::Handled)
        }
        "luvia.acp.session.open" => {
            let params = parse_unique_value(request)
                .ok()
                .and_then(|value| value.get("params").cloned())
                .unwrap_or(Value::Null);
            Ok(LuviaOutcome::Stop(crate::acp::serve_session(
                grant, paths, id, &params, input, output,
            )))
        }
        _ => {
            let error = Error::new("method_not_found", format!("unknown UHP method {method}"));
            let _ = crate::audit::denied(paths, &grant.id, grant.role, method, error.code);
            write_request_error(output, id, &error)?;
            Ok(LuviaOutcome::Handled)
        }
    }
}

fn authorize(role: Role, method: &str) -> Result<()> {
    if !METHODS.contains(&method) {
        return Err(Error::new(
            "method_not_found",
            format!("unknown UHP method {method}"),
        ));
    }
    if WRITE_METHODS.contains(&method) && role == Role::Observer {
        return Err(Error::new(
            "forbidden",
            format!("method {method} is not permitted for this device role"),
        ));
    }
    Ok(())
}

fn dispatch(
    paths: &Paths,
    herdr_session: Option<&str>,
    session: &DiscoveredSession,
    id: &str,
    method: &str,
    params: &Value,
    output: &mut impl Write,
) -> Result<()> {
    match method {
        "uhp.capabilities" => write_capabilities(paths, session, id, output),
        "session.snapshot" => {
            let result = snapshot(herdr_session, &session.name)?;
            write_ok(output, id, result)
        }
        "workspace.list" => {
            let result = workspace_list(herdr_session)?;
            write_ok(output, id, result)
        }
        "workspace.focus" => {
            let result = workspace_focus(herdr_session, params)?;
            write_ok(output, id, result)
        }
        "pane.list" => {
            let result = pane_list(herdr_session, params)?;
            write_ok(output, id, result)
        }
        "pane.rename" => {
            let result = pane_rename(herdr_session, params)?;
            write_ok(output, id, result)
        }
        "agent.list" => {
            let result = agent_list(herdr_session)?;
            write_ok(output, id, result)
        }
        "agent.get" => {
            let result = agent_get(herdr_session, params)?;
            write_ok(output, id, result)
        }
        "agent.read" => {
            let result = agent_read(herdr_session, params)?;
            write_ok(output, id, result)
        }
        "agent.prompt" => {
            let result = agent_prompt(herdr_session, params)?;
            write_ok(output, id, result)
        }
        "agent.keys" => {
            let result = agent_keys(herdr_session, params)?;
            write_ok(output, id, result)
        }
        _ => Err(Error::new(
            "method_not_found",
            format!("unknown UHP method {method}"),
        )),
    }
}

fn write_capabilities(
    paths: &Paths,
    session: &DiscoveredSession,
    id: &str,
    output: &mut impl Write,
) -> Result<()> {
    let version =
        server_version(session.herdr_session.as_deref()).unwrap_or_else(|_| "0.9.0".into());
    let result = json!({
        "protocol": {
            "name": "luvus-uhp",
            "major": 1,
            "minor": 0,
        },
        "server": {
            "name": "herdr",
            "version": version,
            "backend": "herdr",
        },
        "session": session.name,
        "event_sequence": 0,
        "server_generation": "herdr",
        "agent_states": ["idle", "working", "blocked", "unknown"],
        "methods": METHODS,
        "method_contracts": method_contracts(),
    });
    let mut frame = serde_json::to_vec(&json!({ "id": id, "result": result }))?;
    frame.push(b'\n');
    let frame = crate::acp::augment_capabilities(&frame, paths)?;
    let frame = crate::push::augment_capabilities(&frame, paths)?;
    output.write_all(&frame)?;
    output.flush()?;
    Ok(())
}

fn method_contracts() -> Vec<Value> {
    METHODS
        .iter()
        .map(|method| {
            let write = WRITE_METHODS.contains(method);
            let scope = method_scope(method);
            json!({
                "method": method,
                "access": if write { "write" } else { "read" },
                "scope": scope,
                "idempotent": *method == "uhp.capabilities",
            })
        })
        .collect()
}

fn method_scope(method: &str) -> &'static str {
    if method.starts_with("agent.") {
        "agent"
    } else if method.starts_with("workspace.") || method.starts_with("pane.") {
        "workspace"
    } else {
        "read"
    }
}

fn server_version(herdr_session: Option<&str>) -> Result<String> {
    let bin = require_bin()?;
    let value = run_herdr(&bin, herdr_session, &["status", "--json"])?;
    Ok(value
        .pointer("/server/version")
        .or_else(|| value.pointer("/client/version"))
        .and_then(Value::as_str)
        .unwrap_or("0.9.0")
        .to_string())
}

pub fn snapshot(herdr_session: Option<&str>, session_name: &str) -> Result<Value> {
    let bin = require_bin()?;
    let workspaces = run_herdr(&bin, herdr_session, &["workspace", "list"])?;
    let panes = run_herdr(&bin, herdr_session, &["pane", "list"])?;
    let agents = run_herdr(&bin, herdr_session, &["agent", "list"])?;
    Ok(translate_snapshot(
        session_name,
        &workspaces,
        &panes,
        &agents,
    ))
}

/// Build the Luvus `session.snapshot` object from Herdr CLI JSON (`result` wrapping optional).
pub fn translate_snapshot(
    session_name: &str,
    workspaces: &Value,
    panes: &Value,
    agents: &Value,
) -> Value {
    let workspace_rows = extract_array(workspaces, "workspaces");
    let pane_rows = extract_array(panes, "panes");
    let agent_rows = extract_array(agents, "agents");
    let agents_by_pane = index_by_pane(&agent_rows);

    let mut workspaces_out = Vec::new();
    let mut seen_workspace = std::collections::BTreeSet::new();

    if workspace_rows.is_empty() {
        let mut by_ws: BTreeMap<String, Vec<Value>> = BTreeMap::new();
        for pane in &pane_rows {
            let ws = string_field(pane, "workspace_id").unwrap_or_else(|| "w1".into());
            by_ws.entry(ws).or_default().push(pane.clone());
        }
        for (index, (ws_id, group)) in by_ws.into_iter().enumerate() {
            workspaces_out.push(workspace_object(
                index as i64 + 1,
                &ws_id,
                &ws_id,
                true,
                &group,
                &agents_by_pane,
            ));
        }
    } else {
        for (fallback, ws) in workspace_rows.iter().enumerate() {
            let number = ws
                .get("number")
                .and_then(Value::as_i64)
                .unwrap_or(fallback as i64 + 1);
            let ws_id = string_field(ws, "workspace_id").unwrap_or_else(|| format!("w{number}"));
            seen_workspace.insert(ws_id.clone());
            let name = string_field(ws, "label")
                .or_else(|| string_field(ws, "name"))
                .unwrap_or_else(|| ws_id.clone());
            let active = ws.get("focused").and_then(Value::as_bool).unwrap_or(false);
            let group: Vec<Value> = pane_rows
                .iter()
                .filter(|pane| {
                    string_field(pane, "workspace_id").as_deref() == Some(ws_id.as_str())
                })
                .cloned()
                .collect();
            workspaces_out.push(workspace_object(
                number,
                &ws_id,
                &name,
                active,
                &group,
                &agents_by_pane,
            ));
        }
        let mut leftover: BTreeMap<String, Vec<Value>> = BTreeMap::new();
        for pane in &pane_rows {
            let ws_id = string_field(pane, "workspace_id").unwrap_or_else(|| "w1".into());
            if !seen_workspace.contains(&ws_id) {
                leftover.entry(ws_id).or_default().push(pane.clone());
            }
        }
        for (ws_id, group) in leftover {
            let number = workspaces_out.len() as i64 + 1;
            workspaces_out.push(workspace_object(
                number,
                &ws_id,
                &ws_id,
                false,
                &group,
                &agents_by_pane,
            ));
        }
    }

    json!({
        "type": "session_snapshot",
        "protocol": {
            "name": "luvus-uhp",
            "major": 1,
            "minor": 0,
        },
        "session": session_name,
        "server_generation": "herdr",
        "event_sequence": 0,
        "workspaces": workspaces_out,
    })
}

fn workspace_object(
    index: i64,
    workspace_id: &str,
    name: &str,
    active: bool,
    panes: &[Value],
    agents_by_pane: &BTreeMap<String, Value>,
) -> Value {
    let mut tabs: BTreeMap<String, Vec<Value>> = BTreeMap::new();
    for pane in panes {
        let tab_id = string_field(pane, "tab_id").unwrap_or_else(|| format!("{workspace_id}:t1"));
        tabs.entry(tab_id).or_default().push(pane.clone());
    }
    let tabs_out: Vec<Value> = tabs
        .into_iter()
        .map(|(tab_id, group)| {
            let panes_out: Vec<Value> = group
                .iter()
                .map(|pane| translate_snapshot_pane(pane, agents_by_pane))
                .collect();
            json!({
                "id": tab_id,
                "name": tab_id,
                "panes": panes_out,
            })
        })
        .collect();
    json!({
        "index": index,
        "name": name,
        "pinned": false,
        "active": active,
        "cwd": Value::Null,
        "branch": Value::Null,
        "tabs": tabs_out,
    })
}

fn translate_snapshot_pane(pane: &Value, agents_by_pane: &BTreeMap<String, Value>) -> Value {
    let pane_id = string_field(pane, "pane_id").unwrap_or_default();
    let agent = agents_by_pane.get(&pane_id);
    let status = map_agent_status(
        agent
            .and_then(|row| string_field(row, "agent_status"))
            .or_else(|| string_field(pane, "agent_status"))
            .as_deref(),
    );
    let agent_name = agent.and_then(agent_label).or_else(|| agent_label(pane));
    let agent_kind = agent
        .and_then(|row| string_field(row, "agent"))
        .or_else(|| string_field(pane, "agent"));
    let title = string_field(pane, "terminal_title_stripped")
        .or_else(|| string_field(pane, "terminal_title"))
        .or_else(|| string_field(pane, "title"))
        .or_else(|| string_field(pane, "label"));
    json!({
        "pane_id": pane_id,
        "id": pane_id,
        "title": title,
        "agent_status": status,
        "agent_name": agent_name,
        "name": agent_name,
        "agent": agent_kind,
        "cwd": string_field(pane, "cwd").or_else(|| string_field(pane, "foreground_cwd")),
        "terminal_id": string_field(pane, "terminal_id"),
        "focused": pane.get("focused").and_then(Value::as_bool).unwrap_or(false),
        "kind": "terminal",
        "content_revision": pane.get("revision").and_then(Value::as_i64),
    })
}

fn workspace_list(herdr_session: Option<&str>) -> Result<Value> {
    let bin = require_bin()?;
    let value = run_herdr(&bin, herdr_session, &["workspace", "list"])?;
    let rows = extract_array(&value, "workspaces");
    let workspaces: Vec<Value> = rows
        .iter()
        .map(|ws| {
            let number = ws.get("number").and_then(Value::as_i64).unwrap_or(0);
            json!({
                "workspace": number.to_string(),
                "workspace_id": string_field(ws, "workspace_id"),
                "name": string_field(ws, "label").or_else(|| string_field(ws, "name")).unwrap_or_default(),
                "active": ws.get("focused").and_then(Value::as_bool).unwrap_or(false),
                "tabs": ws.get("tab_count").and_then(Value::as_i64).unwrap_or(0),
                "pinned": false,
            })
        })
        .collect();
    Ok(json!({ "workspaces": workspaces }))
}

fn workspace_focus(herdr_session: Option<&str>, params: &Value) -> Result<Value> {
    let target = resolve_workspace_id(herdr_session, params)?;
    let bin = require_bin()?;
    run_herdr(&bin, herdr_session, &["workspace", "focus", &target])?;
    Ok(json!({ "type": "ok" }))
}

fn pane_list(herdr_session: Option<&str>, params: &Value) -> Result<Value> {
    let bin = require_bin()?;
    let mut args = vec!["pane".to_string(), "list".to_string()];
    if let Some(workspace) = workspace_filter(params) {
        args.push("--workspace".into());
        args.push(workspace);
    }
    let arg_refs: Vec<&str> = args.iter().map(String::as_str).collect();
    let value = run_herdr(&bin, herdr_session, &arg_refs)?;
    let agents = run_herdr(&bin, herdr_session, &["agent", "list"]).unwrap_or_else(|_| json!({}));
    let agents_by_pane = index_by_pane(&extract_array(&agents, "agents"));
    let panes: Vec<Value> = extract_array(&value, "panes")
        .iter()
        .map(|pane| {
            let pane_id = string_field(pane, "pane_id").unwrap_or_default();
            let agent = agents_by_pane.get(&pane_id);
            json!({
                "pane": pane_id,
                "agent": agent.and_then(|row| string_field(row, "agent")).or_else(|| string_field(pane, "agent")),
                "status": map_agent_status(
                    agent
                        .and_then(|row| string_field(row, "agent_status"))
                        .or_else(|| string_field(pane, "agent_status"))
                        .as_deref(),
                ),
                "focused": pane.get("focused").and_then(Value::as_bool).unwrap_or(false),
                "cwd": string_field(pane, "cwd").or_else(|| string_field(pane, "foreground_cwd")),
            })
        })
        .collect();
    Ok(json!({ "panes": panes }))
}

fn pane_rename(herdr_session: Option<&str>, params: &Value) -> Result<Value> {
    let pane = require_target(params)?;
    let name = params
        .get("name")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_params", "pane.rename requires name"))?;
    if name.is_empty() {
        return Err(Error::new("invalid_params", "pane.rename name is empty"));
    }
    let bin = require_bin()?;
    run_herdr(&bin, herdr_session, &["pane", "rename", &pane, name])?;
    Ok(json!({
        "type": "pane_rename",
        "pane": pane,
        "name": name,
    }))
}

fn agent_list(herdr_session: Option<&str>) -> Result<Value> {
    let bin = require_bin()?;
    let value = run_herdr(&bin, herdr_session, &["agent", "list"])?;
    let agents: Vec<Value> = extract_array(&value, "agents")
        .iter()
        .map(translate_agent)
        .collect();
    Ok(json!({ "agents": agents }))
}

fn agent_get(herdr_session: Option<&str>, params: &Value) -> Result<Value> {
    let target = require_target(params)?;
    let bin = require_bin()?;
    let value = run_herdr(&bin, herdr_session, &["agent", "get", &target])?;
    let agent = value
        .get("agent")
        .cloned()
        .or_else(|| extract_array(&value, "agents").into_iter().next())
        .unwrap_or(value);
    Ok(translate_agent_get(&agent))
}

fn agent_read(herdr_session: Option<&str>, params: &Value) -> Result<Value> {
    let target = require_target(params)?;
    let source = params
        .get("source")
        .and_then(Value::as_str)
        .unwrap_or("recent");
    if !matches!(source, "visible" | "recent") {
        return Err(Error::new(
            "invalid_params",
            format!("unsupported agent.read source {source}"),
        ));
    }
    let lines = params
        .get("lines")
        .and_then(Value::as_u64)
        .unwrap_or(200)
        .to_string();
    let format = if params.get("ansi").and_then(Value::as_bool).unwrap_or(false) {
        "ansi"
    } else {
        "text"
    };
    let bin = require_bin()?;
    let value = run_herdr(
        &bin,
        herdr_session,
        &[
            "agent", "read", &target, "--source", source, "--lines", &lines, "--format", format,
        ],
    )?;
    let read = value.get("read").unwrap_or(&value);
    Ok(json!({
        "pane": string_field(read, "pane_id").unwrap_or(target),
        "text": string_field(read, "text").unwrap_or_default(),
        "revision": read.get("revision").and_then(Value::as_i64),
        "content_revision": read.get("revision").and_then(Value::as_i64),
        "terminal_id": string_field(read, "terminal_id"),
    }))
}

fn agent_prompt(herdr_session: Option<&str>, params: &Value) -> Result<Value> {
    let target = require_target(params)?;
    let text = params
        .get("text")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_params", "agent.prompt requires text"))?;
    let bin = require_bin()?;
    let value = run_herdr(&bin, herdr_session, &["agent", "prompt", &target, text])?;
    let agent = value.get("agent").unwrap_or(&value);
    Ok(json!({
        "pane": string_field(agent, "pane_id").unwrap_or(target),
        "submitted": true,
        "matched": false,
        "status": map_agent_status(string_field(agent, "agent_status").as_deref()),
        "baseline_revision": 0,
        "content_revision": agent.get("revision").and_then(Value::as_i64).unwrap_or(0),
        "evidence": "",
        "revision": agent.get("revision").and_then(Value::as_i64),
    }))
}

fn agent_keys(herdr_session: Option<&str>, params: &Value) -> Result<Value> {
    let target = require_target(params)?;
    let keys = params
        .get("keys")
        .and_then(Value::as_array)
        .ok_or_else(|| Error::new("invalid_params", "agent.keys requires keys"))?;
    if keys.is_empty() {
        return Err(Error::new(
            "invalid_params",
            "agent.keys needs at least one key",
        ));
    }
    let mut translated = Vec::new();
    for key in keys {
        let Some(name) = key.as_str() else {
            return Err(Error::new(
                "invalid_params",
                "agent.keys entries must be strings",
            ));
        };
        translated.push(translate_key(name)?);
    }
    let bin = require_bin()?;
    let mut args = vec!["agent".to_string(), "send-keys".to_string(), target.clone()];
    args.extend(translated);
    let arg_refs: Vec<&str> = args.iter().map(String::as_str).collect();
    run_herdr(&bin, herdr_session, &arg_refs)?;
    Ok(json!({ "type": "ok", "pane": target }))
}

fn translate_agent(row: &Value) -> Value {
    json!({
        "pane": string_field(row, "pane_id").unwrap_or_default(),
        "pane_id": string_field(row, "pane_id"),
        "name": agent_label(row),
        "agent": string_field(row, "agent"),
        "status": map_agent_status(string_field(row, "agent_status").as_deref()),
        "focused": row.get("focused").and_then(Value::as_bool).unwrap_or(false),
        "cwd": string_field(row, "cwd").or_else(|| string_field(row, "foreground_cwd")),
        "workspace_id": string_field(row, "workspace_id"),
        "tab": string_field(row, "tab_id"),
        "session": row.get("agent_session").and_then(|value| value.get("value")).and_then(Value::as_str),
    })
}

fn translate_agent_get(row: &Value) -> Value {
    let mut mapped = translate_agent(row);
    if let Some(object) = mapped.as_object_mut() {
        object.insert(
            "pane".into(),
            Value::String(string_field(row, "pane_id").unwrap_or_default()),
        );
        object.insert(
            "revision".into(),
            row.get("revision").cloned().unwrap_or(Value::Null),
        );
    }
    mapped
}

/// Map a UHP `agent.keys` token to a Herdr `send-keys` name.
pub fn translate_key(uhp: &str) -> Result<String> {
    let key = uhp.trim();
    if key.is_empty() {
        return Err(Error::new("invalid_params", "empty key"));
    }
    if key.chars().count() == 1 {
        return Ok(key.to_string());
    }
    let lower = key.to_ascii_lowercase();
    if let Some(rest) = lower.strip_prefix("ctrl+") {
        return translate_ctrl(rest);
    }
    if let Some(rest) = lower.strip_prefix("ctrl-") {
        return translate_ctrl(rest);
    }
    match lower.as_str() {
        "enter" | "return" => Ok("enter".into()),
        "esc" | "escape" => Ok("esc".into()),
        "tab" => Ok("tab".into()),
        "space" => Ok("space".into()),
        "backspace" => Ok("backspace".into()),
        "up" | "down" | "left" | "right" => Ok(lower),
        _ => Err(Error::new("invalid_params", format!("unknown key {uhp}"))),
    }
}

fn translate_ctrl(rest: &str) -> Result<String> {
    let mut chars = rest.chars();
    match (chars.next(), chars.next()) {
        (Some(letter), None) if letter.is_ascii_alphabetic() => {
            Ok(format!("ctrl+{}", letter.to_ascii_lowercase()))
        }
        _ => Err(Error::new(
            "invalid_params",
            format!("unknown key ctrl-{rest}"),
        )),
    }
}

pub fn map_agent_status(raw: Option<&str>) -> &'static str {
    match raw {
        Some("idle") => "idle",
        Some("working") => "working",
        Some("blocked") => "blocked",
        _ => "unknown",
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PaneWatch {
    pub pane_id: String,
    pub agent_status: String,
    pub agent_name: Option<String>,
    pub agent_kind: Option<String>,
    pub focused: bool,
    pub workspace_id: String,
    pub tab_id: String,
    pub terminal_id: Option<String>,
    pub cwd: Option<String>,
}

pub fn watches_from_lists(panes: &Value, agents: &Value) -> Vec<PaneWatch> {
    let agents_by_pane = index_by_pane(&extract_array(agents, "agents"));
    extract_array(panes, "panes")
        .iter()
        .map(|pane| {
            let pane_id = string_field(pane, "pane_id").unwrap_or_default();
            let agent = agents_by_pane.get(&pane_id);
            PaneWatch {
                pane_id: pane_id.clone(),
                agent_status: map_agent_status(
                    agent
                        .and_then(|row| string_field(row, "agent_status"))
                        .or_else(|| string_field(pane, "agent_status"))
                        .as_deref(),
                )
                .to_string(),
                agent_name: agent.and_then(agent_label).or_else(|| agent_label(pane)),
                agent_kind: agent
                    .and_then(|row| string_field(row, "agent"))
                    .or_else(|| string_field(pane, "agent")),
                focused: pane
                    .get("focused")
                    .and_then(Value::as_bool)
                    .unwrap_or(false),
                workspace_id: string_field(pane, "workspace_id").unwrap_or_default(),
                tab_id: string_field(pane, "tab_id").unwrap_or_default(),
                terminal_id: string_field(pane, "terminal_id"),
                cwd: string_field(pane, "cwd").or_else(|| string_field(pane, "foreground_cwd")),
            }
        })
        .collect()
}

/// Emit topology/status events. `initial` also writes a snapshot of the current set.
pub fn diff_events(
    previous: &[PaneWatch],
    current: &[PaneWatch],
    sequence: &mut u64,
) -> Vec<Value> {
    let mut events = Vec::new();
    let previous_by_id: BTreeMap<&str, &PaneWatch> = previous
        .iter()
        .map(|pane| (pane.pane_id.as_str(), pane))
        .collect();
    let current_by_id: BTreeMap<&str, &PaneWatch> = current
        .iter()
        .map(|pane| (pane.pane_id.as_str(), pane))
        .collect();

    for pane in current {
        if !previous_by_id.contains_key(pane.pane_id.as_str()) {
            *sequence += 1;
            events.push(event_frame(
                *sequence,
                "pane.created",
                json!({
                    "pane": pane.pane_id,
                    "terminal_id": pane.terminal_id,
                    "workspace": pane.workspace_id,
                    "tab": pane.tab_id,
                }),
            ));
            *sequence += 1;
            events.push(status_event(*sequence, pane));
            if pane.focused {
                *sequence += 1;
                events.push(event_frame(
                    *sequence,
                    "pane.focused",
                    json!({ "pane": pane.pane_id, "terminal_id": pane.terminal_id }),
                ));
            }
        }
    }

    for pane in previous {
        if !current_by_id.contains_key(pane.pane_id.as_str()) {
            *sequence += 1;
            events.push(event_frame(
                *sequence,
                "pane.closed",
                json!({
                    "pane": pane.pane_id,
                    "terminal_id": pane.terminal_id,
                    "workspace": pane.workspace_id,
                    "tab": pane.tab_id,
                }),
            ));
        }
    }

    for pane in current {
        let Some(old) = previous_by_id.get(pane.pane_id.as_str()) else {
            continue;
        };
        if old.agent_status != pane.agent_status
            || old.agent_name != pane.agent_name
            || old.agent_kind != pane.agent_kind
        {
            *sequence += 1;
            events.push(status_event(*sequence, pane));
        }
        if pane.focused && !old.focused {
            *sequence += 1;
            events.push(event_frame(
                *sequence,
                "pane.focused",
                json!({ "pane": pane.pane_id, "terminal_id": pane.terminal_id }),
            ));
        }
    }
    events
}

fn status_event(sequence: u64, pane: &PaneWatch) -> Value {
    event_frame(
        sequence,
        "pane.agent_status_changed",
        json!({
            "pane": pane.pane_id,
            "status": pane.agent_status,
            "agent": pane.agent_kind.as_ref().or(pane.agent_name.as_ref()),
            "cwd": pane.cwd,
        }),
    )
}

fn event_frame(sequence: u64, event: &str, data: Value) -> Value {
    json!({
        "event": event,
        "sequence": sequence,
        "data": data,
    })
}

fn subscribe(
    grant: &Grant,
    paths: &Paths,
    herdr_session: Option<&str>,
    id: &str,
    input: &mut (impl BufRead + Send),
    output: &mut (impl Write + Send),
) -> Result<()> {
    write_ok(
        output,
        id,
        json!({
            "type": "subscription_started",
            "sequence": 0,
            "replayed": 0,
            "queue_capacity": 256,
            "loss_behavior": "resync_required_then_close",
        }),
    )?;

    let output = Mutex::new(output);
    let stop = AtomicBool::new(false);
    let herdr_session = herdr_session.map(str::to_string);

    thread::scope(|scope| {
        let poller = scope.spawn(|| -> Result<()> {
            let mut sequence = 0u64;
            let mut previous: Vec<PaneWatch> = Vec::new();
            let mut initial = true;
            loop {
                if stop.load(Ordering::Relaxed) {
                    return Ok(());
                }
                match poll_once(
                    herdr_session.as_deref(),
                    &mut previous,
                    &mut sequence,
                    initial,
                ) {
                    Ok(events) => {
                        initial = false;
                        for event in events {
                            let mut output = output
                                .lock()
                                .map_err(|_| Error::new("io", "bridge output lock failed"))?;
                            write_json_frame(&mut **output, &event)?;
                        }
                    }
                    Err(error) => {
                        let mut output = output
                            .lock()
                            .map_err(|_| Error::new("io", "bridge output lock failed"))?;
                        write_request_error(&mut **output, id, &error)?;
                        return Err(error);
                    }
                }
                let slept = sleep_interruptible(&stop, POLL_INTERVAL);
                if !slept {
                    return Ok(());
                }
            }
        });
        let follow = read_follow_ups(grant, paths, input, &output);
        stop.store(true, Ordering::Relaxed);
        match (follow, poller.join()) {
            (Ok(()), Ok(Ok(()))) => Ok(()),
            (Err(error), _) => Err(error),
            (_, Ok(Err(error))) => Err(error),
            (_, Err(_)) => Err(Error::new("io", "herdr poll thread failed")),
        }
    })
}

fn poll_once(
    herdr_session: Option<&str>,
    previous: &mut Vec<PaneWatch>,
    sequence: &mut u64,
    initial: bool,
) -> Result<Vec<Value>> {
    let bin = require_bin()?;
    let panes = run_herdr(&bin, herdr_session, &["pane", "list"])?;
    let agents = run_herdr(&bin, herdr_session, &["agent", "list"])?;
    let current = watches_from_lists(&panes, &agents);
    let events = if initial {
        diff_events(&[], &current, sequence)
    } else {
        diff_events(previous, &current, sequence)
    };
    *previous = current;
    Ok(events)
}

fn sleep_interruptible(stop: &AtomicBool, total: Duration) -> bool {
    let slice = Duration::from_millis(100);
    let mut remaining = total;
    while remaining > Duration::ZERO {
        if stop.load(Ordering::Relaxed) {
            return false;
        }
        let step = remaining.min(slice);
        thread::sleep(step);
        remaining = remaining.saturating_sub(step);
    }
    !stop.load(Ordering::Relaxed)
}

fn read_follow_ups(
    grant: &Grant,
    paths: &Paths,
    input: &mut impl BufRead,
    output: &Mutex<&mut (impl Write + Send)>,
) -> Result<()> {
    loop {
        match frames::read_frame(input) {
            Ok(frame) => {
                let payload = &frame[..frame.len() - 1];
                let id = request_id(payload);
                let method =
                    crate::uhp::request_method(payload).unwrap_or_else(|_| "unknown".into());
                let error = Error::new(
                    "forbidden",
                    format!("method {method} is not permitted on an active stream"),
                );
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

fn require_bin() -> Result<PathBuf> {
    resolve_bin().ok_or_else(|| Error::new("backend_unavailable", "herdr is not available"))
}

fn run_herdr(bin: &Path, session: Option<&str>, args: &[&str]) -> Result<Value> {
    let mut command = Command::new(bin);
    if let Some(session) = session {
        command.arg("--session").arg(session);
    }
    command.args(args);
    let output = command
        .output()
        .map_err(|error| Error::new("backend_error", format!("failed to spawn herdr: {error}")))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        let message = if !stderr.trim().is_empty() {
            stderr.trim().to_string()
        } else if !stdout.trim().is_empty() {
            stdout.trim().to_string()
        } else {
            format!("herdr exited with status {}", output.status)
        };
        return Err(Error::new("backend_error", message));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let trimmed = stdout.trim();
    if trimmed.is_empty() {
        return Ok(json!({ "type": "ok" }));
    }
    let value: Value = serde_json::from_str(trimmed)
        .map_err(|_| Error::new("backend_error", "herdr returned non-JSON"))?;
    if let Some(error) = value.get("error") {
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("herdr error");
        return Err(Error::new("backend_error", message));
    }
    Ok(value.get("result").cloned().unwrap_or(value))
}

fn require_target(params: &Value) -> Result<String> {
    for key in ["target", "pane", "pane_id"] {
        if let Some(value) = params.get(key).and_then(as_stringish) {
            if !value.is_empty() {
                return Ok(value);
            }
        }
    }
    Err(Error::new("invalid_params", "missing target/pane"))
}

fn workspace_filter(params: &Value) -> Option<String> {
    params
        .get("workspace_id")
        .and_then(as_stringish)
        .or_else(|| params.get("workspace").and_then(as_stringish))
        .filter(|value| value.starts_with('w'))
}

fn resolve_workspace_id(herdr_session: Option<&str>, params: &Value) -> Result<String> {
    if let Some(id) = params
        .get("workspace_id")
        .and_then(as_stringish)
        .filter(|value| !value.is_empty())
    {
        return Ok(id);
    }
    let Some(raw) = params.get("workspace").and_then(as_stringish) else {
        return Err(Error::new(
            "invalid_params",
            "workspace.focus requires workspace",
        ));
    };
    if raw.starts_with('w') {
        return Ok(raw);
    }
    let number: i64 = raw
        .parse()
        .map_err(|_| Error::new("invalid_params", "workspace is not a number"))?;
    let bin = require_bin()?;
    let value = run_herdr(&bin, herdr_session, &["workspace", "list"])?;
    for ws in extract_array(&value, "workspaces") {
        if ws.get("number").and_then(Value::as_i64) == Some(number) {
            if let Some(id) = string_field(&ws, "workspace_id") {
                return Ok(id);
            }
        }
    }
    Ok(format!("w{number}"))
}

fn extract_array(value: &Value, key: &str) -> Vec<Value> {
    let root = value.get("result").unwrap_or(value);
    root.get(key)
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

fn index_by_pane(rows: &[Value]) -> BTreeMap<String, Value> {
    let mut map = BTreeMap::new();
    for row in rows {
        if let Some(id) = string_field(row, "pane_id") {
            map.insert(id, row.clone());
        }
    }
    map
}

fn agent_label(row: &Value) -> Option<String> {
    string_field(row, "name")
        .filter(|value| !value.is_empty())
        .or_else(|| string_field(row, "display_agent").filter(|value| !value.is_empty()))
        .or_else(|| string_field(row, "agent").filter(|value| !value.is_empty()))
}

fn string_field(value: &Value, key: &str) -> Option<String> {
    value.get(key).and_then(as_stringish)
}

fn as_stringish(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        _ => None,
    }
}

fn find_on_path(command: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).find_map(|dir| {
        let candidate = dir.join(command);
        is_executable_file(&candidate).then_some(candidate)
    })
}

fn is_executable_file(path: &Path) -> bool {
    let Ok(metadata) = std::fs::metadata(path) else {
        return false;
    };
    metadata.is_file() && metadata.permissions().mode() & 0o111 != 0
}

fn request_params(payload: &[u8]) -> Value {
    parse_unique_value(payload)
        .ok()
        .and_then(|value| value.get("params").cloned())
        .unwrap_or(Value::Object(Map::new()))
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

fn write_ok(output: &mut impl Write, id: &str, result: Value) -> Result<()> {
    write_json_frame(output, &json!({ "id": id, "result": result }))
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

    #[test]
    fn translate_key_maps_uhp_names() {
        assert_eq!(translate_key("enter").unwrap(), "enter");
        assert_eq!(translate_key("esc").unwrap(), "esc");
        assert_eq!(translate_key("escape").unwrap(), "esc");
        assert_eq!(translate_key("ctrl-c").unwrap(), "ctrl+c");
        assert_eq!(translate_key("ctrl+c").unwrap(), "ctrl+c");
        assert_eq!(translate_key("up").unwrap(), "up");
        assert_eq!(translate_key("y").unwrap(), "y");
        assert_eq!(
            translate_key("unknown-key").unwrap_err().code,
            "invalid_params"
        );
        assert_eq!(translate_key("delete").unwrap_err().code, "invalid_params");
        assert_eq!(translate_key("pageup").unwrap_err().code, "invalid_params");
    }

    #[test]
    fn map_status_collapses_unknown() {
        assert_eq!(map_agent_status(Some("idle")), "idle");
        assert_eq!(map_agent_status(Some("done")), "unknown");
        assert_eq!(map_agent_status(Some("thinking")), "unknown");
        assert_eq!(map_agent_status(None), "unknown");
    }
}
