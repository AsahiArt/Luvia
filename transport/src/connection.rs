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
use std::time::Duration;

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

const DNS_TIMEOUT: Duration = Duration::from_secs(2);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

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
            )
            .await
        })
        .await?
    }

    /// Exec `luvia-host bridge`, then sign Hello only if the SSH host key matched
    /// the pairing record's host id.
    pub async fn open_authenticated_bridge(&self) -> Result<Arc<BridgeChannel>, TransportError> {
        let verified = self.host_key_verified.load(Ordering::SeqCst);
        let expected_host_id = self.expected_host_id.clone();
        let device_id = self.device_id.clone();
        let signing_seed = self.signing_seed;
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
            )
            .await
        })
        .await?
    }

    pub async fn open_bridge(&self) -> Result<Arc<BridgeChannel>, TransportError> {
        let slots = Arc::clone(&self.handle);
        spawn_on_runtime(async move {
            let guard = slots.lock().await;
            let handle = guard
                .as_ref()
                .ok_or_else(|| TransportError::disconnected("closed"))?;
            open_bridge_inner(handle).await
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

fn ed25519_seed(key: &PrivateKey) -> Result<[u8; 32], TransportError> {
    match key.key_data() {
        KeypairData::Ed25519(pair) => Ok(pair.private.to_bytes()),
        _ => Err(TransportError::invalid_key()),
    }
}

async fn connect_inner(
    host: String,
    port: u16,
    user: String,
    accepted: Vec<[u8; 32]>,
    key: PrivateKey,
    identity: ConnectIdentity,
) -> Result<Arc<SshConnection>, TransportError> {
    if host.is_empty() || user.is_empty() {
        return Err(TransportError::io("host and user are required"));
    }

    let resolved =
        match tokio::time::timeout(DNS_TIMEOUT, tokio::net::lookup_host((host.as_str(), port)))
            .await
        {
            Ok(Ok(mut addrs)) => addrs
                .next()
                .ok_or_else(|| TransportError::io("dns failed"))?,
            Ok(Err(_)) => return Err(TransportError::io("dns failed")),
            Err(_) => return Err(TransportError::io("dns timeout")),
        };

    let stream = match tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(resolved)).await {
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
    let mut session = match tokio::time::timeout(
        CONNECT_TIMEOUT,
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
        Ok(Err(err)) => return Err(err),
        Err(_) => return Err(TransportError::io("connect timeout")),
    };

    let hash_alg = session.best_supported_rsa_hash().await?.flatten();
    // CLIENT_SSH_AUTH is publickey only. Do not call none or password.
    debug_assert_eq!(CLIENT_SSH_AUTH, ["publickey"]);
    let auth = session
        .authenticate_publickey(user, PrivateKeyWithHashAlg::new(Arc::new(key), hash_alg))
        .await?;
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
    }))
}

async fn exec_bridge(
    handle: &client::Handle<PinnedHostKey>,
) -> Result<(russh::Channel<client::Msg>, Vec<u8>), TransportError> {
    let mut channel = handle.channel_open_session().await?;
    channel.exec(true, BRIDGE_COMMAND).await?;

    let mut initial = Vec::new();
    loop {
        match channel.wait().await {
            Some(ChannelMsg::Success) => break,
            Some(ChannelMsg::Failure) => {
                let _ = channel.close().await;
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
    Ok((channel, initial))
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

async fn open_bridge_inner(
    handle: &client::Handle<PinnedHostKey>,
) -> Result<Arc<BridgeChannel>, TransportError> {
    let (channel, initial) = exec_bridge(handle).await?;
    Ok(BridgeChannel::spawn(channel, initial))
}

async fn open_authenticated_inner(
    handle: &client::Handle<PinnedHostKey>,
    host_key_verified: bool,
    expected_host_id: &str,
    device_id: &str,
    signing_seed: &[u8; 32],
) -> Result<Arc<BridgeChannel>, TransportError> {
    if !host_key_verified {
        return Err(TransportError::HostKeyUnverified);
    }
    let (mut channel, mut initial) = exec_bridge(handle).await?;
    let frame = match tokio::time::timeout(
        Duration::from_secs(4),
        read_lf_frame(&mut channel, &mut initial),
    )
    .await
    {
        Ok(frame) => frame?,
        Err(_) => return Err(TransportError::io("connect timeout")),
    };
    let prove = auth::client_prove(true, expected_host_id, device_id, &frame, |message| {
        Ok(auth::sign_message(signing_seed, message))
    })?;
    channel
        .data_bytes(Bytes::from(prove))
        .await
        .map_err(TransportError::from)?;
    Ok(BridgeChannel::spawn(channel, initial))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::generate_device_key;

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

    #[test]
    fn connect_times_out_on_unroutable_literal() {
        let key = generate_device_key().unwrap();
        let started = std::time::Instant::now();
        let err = crate::runtime::runtime().block_on(SshConnection::connect(
            "192.0.2.1".into(),
            22,
            "luvia".into(),
            vec![dummy_fingerprint()],
            key.private_key_openssh,
            "host".into(),
            "device".into(),
        ));
        assert!(started.elapsed() < Duration::from_secs(8));
        let shown = format!("{err:?}");
        assert!(
            shown.contains("connect timeout") || shown.contains("no route"),
            "{shown}"
        );
    }
}
