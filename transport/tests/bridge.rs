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

use std::future::Future;
use std::sync::Arc;
use std::time::Duration;

use luvia_transport::{
    generate_device_key, import_device_key, SshConnection, TransportError, BRIDGE_COMMAND,
    MAX_READ_BYTES,
};
use russh::keys::{HashAlg, PrivateKey, PublicKey};
use russh::server::{Auth, Handler, Msg, Session};
use russh::{server, Channel, ChannelId};

struct EchoServer {
    client_key: PublicKey,
}

impl Handler for EchoServer {
    type Error = russh::Error;

    async fn auth_publickey(
        &mut self,
        _user: &str,
        public_key: &PublicKey,
    ) -> Result<Auth, Self::Error> {
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
        session.data(channel, data.to_vec())?;
        Ok(())
    }
}

fn block_on<T>(fut: impl Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("test runtime")
        .block_on(fut)
}

async fn start_echo(host_key: PrivateKey, client_key: PublicKey) -> u16 {
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
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = listener.accept().await else {
                break;
            };
            let config = Arc::clone(&config);
            let handler = EchoServer {
                client_key: client_key.clone(),
            };
            tokio::spawn(async move {
                let _ = server::run_stream(config, stream, handler).await;
            });
        }
    });
    port
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
        let port = start_echo(host_key, client_pub).await;

        let mismatch = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![wrong_fp.clone()],
            device.private_key_openssh.clone(),
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
        )
        .await
        .unwrap_err();
        assert!(matches!(auth, TransportError::AuthenticationFailed));

        let conn = SshConnection::connect(
            "127.0.0.1".into(),
            port,
            "luvia".into(),
            vec![wrong_fp, real_fp],
            device.private_key_openssh,
        )
        .await
        .unwrap();

        let a = conn.open_bridge().await.unwrap();
        let b = conn.open_bridge().await.unwrap();
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
