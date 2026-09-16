use std::collections::HashMap;
use std::io::BufReader;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;


use serde_json::{json, Value};

use crate::discovery::{self, Backend, DiscoveredSession};

use crate::endpoint::Evidence;
use crate::error::{Error, Result};
use crate::frames::{self, FrameError};
use crate::paths::Paths;
use crate::push::{self, WakeKind};
use crate::role::Role;
use crate::uhp::{self, MintedToken};
use crate::unique_json::parse_unique_value;

static STOP: AtomicBool = AtomicBool::new(false);

const BACKOFF_MIN: Duration = Duration::from_secs(2);
const BACKOFF_MAX: Duration = Duration::from_secs(60);

pub fn run(paths: &Paths, interval_secs: u64) -> Result<()> {
    install_stop_handlers();
    let interval = Duration::from_secs(interval_secs.max(1));
    let mut backoff = BACKOFF_MIN;
    eprintln!("luvia-host watch: starting");
    while !stopped() {
        if let Some(session) = luvus_session(paths) {
            match watch_luvus(paths, &session) {
                Ok(()) => backoff = BACKOFF_MIN,
                Err(error) => {
                    eprintln!(
                        "luvia-host watch: Luvus disconnected ({}); backing off {}s",
                        error.message,
                        backoff.as_secs()
                    );
                }
            }
            if stopped() {
                break;
            }
            if luvus_session(paths).is_some() {
                sleep_interruptible(backoff);
                backoff = capped_backoff(backoff);
            }
            continue;
        }
        if herdr_on_path() {
            backoff = BACKOFF_MIN;
            eprintln!("luvia-host watch: Luvus not running; polling herdr agent list");
            watch_herdr(paths, interval)?;
            continue;
        }
        eprintln!(
            "luvia-host watch: waiting for Luvus (backing off {}s)",
            backoff.as_secs()
        );
        sleep_interruptible(backoff);
        backoff = capped_backoff(backoff);
    }
    eprintln!("luvia-host watch: stopped");
    Ok(())
}

fn watch_luvus(paths: &Paths, session: &DiscoveredSession) -> Result<()> {
    let session_token = uhp::mint_token(
        &session.address,
        session.evidence,
        Role::session_scopes(),
    )?;
    let action_token = match uhp::mint_token(
        &session.address,
        session.evidence,
        Role::Controller.action_scopes(),
    ) {
        Ok(token) => token,
        Err(error) => {
            let _ = uhp::revoke_token(&session.address, session.evidence, &session_token.id);
            return Err(error);
        }
    };
    let outcome = watch_luvus_with_tokens(paths, session, &session_token, &action_token);
    let _ = uhp::revoke_token(&session.address, session.evidence, &session_token.id);
    let _ = uhp::revoke_token(&session.address, session.evidence, &action_token.id);
    outcome
}

fn watch_luvus_with_tokens(
    paths: &Paths,
    session: &DiscoveredSession,
    session_token: &MintedToken,
    action_token: &MintedToken,
) -> Result<()> {
    let mut statuses = seed_statuses(session, session_token, action_token);
    let request = json!({
        "id": "luvia-watch-subscribe",
        "method": "events.subscribe",
        "params": {},
        "auth": session_token.secret,
    });
    let payload = serde_json::to_vec(&request)?;
    let stream = uhp::open_request_stream(&session.address, session.evidence, &payload)?;
    let _ = stream.set_read_timeout(Some(Duration::from_millis(500)));
    let mut reader = BufReader::new(stream);
    let mut saw_ack = false;
    while !stopped() {
        match frames::read_frame(&mut reader) {
            Ok(frame) => {
                let payload = strip_lf(&frame);
                let Ok(value) = parse_unique_value(payload) else {
                    continue;
                };
                if !saw_ack {
                    if value.get("error").is_some() {
                        let message = value
                            .get("error")
                            .and_then(|error| error.get("message"))
                            .and_then(Value::as_str)
                            .unwrap_or("events.subscribe failed");
                        return Err(Error::new("backend_error", message));
                    }
                    saw_ack = true;
                    continue;
                }
                if value.get("event").and_then(Value::as_str)
                    == Some("events.resync_required")
                {
                    return Ok(());
                }
                handle_bus_event(paths, session, action_token, &mut statuses, &value);
            }
            Err(FrameError::Timeout) => continue,
            Err(FrameError::Eof) | Err(FrameError::MissingLf) => return Ok(()),
            Err(error) => return Err(error.into_error("events")),
        }
    }
    Ok(())
}

fn handle_bus_event(
    paths: &Paths,
    session: &DiscoveredSession,
    action_token: &MintedToken,
    statuses: &mut HashMap<String, String>,
    value: &Value,
) {
    let Some(event) = value.get("event").and_then(Value::as_str) else {
        return;
    };
    let data = value.get("data").cloned().unwrap_or(Value::Null);
    match event {
        "pane.agent_status_changed" => {
            if apply_status_event(statuses, &data) {
                let count = blocked_count(statuses);
                push::wake(paths, WakeKind::Blocked, count);
            }
        }
        "task.done" => {
            if let Some(count) = tasks_all_done(session, action_token) {
                push::wake(paths, WakeKind::Done, count);
            }
        }
        _ => {}
    }
}

fn seed_statuses(
    session: &DiscoveredSession,
    session_token: &MintedToken,
    action_token: &MintedToken,
) -> HashMap<String, String> {
    if let Ok(snapshot) = unary(
        &session.address,
        session.evidence,
        &session_token.secret,
        "session.snapshot",
        json!({}),
    ) {
        let map = statuses_from_snapshot(&snapshot);
        if !map.is_empty() {
            return map;
        }
    }
    match unary(
        &session.address,
        session.evidence,
        &action_token.secret,
        "agent.list",
        json!({}),
    ) {
        Ok(list) => statuses_from_agents(&list),
        Err(_) => HashMap::new(),
    }
}

fn tasks_all_done(session: &DiscoveredSession, action_token: &MintedToken) -> Option<u32> {
    let value = unary(
        &session.address,
        session.evidence,
        &action_token.secret,
        "task.list",
        json!({}),
    )
    .ok()?;
    all_tasks_done(task_values(&value))
}


fn watch_herdr(paths: &Paths, interval: Duration) -> Result<()> {
    let mut prev: HashMap<String, String> = HashMap::new();
    let mut seeded = false;
    while !stopped() {
        if luvus_session(paths).is_some() {
            return Ok(());
        }
        match herdr_agent_statuses() {
            Ok(map) => {
                if seeded {
                    let newly_blocked = map.iter().any(|(id, status)| {
                        status == "blocked"
                            && prev.get(id).map(String::as_str).unwrap_or("") != "blocked"
                    });
                    if newly_blocked {
                        push::wake(paths, WakeKind::Blocked, blocked_count(&map));
                    }
                }
                prev = map;
                seeded = true;
            }
            Err(error) => {
                eprintln!("luvia-host watch: herdr agent list failed: {}", error.message);
            }
        }
        sleep_interruptible(interval);
    }
    Ok(())
}

fn herdr_agent_statuses() -> Result<HashMap<String, String>> {
    let output = Command::new("herdr")
        .args(["agent", "list"])
        .output()
        .map_err(|error| Error::new("backend_error", error.to_string()))?;
    if !output.status.success() {
        let message = String::from_utf8_lossy(&output.stderr);
        return Err(Error::new(
            "backend_error",
            message.trim().to_string(),
        ));
    }
    let value: Value = serde_json::from_slice(&output.stdout)
        .map_err(|_| Error::new("backend_error", "herdr returned non-JSON"))?;
    Ok(statuses_from_agents(&value))
}

fn unary(
    path: &Path,
    evidence: Evidence,
    token: &str,
    method: &str,
    params: Value,
) -> Result<Value> {
    let request = json!({
        "id": format!("luvia-watch-{method}"),
        "method": method,
        "params": params,
        "auth": token,
    });
    let payload = serde_json::to_vec(&request)?;
    let stream = uhp::open_request_stream(path, evidence, &payload)?;
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let mut reader = BufReader::new(stream);
    let text = frames::read_text_frame(&mut reader, "response")?;
    let value = parse_unique_value(text.as_bytes())?;
    if let Some(error) = value.get("error") {
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("UHP request failed");
        return Err(Error::new("backend_error", message));
    }
    Ok(value)
}

pub(crate) fn apply_status_event(statuses: &mut HashMap<String, String>, data: &Value) -> bool {
    let Some(pane) = pane_id_of(data) else {
        return false;
    };
    let Some(status) = status_of(data) else {
        return false;
    };
    let previous = statuses.insert(pane.to_string(), status.to_string());
    status == "blocked" && previous.as_deref() != Some("blocked")
}

pub(crate) fn blocked_count(statuses: &HashMap<String, String>) -> u32 {
    statuses
        .values()
        .filter(|status| status.as_str() == "blocked")
        .count() as u32
}

pub(crate) fn statuses_from_snapshot(value: &Value) -> HashMap<String, String> {
    let mut map = HashMap::new();
    let root = value.get("result").unwrap_or(value);
    let Some(workspaces) = root.get("workspaces").and_then(Value::as_array) else {
        return map;
    };
    for workspace in workspaces {
        let Some(tabs) = workspace.get("tabs").and_then(Value::as_array) else {
            continue;
        };
        for tab in tabs {
            let Some(panes) = tab.get("panes").and_then(Value::as_array) else {
                continue;
            };
            for pane in panes {
                if let (Some(id), Some(status)) = (pane_id_of(pane), status_of(pane)) {
                    map.insert(id.to_string(), status.to_string());
                }
            }
        }
    }
    map
}

pub(crate) fn statuses_from_agents(value: &Value) -> HashMap<String, String> {
    let mut map = HashMap::new();
    let root = value.get("result").unwrap_or(value);
    let Some(agents) = root
        .get("agents")
        .and_then(Value::as_array)
        .or_else(|| value.get("agents").and_then(Value::as_array))
    else {
        return map;
    };
    for agent in agents {
        if let (Some(id), Some(status)) = (pane_id_of(agent), status_of(agent)) {
            map.insert(id.to_string(), status.to_string());
        }
    }
    map
}


pub(crate) fn task_values(value: &Value) -> Vec<Value> {
    let root = value.get("result").unwrap_or(value);
    root.get("tasks")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

pub(crate) fn all_tasks_done(tasks: Vec<Value>) -> Option<u32> {
    if tasks.is_empty() {
        return None;
    }
    if tasks.iter().all(|task| {
        task.get("status").and_then(Value::as_str) == Some("done")
    }) {
        Some(tasks.len() as u32)
    } else {
        None
    }
}

fn pane_id_of(value: &Value) -> Option<&str> {
    value
        .get("pane")
        .and_then(Value::as_str)
        .or_else(|| value.get("pane_id").and_then(Value::as_str))
        .or_else(|| value.get("id").and_then(Value::as_str))
}

fn status_of(value: &Value) -> Option<&str> {
    value
        .get("status")
        .and_then(Value::as_str)
        .or_else(|| value.get("agent_status").and_then(Value::as_str))
}

fn luvus_session(paths: &Paths) -> Option<DiscoveredSession> {
    let sessions = discovery::discover_running(paths).ok()?;
    sessions
        .into_iter()
        .filter(|session| session.backend == Backend::Luvus)
        .find(|session| session.default)
        .or_else(|| {
            discovery::discover_running(paths)
                .ok()?
                .into_iter()
                .find(|session| session.backend == Backend::Luvus)
        })
}

fn herdr_on_path() -> bool {
    let Some(path) = std::env::var_os("PATH") else {
        return false;
    };
    std::env::split_paths(&path).any(|dir| {
        let candidate = dir.join("herdr");
        candidate.is_file()
    })
}

fn capped_backoff(current: Duration) -> Duration {
    current.saturating_mul(2).min(BACKOFF_MAX)
}

fn sleep_interruptible(total: Duration) {
    let slice = Duration::from_millis(200);
    let start = std::time::Instant::now();
    while start.elapsed() < total && !stopped() {
        let remaining = total.saturating_sub(start.elapsed());
        std::thread::sleep(remaining.min(slice));
    }
}

fn stopped() -> bool {
    STOP.load(Ordering::SeqCst)
}

fn install_stop_handlers() {
    extern "C" fn handle_stop(_: libc::c_int) {
        STOP.store(true, Ordering::SeqCst);
    }
    unsafe {
        libc::signal(libc::SIGINT, handle_stop as *const () as libc::sighandler_t);
        libc::signal(libc::SIGTERM, handle_stop as *const () as libc::sighandler_t);
    }
}


fn strip_lf(frame: &[u8]) -> &[u8] {
    frame.strip_suffix(b"\n").unwrap_or(frame)
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_watch_counts_blocked_and_new_transitions() {
        let mut statuses = HashMap::new();
        statuses.insert("7".into(), "idle".into());
        statuses.insert("8".into(), "working".into());
        assert_eq!(blocked_count(&statuses), 0);
        assert!(apply_status_event(
            &mut statuses,
            &json!({ "pane": "7", "status": "blocked" })
        ));
        assert_eq!(blocked_count(&statuses), 1);
        assert!(!apply_status_event(
            &mut statuses,
            &json!({ "pane": "7", "status": "blocked" })
        ));
        assert!(apply_status_event(
            &mut statuses,
            &json!({ "pane": "8", "status": "blocked" })
        ));
        assert_eq!(blocked_count(&statuses), 2);
    }

    #[test]
    fn push_watch_seeds_from_snapshot_and_agent_list() {
        let snapshot = json!({
            "result": {
                "workspaces": [{
                    "tabs": [{
                        "panes": [
                            { "pane_id": "7", "agent_status": "blocked" },
                            { "pane_id": "8", "agent_status": "idle" }
                        ]
                    }]
                }]
            }
        });
        let map = statuses_from_snapshot(&snapshot);
        assert_eq!(blocked_count(&map), 1);
        let agents = json!({
            "result": {
                "type": "agent_list",
                "agents": [
                    { "pane": "w1:p1", "agent_status": "blocked" },
                    { "id": "w1:p2", "status": "idle" }
                ]
            }
        });
        let map = statuses_from_agents(&agents);
        assert_eq!(blocked_count(&map), 1);
        assert!(map.contains_key("w1:p1"));
    }

    #[test]
    fn push_watch_done_requires_nonempty_all_done_board() {
        assert_eq!(all_tasks_done(vec![]), None);
        assert_eq!(
            all_tasks_done(vec![json!({"id":"t1","status":"done"})]),
            Some(1)
        );
        assert_eq!(
            all_tasks_done(vec![
                json!({"id":"t1","status":"done"}),
                json!({"id":"t2","status":"running"})
            ]),
            None
        );
        let listed = json!({"result":{"type":"task_list","tasks":[{"id":"t1","status":"done"}]}});
        assert_eq!(all_tasks_done(task_values(&listed)), Some(1));
    }

    #[test]
    fn push_watch_backoff_caps_at_sixty_seconds() {
        assert_eq!(capped_backoff(Duration::from_secs(2)), Duration::from_secs(4));
        assert_eq!(
            capped_backoff(Duration::from_secs(32)),
            Duration::from_secs(60)
        );
        assert_eq!(
            capped_backoff(Duration::from_secs(60)),
            Duration::from_secs(60)
        );
    }
}
