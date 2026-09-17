use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use axum::Router;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use jsonwebtoken::{Algorithm, EncodingKey, Header, encode};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::Mutex;

pub const APNS_JWT_TTL: Duration = Duration::from_secs(50 * 60);

#[derive(Clone, Debug)]
pub struct Config {
    pub listen: String,
    pub keys: Vec<String>,
    pub apns: Option<ApnsConfig>,
    pub fcm: Option<FcmConfig>,
}

#[derive(Clone, Debug)]
pub struct ApnsConfig {
    pub team_id: String,
    pub key_id: String,
    pub key_pem: String,
    pub topic: String,
}

#[derive(Clone, Debug)]
pub struct FcmConfig {
    pub project_id: String,
    pub client_email: String,
    pub private_key: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WakeRequest {
    pub targets: Vec<WakeTarget>,
    pub wake: String,
    pub count: u32,
    pub host: String,
    pub sent_at: u64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WakeTarget {
    pub kind: String,
    pub token: String,
    #[serde(default)]
    pub environment: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct WakeResponse {
    pub accepted: u32,
    pub rejected: Vec<WakeRejection>,
}

#[derive(Clone, Debug, Serialize)]
pub struct WakeRejection {
    pub token: String,
    pub reason: String,
}

pub struct AppState {
    pub config: Config,
    pub http: reqwest::Client,
    apns_jwt: Mutex<Option<(String, Instant)>>,
    fcm_token: Mutex<Option<(String, Instant)>>,
}

impl AppState {
    pub fn new(config: Config) -> Result<Self, String> {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|error| error.to_string())?;
        Ok(Self {
            config,
            http,
            apns_jwt: Mutex::new(None),
            fcm_token: Mutex::new(None),
        })
    }
}

impl Config {
    pub fn from_env() -> Result<Self, String> {
        let listen =
            std::env::var("LUVIA_RELAY_LISTEN").unwrap_or_else(|_| "0.0.0.0:8787".to_string());
        let keys = std::env::var("LUVIA_RELAY_KEYS").map_err(|_| {
            "LUVIA_RELAY_KEYS is required (comma-separated bearer keys)".to_string()
        })?;
        let keys: Vec<String> = keys
            .split(',')
            .map(str::trim)
            .filter(|key| !key.is_empty())
            .map(str::to_string)
            .collect();
        if keys.is_empty() {
            return Err("LUVIA_RELAY_KEYS must contain at least one key".into());
        }
        let apns = match (
            std::env::var("LUVIA_RELAY_APNS_TEAM_ID").ok(),
            std::env::var("LUVIA_RELAY_APNS_KEY_ID").ok(),
            std::env::var("LUVIA_RELAY_APNS_KEY_P8").ok(),
            std::env::var("LUVIA_RELAY_APNS_TOPIC").ok(),
        ) {
            (Some(team_id), Some(key_id), Some(path), Some(topic)) => {
                let key_pem = std::fs::read_to_string(&path)
                    .map_err(|error| format!("read APNs .p8: {error}"))?;
                Some(ApnsConfig {
                    team_id,
                    key_id,
                    key_pem,
                    topic,
                })
            }
            (None, None, None, None) => None,
            _ => {
                return Err(
                    "APNs config requires TEAM_ID, KEY_ID, KEY_P8 and TOPIC together".into(),
                );
            }
        };
        let fcm = match std::env::var("LUVIA_RELAY_FCM_SERVICE_ACCOUNT").ok() {
            Some(path) => Some(load_fcm_service_account(&path)?),
            None => None,
        };
        Ok(Self {
            listen,
            keys,
            apns,
            fcm,
        })
    }
}

#[derive(Deserialize)]
struct ServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
}

fn load_fcm_service_account(path: &str) -> Result<FcmConfig, String> {
    let text = std::fs::read_to_string(path)
        .map_err(|error| format!("read FCM service account: {error}"))?;
    let account: ServiceAccount = serde_json::from_str(&text)
        .map_err(|error| format!("parse FCM service account: {error}"))?;
    Ok(FcmConfig {
        project_id: account.project_id,
        client_email: account.client_email,
        private_key: account.private_key,
    })
}

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/wake", post(wake))
        .with_state(state)
}

async fn healthz() -> &'static str {
    "ok"
}

async fn wake(State(state): State<Arc<AppState>>, headers: HeaderMap, body: Bytes) -> Response {
    if !authorized(&state.config.keys, &headers) {
        return (StatusCode::UNAUTHORIZED, "unauthorized").into_response();
    }
    let request: WakeRequest = match serde_json::from_slice(&body) {
        Ok(request) => request,
        Err(error) => {
            return (StatusCode::BAD_REQUEST, format!("invalid json: {error}")).into_response();
        }
    };
    if let Err(error) = validate_wake_request(&request) {
        return (StatusCode::BAD_REQUEST, error).into_response();
    }
    let mut accepted = 0u32;
    let mut rejected = Vec::new();
    for target in &request.targets {
        match send_target(&state, &request, target).await {
            Ok(SendResult::Accepted) => accepted += 1,
            Ok(SendResult::Gone) => rejected.push(WakeRejection {
                token: target.token.clone(),
                reason: "gone".into(),
            }),
            Ok(SendResult::Rejected(reason)) => rejected.push(WakeRejection {
                token: target.token.clone(),
                reason,
            }),
            Err(reason) => rejected.push(WakeRejection {
                token: target.token.clone(),
                reason,
            }),
        }
    }
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        serde_json::to_vec(&WakeResponse { accepted, rejected }).unwrap_or_else(|_| b"{}".to_vec()),
    )
        .into_response()
}

fn authorized(keys: &[String], headers: &HeaderMap) -> bool {
    let Some(value) = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    let Some(provided) = value
        .strip_prefix("Bearer ")
        .or_else(|| value.strip_prefix("bearer "))
    else {
        return false;
    };
    keys.iter().any(|key| key == provided)
}

pub fn validate_wake_request(request: &WakeRequest) -> Result<(), String> {
    if request.targets.is_empty() {
        return Err("targets must not be empty".into());
    }
    if !matches!(request.wake.as_str(), "blocked" | "permission" | "done") {
        return Err("wake must be blocked, permission, or done".into());
    }
    if request.host.len() != 8
        || !request
            .host
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err("host must be 8 lowercase hex characters".into());
    }
    for target in &request.targets {
        if !matches!(target.kind.as_str(), "apns" | "unifiedpush" | "fcm") {
            return Err("target kind must be apns, unifiedpush, or fcm".into());
        }
        if target.token.is_empty() || target.token.len() > 4096 {
            return Err("token must be 1 to 4096 bytes".into());
        }
        if let Some(environment) = target.environment.as_deref()
            && !matches!(environment, "sandbox" | "production")
        {
            return Err("environment must be sandbox or production".into());
        }
    }
    Ok(())
}

pub fn apns_payload(wake: &str, host: &str, count: u32) -> Value {
    json!({
        "aps": {
            "alert": {
                "title-loc-key": "push.title",
                "loc-key": format!("push.{wake}"),
                "loc-args": [count.to_string()],
            },
            "sound": "default",
            "thread-id": host,
            "interruption-level": "time-sensitive",
        },
        "luvia": {
            "wake": wake,
            "host": host,
            "count": count,
        }
    })
}

pub fn apns_expiration(sent_at: u64) -> u64 {
    sent_at.saturating_add(600)
}

enum SendResult {
    Accepted,
    Gone,
    Rejected(String),
}

async fn send_target(
    state: &AppState,
    request: &WakeRequest,
    target: &WakeTarget,
) -> Result<SendResult, String> {
    match target.kind.as_str() {
        "apns" => send_apns(state, request, target).await,
        "unifiedpush" => send_unifiedpush(state, request, target).await,
        "fcm" => send_fcm(state, request, target).await,
        _ => Ok(SendResult::Rejected("unsupported_kind".into())),
    }
}

async fn send_apns(
    state: &AppState,
    request: &WakeRequest,
    target: &WakeTarget,
) -> Result<SendResult, String> {
    let Some(apns) = state.config.apns.as_ref() else {
        return Ok(SendResult::Rejected("apns_unconfigured".into()));
    };
    let jwt = apns_jwt(state, apns).await?;
    let host = if target.environment.as_deref() == Some("sandbox") {
        "https://api.sandbox.push.apple.com"
    } else {
        "https://api.push.apple.com"
    };
    let url = format!("{host}/3/device/{}", target.token);
    let payload = apns_payload(&request.wake, &request.host, request.count);
    let response = state
        .http
        .post(url)
        .header(header::AUTHORIZATION, format!("bearer {jwt}"))
        .header("apns-push-type", "alert")
        .header("apns-priority", "10")
        .header("apns-topic", &apns.topic)
        .header(
            "apns-expiration",
            apns_expiration(request.sent_at).to_string(),
        )
        .json(&payload)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    let status = response.status();
    let body = response.text().await.unwrap_or_default();
    if status.as_u16() == 410 || status.as_u16() == 404 || apns_unregistered(&body) {
        return Ok(SendResult::Gone);
    }
    if status.is_success() {
        Ok(SendResult::Accepted)
    } else {
        Ok(SendResult::Rejected(format!("apns_{status}")))
    }
}

fn apns_unregistered(body: &str) -> bool {
    body.contains("Unregistered")
}

async fn apns_jwt(state: &AppState, apns: &ApnsConfig) -> Result<String, String> {
    {
        let cache = state.apns_jwt.lock().await;
        if let Some((token, issued)) = cache.as_ref()
            && issued.elapsed() < APNS_JWT_TTL
        {
            return Ok(token.clone());
        }
    }
    let token = encode_apns_jwt(apns, unix_now())?;
    let mut cache = state.apns_jwt.lock().await;
    *cache = Some((token.clone(), Instant::now()));
    Ok(token)
}

pub fn encode_apns_jwt(apns: &ApnsConfig, iat: u64) -> Result<String, String> {
    let mut header = Header::new(Algorithm::ES256);
    header.kid = Some(apns.key_id.clone());
    header.typ = None;
    #[derive(Serialize)]
    struct Claims {
        iss: String,
        iat: u64,
    }
    let claims = Claims {
        iss: apns.team_id.clone(),
        iat,
    };
    let key = EncodingKey::from_ec_pem(apns.key_pem.as_bytes())
        .map_err(|error| format!("APNs key: {error}"))?;
    encode(&header, &claims, &key).map_err(|error| error.to_string())
}

async fn send_unifiedpush(
    state: &AppState,
    request: &WakeRequest,
    target: &WakeTarget,
) -> Result<SendResult, String> {
    let body = json!({
        "wake": request.wake,
        "host": request.host,
        "count": request.count,
    });
    let response = state
        .http
        .post(&target.token)
        .header("TTL", "600")
        .header("Urgency", "high")
        .json(&body)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    let status = response.status();
    if status.as_u16() == 404 || status.as_u16() == 410 {
        return Ok(SendResult::Gone);
    }
    if status.is_success() {
        Ok(SendResult::Accepted)
    } else {
        Ok(SendResult::Rejected(format!("unifiedpush_{status}")))
    }
}

async fn send_fcm(
    state: &AppState,
    request: &WakeRequest,
    target: &WakeTarget,
) -> Result<SendResult, String> {
    let Some(fcm) = state.config.fcm.as_ref() else {
        return Ok(SendResult::Rejected("fcm_unconfigured".into()));
    };
    let access = fcm_access_token(state, fcm).await?;
    let url = format!(
        "https://fcm.googleapis.com/v1/projects/{}/messages:send",
        fcm.project_id
    );
    let payload = json!({
        "message": {
            "token": target.token,
            "data": {
                "wake": request.wake,
                "host": request.host,
                "count": request.count.to_string(),
            },
            "android": {
                "priority": "high"
            }
        }
    });
    let response = state
        .http
        .post(url)
        .bearer_auth(access)
        .json(&payload)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    let status = response.status();
    let body = response.text().await.unwrap_or_default();
    if status.as_u16() == 404 || body.contains("UNREGISTERED") || body.contains("NOT_FOUND") {
        return Ok(SendResult::Gone);
    }
    if status.is_success() {
        Ok(SendResult::Accepted)
    } else {
        Ok(SendResult::Rejected(format!("fcm_{status}")))
    }
}

async fn fcm_access_token(state: &AppState, fcm: &FcmConfig) -> Result<String, String> {
    {
        let cache = state.fcm_token.lock().await;
        if let Some((token, issued)) = cache.as_ref()
            && issued.elapsed() < Duration::from_secs(50 * 60)
        {
            return Ok(token.clone());
        }
    }
    let assertion = encode_fcm_jwt(fcm, unix_now())?;
    let form =
        format!("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion={assertion}");
    let response = state
        .http
        .post("https://oauth2.googleapis.com/token")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body(form)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    if !response.status().is_success() {
        return Err(format!("FCM oauth HTTP {}", response.status()));
    }
    let value: Value = response.json().await.map_err(|error| error.to_string())?;
    let token = value
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or_else(|| "FCM oauth returned no access_token".to_string())?
        .to_string();
    let mut cache = state.fcm_token.lock().await;
    *cache = Some((token.clone(), Instant::now()));
    Ok(token)
}

fn encode_fcm_jwt(fcm: &FcmConfig, iat: u64) -> Result<String, String> {
    let mut header = Header::new(Algorithm::RS256);
    header.typ = Some("JWT".into());
    #[derive(Serialize)]
    struct Claims {
        iss: String,
        scope: String,
        aud: String,
        iat: u64,
        exp: u64,
    }
    let claims = Claims {
        iss: fcm.client_email.clone(),
        scope: "https://www.googleapis.com/auth/firebase.messaging".into(),
        aud: "https://oauth2.googleapis.com/token".into(),
        iat,
        exp: iat.saturating_add(3600),
    };
    let key = EncodingKey::from_rsa_pem(fcm.private_key.as_bytes())
        .map_err(|error| format!("FCM key: {error}"))?;
    encode(&header, &claims, &key).map_err(|error| error.to_string())
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    fn test_state() -> Arc<AppState> {
        Arc::new(
            AppState::new(Config {
                listen: "127.0.0.1:0".into(),
                keys: vec!["secret".into()],
                apns: None,
                fcm: None,
            })
            .unwrap(),
        )
    }

    fn sample_request() -> Value {
        json!({
            "targets": [{ "kind": "apns", "token": "deadbeef", "environment": "production" }],
            "wake": "blocked",
            "count": 2,
            "host": "a1b2c3d4",
            "sent_at": 1726500000
        })
    }

    #[test]
    fn push_wake_request_validation() {
        let valid: WakeRequest = serde_json::from_value(sample_request()).unwrap();
        assert!(validate_wake_request(&valid).is_ok());

        let mut empty = valid.clone();
        empty.targets.clear();
        assert!(
            validate_wake_request(&empty)
                .unwrap_err()
                .contains("targets")
        );

        let mut wake = serde_json::from_value::<WakeRequest>(sample_request()).unwrap();
        wake.wake = "nudge".into();
        assert!(validate_wake_request(&wake).unwrap_err().contains("wake"));

        let mut host = serde_json::from_value::<WakeRequest>(sample_request()).unwrap();
        host.host = "STUDIO".into();
        assert!(validate_wake_request(&host).unwrap_err().contains("host"));

        let mut kind = serde_json::from_value::<WakeRequest>(sample_request()).unwrap();
        kind.targets[0].kind = "webpush".into();
        assert!(validate_wake_request(&kind).unwrap_err().contains("kind"));
    }

    #[test]
    fn apns_payload_matches_contract() {
        let payload = apns_payload("blocked", "a1b2c3d4", 2);
        assert_eq!(
            payload,
            json!({
                "aps": {
                    "alert": {
                        "title-loc-key": "push.title",
                        "loc-key": "push.blocked",
                        "loc-args": ["2"]
                    },
                    "sound": "default",
                    "thread-id": "a1b2c3d4",
                    "interruption-level": "time-sensitive"
                },
                "luvia": {
                    "wake": "blocked",
                    "host": "a1b2c3d4",
                    "count": 2
                }
            })
        );
        assert_eq!(
            apns_payload("permission", "abcd1234", 1)["aps"]["alert"]["loc-key"],
            "push.permission"
        );
        assert_eq!(
            apns_payload("done", "abcd1234", 3)["aps"]["alert"]["loc-key"],
            "push.done"
        );
        assert_eq!(apns_expiration(1726500000), 1726500600);
    }

    #[tokio::test]
    async fn healthz_ok_and_wake_auth() {
        let app = router(test_state());
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), 64)
            .await
            .unwrap();
        assert_eq!(&body[..], b"ok");

        let unauth = router(test_state())
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/wake")
                    .header("content-type", "application/json")
                    .body(Body::from(sample_request().to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unauth.status(), StatusCode::UNAUTHORIZED);

        let bad = router(test_state())
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/wake")
                    .header("authorization", "Bearer secret")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"wake":"blocked"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(bad.status(), StatusCode::BAD_REQUEST);
    }
}
