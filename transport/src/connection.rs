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

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::Bytes;
use russh::keys::ssh_key::private::KeypairData;
use russh::keys::{PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::{client, ChannelMsg, Disconnect};
use tokio::net::TcpStream;
use tokio::sync::Mutex;

use crate::auth::{self, CLIENT_SSH_AUTH};
use crate::channel::BridgeChannel;
use crate::error::TransportError;
use crate::fingerprint::{
    fingerprints_equal, format_public_key_sha256, format_sha256, parse_sha256_fingerprint,
    public_key_sha256,
};
use crate::keys::parse_private_key;
use crate::runtime::{spawn_background, spawn_on_runtime};
use crate::BRIDGE_COMMAND;

const DNS_TIMEOUT: Duration = Duration::from_millis(1_500);
const TCP_TIMEOUT: Duration = Duration::from_secs(3);
/// Race probe only: TCP connect start through the first Prove+ack.
const PROVE_BUDGET: Duration = Duration::from_secs(10);
/// Each channel opened after the probe: open + Prove + ack, from that open.
const CHANNEL_PROVE_BUDGET: Duration = Duration::from_secs(10);

struct PinnedHostKey {
    accepted: Vec<[u8; 32]>,
    verified: Arc<AtomicBool>,
}

impl client::Handler for PinnedHostKey {
    type Error = TransportError;

    async fn check_server_key(
        &mut self,
        server_public_key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        let actual_key = server_public_key.public_key();
        let actual = public_key_sha256(&actual_key);
        let mut matched = false;
        for expected in &self.accepted {
            if fingerprints_equal(expected, &actual) {
                matched = true;
            }
        }
        if matched {
            self.verified.store(true, Ordering::SeqCst);
            Ok(true)
        } else {
            Err(TransportError::HostKeyMismatch {
                expected: self
                    .accepted
                    .iter()
                    .map(format_sha256)
                    .collect::<Vec<_>>()
                    .join(", "),
                actual: format_public_key_sha256(&actual_key),
            })
        }
    }
}

/// Authenticated SSH session. Channels all exec `luvia-host bridge`.
#[derive(uniffi::Object)]
pub struct SshConnection {
    handle: Arc<Mutex<Option<client::Handle<PinnedHostKey>>>>,
    host_key_verified: Arc<AtomicBool>,
    expected_host_id: String,
    device_id: String,
    signing_seed: [u8; 32],
    /// TCP connect start plus the probe budget. Only the first Prove+ack uses it.
    probe_deadline: Instant,
    /// Per-channel open+Prove+ack budget for every channel after the probe.
    channel_budget: Duration,
    /// Set after the first Prove+ack succeeds. Later channels ignore [`Self::probe_deadline`].
    probe_done: Arc<AtomicBool>,
}

impl std::fmt::Debug for SshConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SshConnection").finish_non_exhaustive()
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl SshConnection {
    #[uniffi::constructor]
    pub async fn connect(
        address: String,
        port: u16,
        username: String,
        accepted_host_key_fingerprints: Vec<String>,
        private_key_openssh: String,
        expected_host_id: String,
        device_id: String,
    ) -> Result<Arc<Self>, TransportError> {
        if accepted_host_key_fingerprints.is_empty() {
            return Err(TransportError::invalid_fingerprint(
                "no accepted host-key fingerprints",
            ));
        }
        let mut accepted = Vec::with_capacity(accepted_host_key_fingerprints.len());
        for fingerprint in &accepted_host_key_fingerprints {
            accepted.push(parse_sha256_fingerprint(fingerprint)?);
        }
        let key = parse_private_key(private_key_openssh)?;
        let signing_seed = ed25519_seed(&key)?;
        spawn_on_runtime(async move {
            connect_inner(
                address,
                port,
                username,
                accepted,
                key,
                ConnectIdentity {
                    expected_host_id,
                    device_id,
                    signing_seed,
                },
                PROVE_BUDGET,
                CHANNEL_PROVE_BUDGET,
            )
            .await
        })
        .await?
    }

    /// Exec `luvia-host bridge`, sign Hello only after the SSH host key matched
    /// the pairing record's host id, and wait for the host's ok frame.
    ///
    /// The first call is the race probe and must finish by the TCP-start deadline.
    /// Later calls each get [`CHANNEL_PROVE_BUDGET`] measured from that open.
    pub async fn open_authenticated_bridge(&self) -> Result<Arc<BridgeChannel>, TransportError> {
        let verified = self.host_key_verified.load(Ordering::SeqCst);
        let expected_host_id = self.expected_host_id.clone();
        let device_id = self.device_id.clone();
        let signing_seed = self.signing_seed;
        let probe_done = self.probe_done.load(Ordering::SeqCst);
        let deadline = if probe_done {
            Instant::now() + self.channel_budget
        } else {
            self.probe_deadline
        };
        let probe_flag = Arc::clone(&self.probe_done);
        let slots = Arc::clone(&self.handle);
        spawn_on_runtime(async move {
            let guard = slots.lock().await;
            let handle = guard
                .as_ref()
                .ok_or_else(|| TransportError::disconnected("closed"))?;
            open_authenticated_inner(
                handle,
                verified,
                &expected_host_id,
                &device_id,
                &signing_seed,
                deadline,
                probe_flag,
            )
            .await
        })
        .await?
    }

    pub async fn shutdown(&self) -> Result<(), TransportError> {
        let handle = self.handle.lock().await.take();
        if let Some(handle) = handle {
            spawn_on_runtime(async move {
                let _ = handle.disconnect(Disconnect::ByApplication, "", "").await;
            })
            .await?;
        }
        Ok(())
    }
}

impl SshConnection {
    /// Same as [`SshConnection::connect`], with an explicit probe budget and
    /// per-channel budget. Not exported over UniFFI. Production `connect`
    /// keeps both at 10s.
    #[allow(clippy::too_many_arguments)]
    pub async fn connect_with_budgets(
        address: String,
        port: u16,
        username: String,
        accepted_host_key_fingerprints: Vec<String>,
        private_key_openssh: String,
        expected_host_id: String,
        device_id: String,
        probe_budget: Duration,
        channel_budget: Duration,
    ) -> Result<Arc<Self>, TransportError> {
        if accepted_host_key_fingerprints.is_empty() {
            return Err(TransportError::invalid_fingerprint(
                "no accepted host-key fingerprints",
            ));
        }
        let mut accepted = Vec::with_capacity(accepted_host_key_fingerprints.len());
        for fingerprint in &accepted_host_key_fingerprints {
            accepted.push(parse_sha256_fingerprint(fingerprint)?);
        }
        let key = parse_private_key(private_key_openssh)?;
        let signing_seed = ed25519_seed(&key)?;
        spawn_on_runtime(async move {
            connect_inner(
                address,
                port,
                username,
                accepted,
                key,
                ConnectIdentity {
                    expected_host_id,
                    device_id,
                    signing_seed,
                },
                probe_budget,
                channel_budget,
            )
            .await
        })
        .await?
    }
}

impl Drop for SshConnection {
    fn drop(&mut self) {
        if let Ok(mut guard) = self.handle.try_lock() {
            if let Some(handle) = guard.take() {
                spawn_background(async move {
                    let _ = handle.disconnect(Disconnect::ByApplication, "", "").await;
                });
            }
        }
    }
}

struct ConnectIdentity {
    expected_host_id: String,
    device_id: String,
    signing_seed: [u8; 32],
}

fn remaining_until(deadline: Instant) -> Duration {
    deadline.saturating_duration_since(Instant::now())
}

fn map_handshake_error(err: TransportError) -> TransportError {
    match err {
        // `read_ssh_id` returns Disconnect on a clean FIN before a valid
        // SSH identification line. That is ClosedBeforeBanner.
        // russh also synthesizes Disconnect when the session task returns Ok
        // after the banner (SSH DISCONNECT) and `connect_stream` replaces it.
        // Those two Disconnects are the same variant here.
        TransportError::Disconnected { .. } => TransportError::ClosedBeforeBanner,
        // Version and kex are already HandshakeFailed. Generic IO is not
        // "no banner": after the identification line, russh uses `read_exact`
        // (UnexpectedEof or a reset). A RST before the banner is the same
        // `Error::IO`, and this mapping does not pretend to split that kind.
        TransportError::Io { reason } if reason == "ssh transport failure" => {
            TransportError::HandshakeFailed { reason }
        }
        other => other,
    }
}

async fn lookup_host(host: &str, port: u16) -> Result<std::net::SocketAddr, TransportError> {
    match tokio::time::timeout(DNS_TIMEOUT, tokio::net::lookup_host((host, port))).await {
        Ok(Ok(mut addrs)) => addrs.next().ok_or_else(|| TransportError::io("dns failed")),
        Ok(Err(_)) => Err(TransportError::io("dns failed")),
        Err(_) => Err(TransportError::io("dns timeout")),
    }
}

/// Resolve one candidate. The Kotlin racer bounds this call at 1.5s as well.
#[uniffi::export(async_runtime = "tokio")]
pub async fn resolve_candidate(address: String, port: u16) -> Result<String, TransportError> {
    spawn_on_runtime(async move {
        let resolved = lookup_host(&address, port).await?;
        Ok(resolved.ip().to_string())
    })
    .await?
}

fn ed25519_seed(key: &PrivateKey) -> Result<[u8; 32], TransportError> {
    match key.key_data() {
        KeypairData::Ed25519(pair) => Ok(pair.private.to_bytes()),
        _ => Err(TransportError::invalid_key()),
    }
}

#[allow(clippy::too_many_arguments)]
async fn connect_inner(
    host: String,
    port: u16,
    user: String,
    accepted: Vec<[u8; 32]>,
    key: PrivateKey,
    identity: ConnectIdentity,
    prove_budget: Duration,
    channel_budget: Duration,
) -> Result<Arc<SshConnection>, TransportError> {
    if host.is_empty() || user.is_empty() {
        return Err(TransportError::io("host and user are required"));
    }

    let resolved = lookup_host(&host, port).await?;

    // The probe budget runs from TCP start through the first Prove+ack, not
    // from DNS, and not for later channels. Tests pass a shorter budget.
    let tcp_started = Instant::now();
    let probe_deadline = tcp_started + prove_budget;
    let stream = match tokio::time::timeout(TCP_TIMEOUT, TcpStream::connect(resolved)).await {
        Ok(Ok(stream)) => stream,
        Ok(Err(err)) => return Err(TransportError::from_connect_io(err)),
        Err(_) => return Err(TransportError::io("connect timeout")),
    };
    let _ = stream.set_nodelay(true);

    let config = client::Config {
        inactivity_timeout: None,
        keepalive_interval: Some(Duration::from_secs(30)),
        nodelay: true,
        ..Default::default()
    };

    let host_key_verified = Arc::new(AtomicBool::new(false));
    let handshake_budget = remaining_until(probe_deadline);
    if handshake_budget.is_zero() {
        return Err(TransportError::io("candidate timeout"));
    }
    let mut session = match tokio::time::timeout(
        handshake_budget,
        client::connect_stream(
            Arc::new(config),
            stream,
            PinnedHostKey {
                accepted,
                verified: Arc::clone(&host_key_verified),
            },
        ),
    )
    .await
    {
        Ok(Ok(session)) => session,
        // A peer that accepts TCP and then FINs with no banner is a failed
        // connect. russh surfaces that as Disconnect. It is not a timeout and
        // not a session that was already up.
        Ok(Err(err)) => return Err(map_handshake_error(err)),
        Err(_) => return Err(TransportError::io("candidate timeout")),
    };

    let auth_budget = remaining_until(probe_deadline);
    if auth_budget.is_zero() {
        let _ = session.disconnect(Disconnect::ByApplication, "", "").await;
        return Err(TransportError::io("candidate timeout"));
    }
    let hash_alg = session.best_supported_rsa_hash().await?.flatten();
    // CLIENT_SSH_AUTH is publickey only. Do not call none or password.
    debug_assert_eq!(CLIENT_SSH_AUTH, ["publickey"]);
    let auth = match tokio::time::timeout(
        auth_budget,
        session.authenticate_publickey(user, PrivateKeyWithHashAlg::new(Arc::new(key), hash_alg)),
    )
    .await
    {
        Ok(result) => result?,
        Err(_) => {
            let _ = session.disconnect(Disconnect::ByApplication, "", "").await;
            return Err(TransportError::io("candidate timeout"));
        }
    };
    if !auth.success() {
        let _ = session.disconnect(Disconnect::ByApplication, "", "").await;
        return Err(TransportError::AuthenticationFailed);
    }

    Ok(Arc::new(SshConnection {
        handle: Arc::new(Mutex::new(Some(session))),
        host_key_verified,
        expected_host_id: identity.expected_host_id,
        device_id: identity.device_id,
        signing_seed: identity.signing_seed,
        probe_deadline,
        channel_budget,
        probe_done: Arc::new(AtomicBool::new(false)),
    }))
}

async fn open_session_channel(
    handle: &client::Handle<PinnedHostKey>,
) -> Result<russh::Channel<client::Msg>, TransportError> {
    Ok(handle.channel_open_session().await?)
}

async fn finish_bridge_exec(
    channel: &mut russh::Channel<client::Msg>,
) -> Result<Vec<u8>, TransportError> {
    channel.exec(true, BRIDGE_COMMAND).await?;

    let mut initial = Vec::new();
    loop {
        match channel.wait().await {
            Some(ChannelMsg::Success) => return Ok(initial),
            Some(ChannelMsg::Failure) => {
                return Err(TransportError::io("bridge exec rejected"));
            }
            Some(ChannelMsg::Data { ref data }) => {
                initial.extend(data.iter().copied());
            }
            Some(ChannelMsg::Eof) | Some(ChannelMsg::Close) | None => {
                return Err(TransportError::ChannelClosed);
            }
            _ => {}
        }
    }
}

async fn read_lf_frame(
    channel: &mut russh::Channel<client::Msg>,
    buffered: &mut Vec<u8>,
) -> Result<Vec<u8>, TransportError> {
    loop {
        if let Some(pos) = buffered.iter().position(|byte| *byte == b'\n') {
            let frame = buffered[..=pos].to_vec();
            buffered.drain(..=pos);
            return Ok(frame);
        }
        match channel.wait().await {
            Some(ChannelMsg::Data { ref data }) => buffered.extend(data.iter().copied()),
            Some(ChannelMsg::Eof) | Some(ChannelMsg::Close) | None => {
                return Err(TransportError::ChannelClosed);
            }
            _ => {}
        }
    }
}

async fn open_authenticated_inner(
    handle: &client::Handle<PinnedHostKey>,
    host_key_verified: bool,
    expected_host_id: &str,
    device_id: &str,
    signing_seed: &[u8; 32],
    deadline: Instant,
    probe_done: Arc<AtomicBool>,
) -> Result<Arc<BridgeChannel>, TransportError> {
    if !host_key_verified {
        return Err(TransportError::HostKeyUnverified);
    }
    let open_budget = remaining_until(deadline);
    if open_budget.is_zero() {
        return Err(TransportError::io("candidate timeout"));
    }
    // `channel_open_session` does not yield a Channel until the server
    // confirms the open. A timeout there drops the wait; there is no handle
    // to close. Once the channel exists, a later timeout closes it.
    let opened = tokio::time::timeout(open_budget, open_session_channel(handle)).await;
    let mut channel = match opened {
        Ok(Ok(channel)) => channel,
        Ok(Err(err)) => return Err(err),
        Err(_) => return Err(TransportError::io("candidate timeout")),
    };
    let exec_budget = remaining_until(deadline);
    if exec_budget.is_zero() {
        return fail_channel(channel, TransportError::io("candidate timeout")).await;
    }
    let finished = tokio::time::timeout(exec_budget, finish_bridge_exec(&mut channel)).await;
    let mut initial = match finished {
        Ok(Ok(initial)) => initial,
        Ok(Err(err)) => return fail_channel(channel, err).await,
        Err(_) => return fail_channel(channel, TransportError::io("candidate timeout")).await,
    };
    let seed = *signing_seed;
    let hello = match read_frame_until(&mut channel, &mut initial, deadline).await {
        Ok(frame) => frame,
        Err(err) => return fail_channel(channel, err).await,
    };
    let prove = match auth::client_prove(true, expected_host_id, device_id, &hello, |message| {
        Ok(auth::sign_message(&seed, message))
    }) {
        Ok(frame) => frame,
        Err(err) => return fail_channel(channel, err).await,
    };
    if let Err(err) = channel.data_bytes(Bytes::from(prove)).await {
        return fail_channel(channel, TransportError::from(err)).await;
    }
    let ack = match read_frame_until(&mut channel, &mut initial, deadline).await {
        Ok(frame) => frame,
        Err(err) => return fail_channel(channel, err).await,
    };
    match auth::parse_auth_ack(&ack) {
        Ok(auth::AuthAck::Ok) => {
            probe_done.store(true, Ordering::SeqCst);
            Ok(BridgeChannel::spawn(channel, initial))
        }
        Ok(auth::AuthAck::Rejected { code, reason }) => {
            fail_channel(channel, TransportError::ProveRejected { code, reason }).await
        }
        Err(err) => fail_channel(channel, err).await,
    }
}

async fn read_frame_until(
    channel: &mut russh::Channel<client::Msg>,
    buffered: &mut Vec<u8>,
    deadline: Instant,
) -> Result<Vec<u8>, TransportError> {
    let budget = remaining_until(deadline);
    if budget.is_zero() {
        return Err(TransportError::io("candidate timeout"));
    }
    match tokio::time::timeout(budget, read_lf_frame(channel, buffered)).await {
        Ok(frame) => frame,
        Err(_) => Err(TransportError::io("candidate timeout")),
    }
}

async fn fail_channel<T>(
    channel: russh::Channel<client::Msg>,
    err: TransportError,
) -> Result<T, TransportError> {
    let _ = channel.close().await;
    Err(err)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::generate_device_key;

    #[test]
    fn candidate_timeouts_are_dns_1_5s_tcp_3s_prove_10s() {
        assert_eq!(DNS_TIMEOUT, Duration::from_millis(1_500));
        assert_eq!(TCP_TIMEOUT, Duration::from_secs(3));
        assert_eq!(PROVE_BUDGET, Duration::from_secs(10));
        assert_eq!(CHANNEL_PROVE_BUDGET, Duration::from_secs(10));
    }

    #[test]
    fn handshake_disconnect_is_closed_before_banner_but_mismatch_is_not() {
        let dropped = map_handshake_error(TransportError::disconnected("disconnected"));
        assert!(matches!(dropped, TransportError::ClosedBeforeBanner));
        let shown = format!("{dropped:?}{dropped}");
        assert!(shown.contains("closed before banner"), "{shown}");
        assert!(!shown.contains("connect timeout"), "{shown}");
        let mismatch = map_handshake_error(TransportError::HostKeyMismatch {
            expected: "SHA256:aaa".into(),
            actual: "SHA256:bbb".into(),
        });
        assert!(matches!(mismatch, TransportError::HostKeyMismatch { .. }));
        assert!(!matches!(mismatch, TransportError::ClosedBeforeBanner));
        let reset = map_handshake_error(TransportError::io("ssh transport failure"));
        assert!(
            matches!(reset, TransportError::HandshakeFailed { ref reason } if reason == "ssh transport failure")
        );
        assert!(!matches!(reset, TransportError::ClosedBeforeBanner));
        let version = map_handshake_error(TransportError::from(russh::Error::Version));
        assert!(
            matches!(version, TransportError::HandshakeFailed { ref reason } if reason == "invalid version")
        );
        let kex = map_handshake_error(TransportError::from(russh::Error::Kex));
        assert!(
            matches!(kex, TransportError::HandshakeFailed { ref reason } if reason == "key exchange failed")
        );
        let timed_out = map_handshake_error(TransportError::io("connect timeout"));
        assert!(
            matches!(timed_out, TransportError::Io { ref reason } if reason == "connect timeout")
        );
        assert!(!matches!(timed_out, TransportError::ClosedBeforeBanner));
    }

    #[test]
    fn connect_rejects_bad_fingerprint_before_io() {
        let key = generate_device_key().unwrap();
        let err = crate::runtime::runtime().block_on(SshConnection::connect(
            "127.0.0.1".into(),
            1,
            "luvia".into(),
            vec!["md5:aa:bb".into()],
            key.private_key_openssh,
            "host".into(),
            "device".into(),
        ));
        assert!(matches!(
            err,
            Err(TransportError::InvalidFingerprint { .. })
        ));
    }

    #[test]
    fn connect_rejects_empty_fingerprint_set() {
        let key = generate_device_key().unwrap();
        let err = crate::runtime::runtime().block_on(SshConnection::connect(
            "127.0.0.1".into(),
            1,
            "luvia".into(),
            Vec::new(),
            key.private_key_openssh,
            "host".into(),
            "device".into(),
        ));
        assert!(matches!(
            err,
            Err(TransportError::InvalidFingerprint { .. })
        ));
    }

    #[test]
    fn host_key_mismatch_is_distinct_from_auth_failure() {
        let err = TransportError::HostKeyMismatch {
            expected: "SHA256:aaa".into(),
            actual: "SHA256:bbb".into(),
        };
        assert!(!matches!(err, TransportError::AuthenticationFailed));
        let shown = format!("{err:?}");
        assert!(shown.contains("HostKeyMismatch"));
        assert!(!shown.contains("BEGIN OPENSSH"));
    }

    fn dummy_fingerprint() -> String {
        "SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs".into()
    }

    #[test]
    fn connect_fails_fast_on_nxdomain() {
        let key = generate_device_key().unwrap();
        let started = std::time::Instant::now();
        let err = crate::runtime::runtime().block_on(SshConnection::connect(
            "this.host.invalid".into(),
            22,
            "luvia".into(),
            vec![dummy_fingerprint()],
            key.private_key_openssh,
            "host".into(),
            "device".into(),
        ));
        assert!(started.elapsed() < Duration::from_secs(4));
        let shown = format!("{err:?}");
        assert!(
            shown.contains("dns failed") || shown.contains("dns timeout"),
            "{shown}"
        );
    }

    fn dial_local(
        port: u16,
        openssh: String,
        budget: Duration,
    ) -> Result<Arc<SshConnection>, TransportError> {
        let key = parse_private_key(openssh)?;
        let signing_seed = ed25519_seed(&key)?;
        let accepted = vec![parse_sha256_fingerprint(&dummy_fingerprint())?];
        crate::runtime::runtime().block_on(connect_inner(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            accepted,
            key,
            ConnectIdentity {
                expected_host_id: "host".into(),
                device_id: "device".into(),
                signing_seed,
            },
            budget,
            CHANNEL_PROVE_BUDGET,
        ))
    }

    #[test]
    fn connect_closed_before_banner_when_listener_drops() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                // Read the client identification so the close is an EOF on the
                // banner read, not a reset of the client's own write.
                let mut buf = [0u8; 64];
                let _ = std::io::Read::read(&mut stream, &mut buf);
                drop(stream);
            }
        });
        let key = generate_device_key().unwrap();
        let started = std::time::Instant::now();
        let err =
            dial_local(port, key.private_key_openssh, Duration::from_millis(800)).unwrap_err();
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "{:?}",
            started.elapsed()
        );
        assert!(matches!(err, TransportError::ClosedBeforeBanner), "{err:?}");
        let _ = server.join();
    }

    #[test]
    fn connect_banner_then_garbage_is_handshake_failed() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (release_tx, release_rx) = std::sync::mpsc::channel::<()>();
        let server = std::thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                use std::io::Write;
                // A valid identification line, then a packet length russh rejects.
                // Hold the socket so this is not a race with BrokenPipe.
                let _ = stream.write_all(b"SSH-2.0-luvia-test\r\n");
                let _ = stream.write_all(&[0xff, 0xff, 0xff, 0xff]);
                let _ = stream.flush();
                let _ = release_rx.recv_timeout(Duration::from_secs(2));
                drop(stream);
            }
        });
        let key = generate_device_key().unwrap();
        let started = std::time::Instant::now();
        let err =
            dial_local(port, key.private_key_openssh, Duration::from_millis(800)).unwrap_err();
        let _ = release_tx.send(());
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "{:?}",
            started.elapsed()
        );
        assert!(
            matches!(err, TransportError::HandshakeFailed { .. }),
            "{err:?}"
        );
        assert!(
            !matches!(err, TransportError::ClosedBeforeBanner),
            "{err:?}"
        );
        let _ = server.join();
    }

    #[test]
    fn connect_times_out_when_listener_sends_no_banner() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (release_tx, release_rx) = std::sync::mpsc::channel::<()>();
        let server = std::thread::spawn(move || {
            if let Ok((stream, _)) = listener.accept() {
                let _ = release_rx.recv_timeout(Duration::from_secs(3));
                drop(stream);
            }
        });
        let key = generate_device_key().unwrap();
        let started = std::time::Instant::now();
        let err =
            dial_local(port, key.private_key_openssh, Duration::from_millis(200)).unwrap_err();
        let elapsed = started.elapsed();
        let _ = release_tx.send(());
        let _ = server.join();
        assert!(elapsed < Duration::from_secs(2), "{elapsed:?}");
        assert!(
            !matches!(err, TransportError::ClosedBeforeBanner),
            "{err:?}"
        );
        let shown = format!("{err:?}{err}");
        assert!(shown.contains("timeout"), "{shown}");
    }
}
