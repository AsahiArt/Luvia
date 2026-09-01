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

use std::sync::Arc;
use std::time::Duration;

use russh::keys::{PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::{client, ChannelMsg, Disconnect};
use tokio::sync::Mutex;

use crate::channel::BridgeChannel;
use crate::error::TransportError;
use crate::fingerprint::{
    fingerprints_equal, format_public_key_sha256, format_sha256, parse_sha256_fingerprint,
    public_key_sha256,
};
use crate::keys::parse_private_key;
use crate::runtime::{spawn_background, spawn_on_runtime};
use crate::BRIDGE_COMMAND;

struct PinnedHostKey {
    accepted: Vec<[u8; 32]>,
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
        spawn_on_runtime(async move { connect_inner(address, port, username, accepted, key).await })
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

async fn connect_inner(
    host: String,
    port: u16,
    user: String,
    accepted: Vec<[u8; 32]>,
    key: PrivateKey,
) -> Result<Arc<SshConnection>, TransportError> {
    if host.is_empty() || user.is_empty() {
        return Err(TransportError::io("host and user are required"));
    }

    let config = client::Config {
        inactivity_timeout: None,
        keepalive_interval: Some(Duration::from_secs(30)),
        nodelay: true,
        ..Default::default()
    };

    let mut session = client::connect(
        Arc::new(config),
        (host.as_str(), port),
        PinnedHostKey { accepted },
    )
    .await?;

    let hash_alg = session.best_supported_rsa_hash().await?.flatten();
    let auth = session
        .authenticate_publickey(user, PrivateKeyWithHashAlg::new(Arc::new(key), hash_alg))
        .await?;
    if !auth.success() {
        let _ = session.disconnect(Disconnect::ByApplication, "", "").await;
        return Err(TransportError::AuthenticationFailed);
    }

    Ok(Arc::new(SshConnection {
        handle: Arc::new(Mutex::new(Some(session))),
    }))
}

async fn open_bridge_inner(
    handle: &client::Handle<PinnedHostKey>,
) -> Result<Arc<BridgeChannel>, TransportError> {
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
}
