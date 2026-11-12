// Copyright 2026 AsahiArt
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::sync::Arc;
use std::time::{Duration, Instant};

use luvia_transport::{
    generate_device_key, import_device_key, SshConnection, TransportError, BRIDGE_COMMAND,
    MAX_READ_BYTES,
};
use russh::keys::{HashAlg, PrivateKey, PublicKey};
use russh::server::{Auth, Handler, Msg, Session};
use russh::{server, Channel, ChannelId};

fn block_on<T>(fut: impl Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("test runtime")
        .block_on(fut)
}

fn fingerprint(key: &PrivateKey) -> String {
    format!("{}", key.public_key().fingerprint(HashAlg::Sha256))
}

#[test]
fn key_round_trip_and_redaction() {
    let generated = generate_device_key().unwrap();
    let imported = import_device_key(generated.private_key_openssh.clone()).unwrap();
    assert_eq!(imported, generated.public_key);
    assert!(imported.authorized_keys.starts_with("ssh-ed25519 "));
    let debug = format!("{generated:?}");
    assert!(debug.contains("<redacted>"));
    assert!(!debug.contains(&generated.private_key_openssh));
}

#[test]
fn pin_auth_bounds_and_concurrent_bridges() {
    block_on(async {
        let host = generate_device_key().unwrap();
        let host_key = PrivateKey::from_openssh(&host.private_key_openssh).unwrap();
        let device = generate_device_key().unwrap();
        let stranger = generate_device_key().unwrap();
        let client_pub = PublicKey::from_openssh(&device.public_key.authorized_keys).unwrap();
        let real_fp = fingerprint(&host_key);
        let wrong_host = generate_device_key().unwrap();
        let wrong_fp =
            fingerprint(&PrivateKey::from_openssh(&wrong_host.private_key_openssh).unwrap());
        let host_id = "host".to_string();
        let port = start_auth(host_key, client_pub, &host_id, None).await;

        let mismatch = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![wrong_fp.clone()],
            device.private_key_openssh.clone(),
            host_id.clone(),
            "device".into(),
        )
        .await
        .unwrap_err();
        assert!(matches!(mismatch, TransportError::HostKeyMismatch { .. }));

        let auth = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![real_fp.clone()],
            stranger.private_key_openssh,
            host_id.clone(),
            "device".into(),
        )
        .await
        .unwrap_err();
        assert_auth_rejected(auth);

        let conn = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![wrong_fp, real_fp],
            device.private_key_openssh,
            host_id,
            "device".into(),
        )
        .await
        .unwrap();

        let a = conn.open_authenticated_bridge().await.unwrap();
        let b = conn.open_authenticated_bridge().await.unwrap();
        a.write(b"alpha".to_vec()).await.unwrap();
        b.write(b"beta".to_vec()).await.unwrap();
        assert_eq!(a.read(16).await.unwrap().data, b"alpha");
        assert_eq!(b.read(16).await.unwrap().data, b"beta");

        assert!(matches!(
            a.read(0).await.unwrap_err(),
            TransportError::InvalidReadBound { max_bytes: 0 }
        ));
        assert!(matches!(
            a.read(MAX_READ_BYTES + 1).await.unwrap_err(),
            TransportError::InvalidReadBound { .. }
        ));

        a.shutdown().await.unwrap();
        a.shutdown().await.unwrap();
        conn.shutdown().await.unwrap();
    });
}

struct AuthServer {
    client_key: PublicKey,
    host_id: String,
    reject_device: Option<String>,
    reject_publickey: bool,
    pending: HashMap<ChannelId, Vec<u8>>,
    proved: HashSet<ChannelId>,
}

impl Handler for AuthServer {
    type Error = russh::Error;

    async fn auth_publickey(
        &mut self,
        _user: &str,
        public_key: &PublicKey,
    ) -> Result<Auth, Self::Error> {
        if self.reject_publickey {
            return Ok(Auth::reject());
        }
        if public_key.fingerprint(HashAlg::Sha256) == self.client_key.fingerprint(HashAlg::Sha256) {
            Ok(Auth::Accept)
        } else {
            Ok(Auth::reject())
        }
    }

    async fn channel_open_session(
        &mut self,
        _channel: Channel<Msg>,
        reply: server::ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        reply.accept().await;
        Ok(())
    }

    async fn exec_request(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        if data == BRIDGE_COMMAND.as_bytes() {
            session.channel_success(channel)?;
            session.data(channel, hello_frame(&self.host_id))?;
        } else {
            session.channel_failure(channel)?;
        }
        Ok(())
    }

    async fn data(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        if self.proved.contains(&channel) {
            session.data(channel, data.to_vec())?;
            return Ok(());
        }
        let buf = self.pending.entry(channel).or_default();
        buf.extend_from_slice(data);
        let Some(pos) = buf.iter().position(|byte| *byte == b'\n') else {
            return Ok(());
        };
        let frame: Vec<u8> = buf.drain(..=pos).collect();
        let text = String::from_utf8_lossy(&frame);
        let device = json_string_field(&text, "device_id");
        let accepted = match &device {
            Some(device) => self.reject_device.as_deref() != Some(device.as_str()),
            None => false,
        };
        let reply = match device {
            Some(device) if !accepted => {
                error_frame("unknown_device", &format!("no pairing grant for {device}"))
            }
            Some(_) => ok_frame(),
            None => error_frame("invalid_prove", "prove device_id must be a string"),
        };
        if accepted {
            self.proved.insert(channel);
        }
        session.data(channel, reply)?;
        Ok(())
    }
}

fn hello_frame(host_id: &str) -> Vec<u8> {
    format!(
        "{{\"type\":\"hello\",\"nonce\":\"ERERERERERERERERERERERERERERERERERERERERERE\",\"host_id\":\"{host_id}\"}}\n"
    )
    .into_bytes()
}

fn ok_frame() -> Vec<u8> {
    b"{\"type\":\"ok\"}\n".to_vec()
}

fn error_frame(code: &str, message: &str) -> Vec<u8> {
    format!("{{\"type\":\"error\",\"code\":\"{code}\",\"message\":\"{message}\"}}\n").into_bytes()
}

fn json_string_field(frame: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\":\"");
    let start = frame.find(&needle)? + needle.len();
    let end = frame[start..].find('"')? + start;
    Some(frame[start..end].to_string())
}

fn assert_auth_rejected(err: TransportError) {
    let shown = format!("{err}{err:?}");
    match &err {
        TransportError::AuthRejected { reason } => {
            assert!(
                !reason.is_empty(),
                "AuthRejected reason must show up in logs"
            );
            assert!(shown.contains("AuthRejected"), "{shown}");
            assert!(!shown.to_lowercase().contains("timeout"), "{shown}");
        }
        other => panic!("expected AuthRejected, got {other:?}"),
    }
}

async fn start_auth(
    host_key: PrivateKey,
    client_key: PublicKey,
    host_id: &str,
    reject_device: Option<String>,
) -> u16 {
    start_auth_with(host_key, client_key, host_id, reject_device, false).await
}

async fn start_auth_with(
    host_key: PrivateKey,
    client_key: PublicKey,
    host_id: &str,
    reject_device: Option<String>,
    reject_publickey: bool,
) -> u16 {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let port = listener.local_addr().expect("addr").port();
    let config = Arc::new(server::Config {
        auth_rejection_time: Duration::from_millis(1),
        auth_rejection_time_initial: Some(Duration::ZERO),
        inactivity_timeout: None,
        keys: vec![host_key],
        ..Default::default()
    });
    let host_id = host_id.to_string();
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = listener.accept().await else {
                break;
            };
            let config = Arc::clone(&config);
            let handler = AuthServer {
                client_key: client_key.clone(),
                host_id: host_id.clone(),
                reject_device: reject_device.clone(),
                reject_publickey,
                pending: HashMap::new(),
                proved: HashSet::new(),
            };
            tokio::spawn(async move {
                let _ = server::run_stream(config, stream, handler).await;
            });
        }
    });
    port
}

#[test]
fn second_bridge_opens_after_probe_budget_expires() {
    block_on(async {
        let host = generate_device_key().unwrap();
        let host_key = PrivateKey::from_openssh(&host.private_key_openssh).unwrap();
        let device = generate_device_key().unwrap();
        let client_pub = PublicKey::from_openssh(&device.public_key.authorized_keys).unwrap();
        let host_id = "ab".repeat(16);
        let port = start_auth(host_key, client_pub, &host_id, None).await;
        let probe_budget = Duration::from_millis(800);
        let started = Instant::now();
        let conn = SshConnection::connect_with_budgets(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![fingerprint(
                &PrivateKey::from_openssh(&host.private_key_openssh).unwrap(),
            )],
            device.private_key_openssh,
            host_id,
            "phone-1".into(),
            probe_budget,
            Duration::from_secs(5),
        )
        .await
        .unwrap();
        let first = conn.open_authenticated_bridge().await.unwrap();
        assert!(
            started.elapsed() < probe_budget,
            "probe finished late: {:?}",
            started.elapsed()
        );
        tokio::time::sleep(probe_budget).await;
        assert!(started.elapsed() > probe_budget);
        let second = conn
            .open_authenticated_bridge()
            .await
            .expect("later channel must not use the probe deadline");
        first.shutdown().await.unwrap();
        second.shutdown().await.unwrap();
        conn.shutdown().await.unwrap();
    });
}

#[test]
fn deleted_grant_bridge_fails_with_the_host_reason() {
    block_on(async {
        let host = generate_device_key().unwrap();
        let host_key = PrivateKey::from_openssh(&host.private_key_openssh).unwrap();
        let device = generate_device_key().unwrap();
        let client_pub = PublicKey::from_openssh(&device.public_key.authorized_keys).unwrap();
        let host_id = "cd".repeat(16);
        let device_id = "deleted-device";
        let port = start_auth(
            host_key.clone(),
            client_pub,
            &host_id,
            Some(device_id.into()),
        )
        .await;
        let conn = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![fingerprint(&host_key)],
            device.private_key_openssh,
            host_id,
            device_id.into(),
        )
        .await
        .unwrap();
        let opened = conn.open_authenticated_bridge().await;
        let err = match opened {
            Ok(_) => panic!("deleted grant must not win"),
            Err(err) => err,
        };
        match err {
            TransportError::ProveRejected { code, reason } => {
                assert_eq!(code, "unknown_device");
                assert_eq!(reason, format!("no pairing grant for {device_id}"));
                assert!(!reason.is_empty());
            }
            other => panic!("deleted grant must not win: {other:?}"),
        }
        conn.shutdown().await.unwrap();
    });
}

#[test]
fn verified_host_key_then_publickey_reject_is_auth_rejected() {
    block_on(async {
        let host = generate_device_key().unwrap();
        let host_key = PrivateKey::from_openssh(&host.private_key_openssh).unwrap();
        let device = generate_device_key().unwrap();
        let client_pub = PublicKey::from_openssh(&device.public_key.authorized_keys).unwrap();
        let real_fp = fingerprint(&host_key);
        let wrong_host = generate_device_key().unwrap();
        let wrong_fp =
            fingerprint(&PrivateKey::from_openssh(&wrong_host.private_key_openssh).unwrap());
        let host_id = "host".to_string();
        let port = start_auth_with(host_key, client_pub, &host_id, None, true).await;

        let mismatch = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![wrong_fp],
            device.private_key_openssh.clone(),
            host_id.clone(),
            "device".into(),
        )
        .await
        .unwrap_err();
        assert!(matches!(mismatch, TransportError::HostKeyMismatch { .. }));
        assert!(!matches!(mismatch, TransportError::AuthRejected { .. }));
        let shown = format!("{mismatch:?}{mismatch}");
        assert!(shown.contains("HostKeyMismatch"), "{shown}");
        assert!(!shown.to_lowercase().contains("timeout"), "{shown}");

        let auth = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![real_fp],
            device.private_key_openssh,
            host_id,
            "device".into(),
        )
        .await
        .unwrap_err();
        assert_auth_rejected(auth);
    });
}
