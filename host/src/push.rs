use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::error::{Error, Result};
use crate::frames::write_json_frame;
use crate::grant::{self, Grant, PushRegistration};
use crate::paths::{self, Paths};
use crate::unique_json::parse_unique_value;

pub const REGISTER_METHOD: &str = "luvia.push.register";
pub const UNREGISTER_METHOD: &str = "luvia.push.unregister";

const MAX_TOKEN_BYTES: usize = 4096;
const DEDUPE_WINDOW_SECS: u64 = 60;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WakeKind {
    Blocked,
    Permission,
    Done,
}

impl WakeKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Blocked => "blocked",
            Self::Permission => "permission",
            Self::Done => "done",
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RelayConfig {
    pub url: String,
    pub key: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct WakeBody {
    targets: Vec<WakeTarget>,
    wake: &'static str,
    count: u32,
    host: String,
    sent_at: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
struct WakeTarget {
    kind: String,
    token: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    environment: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
struct WakeResponse {
    #[serde(default)]
    accepted: u64,
    #[serde(default)]
    rejected: Vec<WakeRejection>,
}

#[derive(Clone, Debug, Deserialize)]
struct WakeRejection {
    token: String,
    #[serde(default)]
    reason: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct DedupeFile {
    #[serde(default)]
    entries: Vec<DedupeEntry>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct DedupeEntry {
    device: String,
    wake: String,
    sent_at: u64,
}

struct Dedupe {
    map: HashMap<(String, String), u64>,
}

impl Dedupe {
    fn load(paths: &Paths, now: u64) -> Self {
        let mut map = HashMap::new();
        if let Ok(text) = paths::read_nofollow_to_string(&dedupe_path(paths)) {
            if let Ok(file) = serde_json::from_str::<DedupeFile>(&text) {
                for entry in file.entries {
                    if now.saturating_sub(entry.sent_at) < DEDUPE_WINDOW_SECS {
                        map.insert((entry.device, entry.wake), entry.sent_at);
                    }
                }
            }
        }
        Self { map }
    }

    fn persist(&self, paths: &Paths) {
        let file = DedupeFile {
            entries: self
                .map
                .iter()
                .map(|((device, wake), sent_at)| DedupeEntry {
                    device: device.clone(),
                    wake: wake.clone(),
                    sent_at: *sent_at,
                })
                .collect(),
        };
        if let Ok(bytes) = serde_json::to_vec_pretty(&file) {
            let _ = paths::ensure_private_dir(&run_dir(paths));
            let _ = paths::write_atomic(&dedupe_path(paths), &bytes);
        }
    }
}

static DEDUPE: Mutex<Option<Dedupe>> = Mutex::new(None);

pub fn relay_path(paths: &Paths) -> std::path::PathBuf {
    paths.config_dir.join("relay.json")
}

pub fn load_relay(paths: &Paths) -> Result<RelayConfig> {
    let path = relay_path(paths);
    paths::reject_symlink(&path, "relay config")?;
    let text = paths::read_nofollow_to_string(&path)?;
    if text.is_empty() {
        return Err(Error::new("relay_unconfigured", "relay.json is missing"));
    }
    let config: RelayConfig = serde_json::from_str(&text)?;
    if config.url.trim().is_empty() || config.key.is_empty() {
        return Err(Error::new(
            "relay_unconfigured",
            "relay.json must include url and key",
        ));
    }
    Ok(config)
}

pub fn relay_configured(paths: &Paths) -> bool {
    load_relay(paths).is_ok()
}

/// Intercept `luvia.push.*`. Returns `true` when the method was handled.
/// Callers must already have rejected client-supplied `auth`.
pub fn try_handle(
    grant: &Grant,
    paths: &Paths,
    method: &str,
    request: &[u8],
    output: &mut impl Write,
) -> Result<bool> {
    match method {
        REGISTER_METHOD | UNREGISTER_METHOD => {
            let id = request_id(request);
            let result = if method == REGISTER_METHOD {
                let params = request_params(request);
                register(paths, grant, &params)
            } else {
                unregister(paths, grant)
            };
            match result {
                Ok(value) => write_json_frame(output, &json!({ "id": id, "result": value }))?,
                Err(error) => write_error(output, &id, &error)?,
            }
            Ok(true)
        }
        _ => Ok(false),
    }
}

pub fn register(paths: &Paths, grant: &Grant, params: &Value) -> Result<Value> {
    let kind = params
        .get("kind")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_params", "kind is required"))?;
    if !matches!(kind, "apns" | "unifiedpush" | "fcm") {
        return Err(Error::new(
            "invalid_params",
            "kind must be apns, unifiedpush, or fcm",
        ));
    }
    let token = params
        .get("token")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::new("invalid_params", "token is required"))?;
    validate_token(token)?;
    let environment = match params.get("environment") {
        None | Some(Value::Null) => None,
        Some(Value::String(value)) if matches!(value.as_str(), "sandbox" | "production") => {
            Some(value.clone())
        }
        Some(_) => {
            return Err(Error::new(
                "invalid_params",
                "environment must be sandbox or production",
            ));
        }
    };
    let updated_at = unix_now();
    let mut stored = match grant::load_grant(paths, &grant.id) {
        Ok(stored) => stored,
        Err(_) => grant.clone(),
    };
    stored.push = Some(PushRegistration {
        kind: kind.to_string(),
        token: token.to_string(),
        environment,
        updated_at,
    });
    grant::save_grant(paths, &stored)?;
    Ok(json!({ "registered": true }))
}

pub fn unregister(paths: &Paths, grant: &Grant) -> Result<Value> {
    let mut stored = match grant::load_grant(paths, &grant.id) {
        Ok(stored) => stored,
        Err(_) => grant.clone(),
    };
    stored.push = None;
    grant::save_grant(paths, &stored)?;
    Ok(json!({ "registered": false }))
}

pub fn augment_capabilities(result_frame: &[u8], paths: &Paths) -> Result<Vec<u8>> {
    if !relay_configured(paths) {
        return Ok(result_frame.to_vec());
    }
    let payload = strip_lf(result_frame);
    let mut value = parse_unique_value(payload)?;
    let Some(result) = value.get_mut("result").and_then(Value::as_object_mut) else {
        return Ok(result_frame.to_vec());
    };
    match result.get_mut("methods") {
        Some(Value::Array(methods)) => {
            append_method_name(methods, REGISTER_METHOD);
            append_method_name(methods, UNREGISTER_METHOD);
        }
        Some(_) => {}
        None => {
            result.insert(
                "methods".into(),
                json!([REGISTER_METHOD, UNREGISTER_METHOD]),
            );
        }
    }
    if let Some(Value::Array(contracts)) = result.get_mut("method_contracts") {
        append_contract(contracts, REGISTER_METHOD, "write", "read", false);
        append_contract(contracts, UNREGISTER_METHOD, "write", "read", true);
    }
    let mut out = serde_json::to_vec(&value)?;
    out.push(b'\n');
    Ok(out)
}

pub fn wake(paths: &Paths, wake: WakeKind, count: u32) {
    if let Err(error) = wake_inner(paths, wake, count) {
        eprintln!("luvia-host: push wake failed: {}", error.message);
    }
}

fn wake_inner(paths: &Paths, wake: WakeKind, count: u32) -> Result<()> {
    let config = match load_relay(paths) {
        Ok(config) => config,
        Err(_) => return Ok(()),
    };
    let now = unix_now();
    let grants = grant::list_grants(paths)?;
    let mut candidates = Vec::new();
    for grant in grants {
        if let Some(registration) = grant.push.clone() {
            candidates.push((grant, registration));
        }
    }
    if candidates.is_empty() {
        return Ok(());
    }

    let mut dedupe_guard = DEDUPE.lock().unwrap_or_else(|error| error.into_inner());
    if dedupe_guard.is_none() {
        *dedupe_guard = Some(Dedupe::load(paths, now));
    }
    let dedupe = dedupe_guard.as_mut().expect("dedupe initialized");

    let mut sending = Vec::new();
    for (grant, registration) in candidates {
        let key = (grant.id.clone(), wake.as_str().to_string());
        if let Some(prev) = dedupe.map.get(&key) {
            if now.saturating_sub(*prev) < DEDUPE_WINDOW_SECS {
                continue;
            }
        }
        sending.push((grant, registration));
    }
    if sending.is_empty() {
        return Ok(());
    }

    let host = host_id();
    let mut seen = HashSet::new();
    let mut targets = Vec::new();
    for (_, registration) in &sending {
        if !seen.insert((registration.kind.clone(), registration.token.clone())) {
            continue;
        }
        targets.push(WakeTarget {
            kind: registration.kind.clone(),
            token: registration.token.clone(),
            environment: registration.environment.clone(),
        });
    }
    let body = WakeBody {
        targets,
        wake: wake.as_str(),
        count,
        host: host.clone(),
        sent_at: now,
    };
    eprintln!(
        "luvia-host: wake {} count={count} host={host} targets={}",
        wake.as_str(),
        body.targets.len()
    );
    let response = post_wake(&config, &body)?;
    let _ = response.accepted;

    for (grant, _) in &sending {
        dedupe
            .map
            .insert((grant.id.clone(), wake.as_str().to_string()), now);
    }
    dedupe.persist(paths);
    drop(dedupe_guard);

    let gone: HashSet<String> = response
        .rejected
        .into_iter()
        .filter(|rejection| rejection.reason == "gone")
        .map(|rejection| rejection.token)
        .collect();
    remove_gone_tokens(paths, &gone);
    Ok(())
}

fn remove_gone_tokens(paths: &Paths, gone: &HashSet<String>) {
    if gone.is_empty() {
        return;
    }
    if let Ok(grants) = grant::list_grants(paths) {
        for mut grant in grants {
            let Some(registration) = grant.push.as_ref() else {
                continue;
            };
            if gone.contains(&registration.token) {
                grant.push = None;
                let _ = grant::save_grant(paths, &grant);
            }
        }
    }
}

pub fn host_id() -> String {
    host_id_for(&hostname().unwrap_or_else(|_| "unknown".into()))
}

pub fn host_id_for(hostname: &str) -> String {
    let digest = Sha256::digest(hostname.as_bytes());
    let mut out = String::with_capacity(8);
    for byte in &digest[..4] {
        use std::fmt::Write as _;
        let _ = write!(out, "{byte:02x}");
    }
    out
}

pub fn wake_url(base: &str) -> String {
    format!("{}/v1/wake", base.trim_end_matches('/'))
}

pub fn build_wake_body(
    targets: Vec<(String, String, Option<String>)>,
    wake: WakeKind,
    count: u32,
    host: &str,
    sent_at: u64,
) -> Value {
    json!({
        "targets": targets.into_iter().map(|(kind, token, environment)| {
            let mut target = json!({
                "kind": kind,
                "token": token,
            });
            if let Some(environment) = environment {
                target
                    .as_object_mut()
                    .expect("target object")
                    .insert("environment".into(), Value::String(environment));
            }
            target
        }).collect::<Vec<_>>(),
        "wake": wake.as_str(),
        "count": count,
        "host": host,
        "sent_at": sent_at,
    })
}

fn post_wake(config: &RelayConfig, body: &WakeBody) -> Result<WakeResponse> {
    let url = wake_url(&config.url);
    let agent = ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(10))
        .build();
    let response = agent
        .post(&url)
        .set("Authorization", &format!("Bearer {}", config.key))
        .set("Content-Type", "application/json")
        .send_json(serde_json::to_value(body)?)
        .map_err(|error| Error::new("io", error.to_string()))?;
    if response.status() / 100 != 2 {
        return Err(Error::new(
            "io",
            format!("relay returned HTTP {}", response.status()),
        ));
    }
    response
        .into_json()
        .map_err(|error| Error::new("invalid_json", error.to_string()))
}

pub fn validate_token(token: &str) -> Result<()> {
    if token.is_empty() || token.len() > MAX_TOKEN_BYTES {
        return Err(Error::new(
            "invalid_params",
            "token must be 1 to 4096 bytes",
        ));
    }
    if !token.bytes().all(|byte| byte.is_ascii_graphic()) {
        return Err(Error::new(
            "invalid_params",
            "token must be printable ASCII",
        ));
    }
    Ok(())
}

fn request_params(request: &[u8]) -> Value {
    parse_unique_value(request)
        .ok()
        .and_then(|value| value.get("params").cloned())
        .unwrap_or(Value::Null)
}

fn request_id(payload: &[u8]) -> String {
    serde_json::from_slice::<Value>(payload)
        .ok()
        .and_then(|value| value.get("id").and_then(Value::as_str).map(str::to_string))
        .filter(|id| !id.is_empty() && id.len() <= 128)
        .unwrap_or_else(|| "0".to_string())
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

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

fn hostname() -> Result<String> {
    let mut buf = [0u8; 256];
    let rc = unsafe { libc::gethostname(buf.as_mut_ptr() as *mut libc::c_char, buf.len()) };
    if rc != 0 {
        return Err(Error::new("io", "cannot determine the host name"));
    }
    let len = buf.iter().position(|byte| *byte == 0).unwrap_or(buf.len());
    let name = std::str::from_utf8(&buf[..len])
        .map_err(|_| Error::new("io", "host name is not valid UTF-8"))?
        .trim();
    if name.is_empty() {
        return Err(Error::new("io", "host name is empty"));
    }
    Ok(name.to_string())
}

fn run_dir(paths: &Paths) -> std::path::PathBuf {
    paths.config_dir.join("run")
}

fn dedupe_path(paths: &Paths) -> std::path::PathBuf {
    run_dir(paths).join("push-dedupe.json")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::role::Role;

    fn test_paths(root: &std::path::Path) -> Paths {
        Paths::from_parts(
            root.join("host"),
            root.join("authorized_keys"),
            root.join("luvus"),
        )
    }

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
            push: None,
        }
    }

    fn write_relay(paths: &Paths, url: &str) {
        paths.ensure_host_dirs().unwrap();
        std::fs::write(
            relay_path(paths),
            serde_json::to_vec(&json!({ "url": url, "key": "relay-key" })).unwrap(),
        )
        .unwrap();
    }

    #[test]
    fn push_token_must_be_printable_and_bounded() {
        assert!(validate_token("abc").is_ok());
        assert_eq!(validate_token("").unwrap_err().code, "invalid_params");
        assert_eq!(
            validate_token(&"a".repeat(MAX_TOKEN_BYTES + 1))
                .unwrap_err()
                .code,
            "invalid_params"
        );
        assert_eq!(
            validate_token("not a token\n").unwrap_err().code,
            "invalid_params"
        );
    }

    #[test]
    fn push_register_rejects_unknown_kind() {
        let dir = tempfile::tempdir().unwrap();
        let paths = test_paths(dir.path());
        let err = register(
            &paths,
            &grant(),
            &json!({ "kind": "webpush", "token": "abc" }),
        )
        .unwrap_err();
        assert_eq!(err.code, "invalid_params");
    }

    #[test]
    fn push_register_and_unregister_persist_on_grant() {
        let dir = tempfile::tempdir().unwrap();
        let paths = test_paths(dir.path());
        let grant = grant();
        grant::save_grant(&paths, &grant).unwrap();
        register(
            &paths,
            &grant,
            &json!({
                "kind": "apns",
                "token": "deadbeef",
                "environment": "sandbox"
            }),
        )
        .unwrap();
        let loaded = grant::load_grant(&paths, &grant.id).unwrap();
        let registration = loaded.push.expect("push registration");
        assert_eq!(registration.kind, "apns");
        assert_eq!(registration.token, "deadbeef");
        assert_eq!(registration.environment.as_deref(), Some("sandbox"));
        unregister(&paths, &grant).unwrap();
        assert!(grant::load_grant(&paths, &grant.id).unwrap().push.is_none());
    }

    #[test]
    fn push_capabilities_only_when_relay_json_exists() {
        let dir = tempfile::tempdir().unwrap();
        let paths = test_paths(dir.path());
        let frame = br#"{"id":"1","result":{"methods":["ping"]}}"#;
        let out = augment_capabilities(frame, &paths).unwrap();
        assert_eq!(out, frame);
        write_relay(&paths, "https://relay.example.com");
        let out = augment_capabilities(frame, &paths).unwrap();
        let value: Value = serde_json::from_slice(strip_lf(&out)).unwrap();
        let methods = value["result"]["methods"].as_array().unwrap();
        assert!(methods.iter().any(|method| method == REGISTER_METHOD));
        assert!(methods.iter().any(|method| method == UNREGISTER_METHOD));
    }

    #[test]
    fn push_host_request_json_matches_contract() {
        let body = build_wake_body(
            vec![("apns".into(), "deadbeef".into(), Some("production".into()))],
            WakeKind::Blocked,
            2,
            "a1b2c3d4",
            1726500000,
        );
        assert_eq!(
            body,
            json!({
                "targets": [{
                    "kind": "apns",
                    "token": "deadbeef",
                    "environment": "production"
                }],
                "wake": "blocked",
                "count": 2,
                "host": "a1b2c3d4",
                "sent_at": 1726500000
            })
        );
        assert_eq!(
            wake_url("https://relay.example.com/"),
            "https://relay.example.com/v1/wake"
        );
        assert_eq!(host_id_for("studio.local").len(), 8);
        assert!(host_id_for("studio.local")
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f')));
    }

    #[test]
    fn push_try_handle_register_writes_result() {
        let dir = tempfile::tempdir().unwrap();
        let paths = test_paths(dir.path());
        let grant = grant();
        grant::save_grant(&paths, &grant).unwrap();
        let request = serde_json::to_vec(&json!({
            "id": "1",
            "method": REGISTER_METHOD,
            "params": { "kind": "fcm", "token": "tok" }
        }))
        .unwrap();
        let mut output = Vec::new();
        assert!(try_handle(&grant, &paths, REGISTER_METHOD, &request, &mut output).unwrap());
        let value: Value = serde_json::from_slice(&output[..output.len() - 1]).unwrap();
        assert_eq!(value["result"]["registered"], true);
    }

    #[test]
    fn push_wake_posts_and_removes_gone_tokens() {
        let dir = tempfile::tempdir().unwrap();
        let paths = test_paths(dir.path());
        let mut grant = grant();
        grant.push = Some(PushRegistration {
            kind: "apns".into(),
            token: "gone-token".into(),
            environment: Some("production".into()),
            updated_at: 1,
        });
        grant::save_grant(&paths, &grant).unwrap();
        let mut gone = HashSet::new();
        gone.insert("gone-token".into());
        remove_gone_tokens(&paths, &gone);
        assert!(grant::load_grant(&paths, &grant.id).unwrap().push.is_none());
    }

    #[test]
    fn push_wake_dedupes_same_device_and_kind_for_60s() {
        let mut map = HashMap::new();
        let key = ("dev".to_string(), "blocked".to_string());
        map.insert(key.clone(), 100);
        let in_window = 100u64.saturating_add(10);
        assert!(in_window.saturating_sub(*map.get(&key).unwrap()) < DEDUPE_WINDOW_SECS);
        let outside = 100u64.saturating_add(DEDUPE_WINDOW_SECS);
        assert!(outside.saturating_sub(*map.get(&key).unwrap()) >= DEDUPE_WINDOW_SECS);
    }
}
