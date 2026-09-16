use std::io::Cursor;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::Mutex;

use luvia_host::discovery::Backend;
use luvia_host::grant::Grant;
use luvia_host::herdr;
use luvia_host::paths::Paths;
use luvia_host::role::Role;
use serde_json::{json, Value};

static ENV_LOCK: Mutex<()> = Mutex::new(());

const FAKE_HERDR: &str = r#"#!/bin/sh
session=""
workspace=""
source="recent"
lines="200"
format="text"
flat=""
while [ $# -gt 0 ]; do
  case "$1" in
    --session) session="$2"; shift 2 ;;
    --json) shift ;;
    --workspace) workspace="$2"; shift 2 ;;
    --source) source="$2"; shift 2 ;;
    --lines) lines="$2"; shift 2 ;;
    --format) format="$2"; shift 2 ;;
    --ansi) format="ansi"; shift ;;
    --wait|--clear) shift ;;
    --timeout|--until) shift 2 ;;
    *)
      if [ -z "$flat" ]; then
        flat="$1"
      else
        flat="$flat $1"
      fi
      shift
      ;;
  esac
done

case "$flat" in
  "session list")
    printf '%s\n' '{"sessions":[{"default":true,"name":"default","running":true,"session_dir":"/tmp","socket_path":"/tmp/herdr.sock"}]}'
    ;;
  "status")
    printf '%s\n' '{"client":{"version":"0.9.0"},"server":{"status":"running","running":true,"version":"0.9.0"}}'
    ;;
  "workspace list")
    printf '%s\n' '{"id":"cli:workspace:list","result":{"type":"workspace_list","workspaces":[{"active_tab_id":"w1:t1","agent_status":"unknown","focused":true,"label":"demo","number":1,"pane_count":1,"tab_count":1,"workspace_id":"w1"}]}}'
    ;;
  "pane list")
    printf '%s\n' '{"id":"cli:pane:list","result":{"type":"pane_list","panes":[{"agent_status":"working","cwd":"/tmp/demo","focused":true,"foreground_cwd":"/tmp/demo","pane_id":"w1:p1","revision":7,"tab_id":"w1:t1","terminal_id":"term_abc","terminal_title":"codex","terminal_title_stripped":"codex","workspace_id":"w1"}]}}'
    ;;
  "agent list")
    printf '%s\n' '{"id":"cli:agent:list","result":{"type":"agent_list","agents":[{"agent":"codex","agent_status":"working","cwd":"/tmp/demo","focused":true,"name":"reviewer","pane_id":"w1:p1","revision":7,"tab_id":"w1:t1","terminal_id":"term_abc","workspace_id":"w1"}]}}'
    ;;
  "agent get w1:p1"|"agent get reviewer")
    printf '%s\n' '{"id":"cli:agent:get","result":{"type":"agent_info","agent":{"agent":"codex","agent_status":"working","cwd":"/tmp/demo","focused":true,"name":"reviewer","pane_id":"w1:p1","revision":7,"tab_id":"w1:t1","terminal_id":"term_abc","workspace_id":"w1"}}}'
    ;;
  agent\ prompt\ *)
    printf '%s\n' '{"id":"cli:agent:prompt","result":{"type":"agent_prompted","agent":{"agent":"codex","agent_status":"working","name":"reviewer","pane_id":"w1:p1","revision":8}}}'
    ;;
  *)
    printf '%s\n' '{"id":"cli:ok","result":{"type":"ok"}}'
    ;;
esac
"#;

fn write_fake_herdr(dir: &std::path::Path) -> PathBuf {
    let path = dir.join("herdr");
    std::fs::write(&path, FAKE_HERDR).unwrap();
    let mut permissions = std::fs::metadata(&path).unwrap().permissions();
    permissions.set_mode(0o755);
    std::fs::set_permissions(&path, permissions).unwrap();
    path
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
        push: None,
    }
}
fn paths(dir: &std::path::Path) -> Paths {
    Paths::from_parts(
        dir.join("host"),
        dir.join("authorized_keys"),
        dir.join("luvus"),
    )
}

fn herdr_session() -> luvia_host::discovery::DiscoveredSession {
    luvia_host::discovery::DiscoveredSession {
        name: "herdr".into(),
        default: false,
        address: PathBuf::new(),
        evidence: luvia_host::endpoint::Evidence {
            dev: 0,
            ino: 0,
            ctime_ns: 0,
        },
        backend: Backend::Herdr,
        running: true,
        herdr_session: None,
    }
}

fn canned_workspace_list() -> Value {
    json!({
        "type": "workspace_list",
        "workspaces": [{
            "active_tab_id": "w1:t1",
            "agent_status": "unknown",
            "focused": true,
            "label": "demo",
            "number": 1,
            "pane_count": 1,
            "tab_count": 1,
            "workspace_id": "w1"
        }]
    })
}

fn canned_pane_list() -> Value {
    json!({
        "type": "pane_list",
        "panes": [{
            "agent_status": "working",
            "cwd": "/tmp/demo",
            "focused": true,
            "foreground_cwd": "/tmp/demo",
            "pane_id": "w1:p1",
            "revision": 7,
            "tab_id": "w1:t1",
            "terminal_id": "term_abc",
            "terminal_title": "codex",
            "terminal_title_stripped": "codex",
            "workspace_id": "w1"
        }]
    })
}

fn canned_agent_list() -> Value {
    json!({
        "type": "agent_list",
        "agents": [{
            "agent": "codex",
            "agent_status": "working",
            "cwd": "/tmp/demo",
            "focused": true,
            "name": "reviewer",
            "pane_id": "w1:p1",
            "revision": 7,
            "tab_id": "w1:t1",
            "terminal_id": "term_abc",
            "workspace_id": "w1"
        }]
    })
}

#[test]
fn discover_lists_herdr_with_backend() {
    let _guard = ENV_LOCK.lock().unwrap();
    let dir = tempfile::tempdir().unwrap();
    let bin = write_fake_herdr(dir.path());
    unsafe { std::env::set_var("LUVIA_HERDR_BIN", &bin) };
    let sessions = herdr::discover();
    unsafe { std::env::remove_var("LUVIA_HERDR_BIN") };
    let herdr = sessions
        .iter()
        .find(|session| session.name == "herdr")
        .expect("herdr session");
    assert_eq!(herdr.backend, Backend::Herdr);
    assert!(herdr.running);
    assert!(!herdr.default);
    let metadata = herdr.metadata();
    assert_eq!(metadata["backend"], "herdr");
    assert_eq!(metadata["running"], true);
    assert_eq!(metadata["name"], "herdr");
}

#[test]
fn herdr_snapshot_translation_sets_agent_status_and_name() {
    let snapshot = herdr::translate_snapshot(
        "herdr",
        &canned_workspace_list(),
        &canned_pane_list(),
        &canned_agent_list(),
    );
    assert_eq!(snapshot["type"], "session_snapshot");
    assert_eq!(snapshot["protocol"]["name"], "luvus-uhp");
    let pane = &snapshot["workspaces"][0]["tabs"][0]["panes"][0];
    assert_eq!(pane["pane_id"], "w1:p1");
    assert_eq!(pane["id"], "w1:p1");
    assert_eq!(pane["agent_status"], "working");
    assert_eq!(pane["agent_name"], "reviewer");
    assert_eq!(pane["name"], "reviewer");
    assert_eq!(pane["terminal_id"], "term_abc");
    assert_eq!(pane["focused"], true);
}

#[test]
fn herdr_observer_cannot_prompt() {
    let _guard = ENV_LOCK.lock().unwrap();
    let dir = tempfile::tempdir().unwrap();
    let bin = write_fake_herdr(dir.path());
    unsafe { std::env::set_var("LUVIA_HERDR_BIN", &bin) };
    let paths = paths(dir.path());
    let request = serde_json::to_vec(&json!({
        "id": "1",
        "method": "agent.prompt",
        "params": {"target": "w1:p1", "text": "hi"}
    }))
    .unwrap();
    let mut input = request;
    input.push(b'\n');
    let mut output = Vec::new();
    herdr::serve(
        &grant(Role::Observer),
        &paths,
        &herdr_session(),
        &mut Cursor::new(input),
        &mut output,
    )
    .unwrap();
    unsafe { std::env::remove_var("LUVIA_HERDR_BIN") };
    let frame = std::str::from_utf8(&output[..output.len() - 1]).unwrap();
    let value: Value = serde_json::from_str(frame).unwrap();
    assert_eq!(value["id"], "1");
    assert_eq!(value["error"]["code"], "forbidden");
}

#[test]
fn herdr_unknown_key_is_invalid_params() {
    assert_eq!(
        herdr::translate_key("not-a-key").unwrap_err().code,
        "invalid_params"
    );
    assert_eq!(herdr::translate_key("ctrl-c").unwrap(), "ctrl+c");
    assert_eq!(herdr::translate_key("esc").unwrap(), "esc");
}

#[test]
#[ignore]
fn live_snapshot_from_real_herdr_pane_list() {
    let bin = PathBuf::from("/opt/homebrew/bin/herdr");
    if !bin.is_file() {
        return;
    }
    let sessions = herdr::discover_with_bin(&bin);
    let herdr = sessions
        .iter()
        .find(|session| session.name == "herdr" && session.backend == Backend::Herdr)
        .expect("live herdr session");
    assert!(herdr.running);
    eprintln!("{}", serde_json::to_string(&herdr.metadata()).unwrap());
    let output = std::process::Command::new(&bin)
        .args(["pane", "list"])
        .output()
        .expect("herdr pane list");
    if !output.status.success() {
        return;
    }
    let panes: Value = serde_json::from_slice(&output.stdout).unwrap_or(json!({}));
    let workspaces = std::process::Command::new(&bin)
        .args(["workspace", "list"])
        .output()
        .ok()
        .and_then(|out| serde_json::from_slice(&out.stdout).ok())
        .unwrap_or(json!({}));
    let agents = std::process::Command::new(&bin)
        .args(["agent", "list"])
        .output()
        .ok()
        .and_then(|out| serde_json::from_slice(&out.stdout).ok())
        .unwrap_or(json!({}));
    let snapshot = herdr::translate_snapshot("herdr", &workspaces, &panes, &agents);
    assert_eq!(snapshot["type"], "session_snapshot");
    assert!(snapshot["workspaces"].is_array());
    eprintln!("{}", snapshot);
}
