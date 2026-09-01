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

use russh::keys::{ssh_key::LineEnding, Algorithm, PrivateKey};
use zeroize::Zeroize;

use crate::error::TransportError;
use crate::fingerprint::format_public_key_sha256;

const DEVICE_KEY_COMMENT: &str = "luvia-device";

/// Public identity suitable for OpenSSH `authorized_keys`.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct DevicePublicKey {
    /// OpenSSH public-key line (`ssh-ed25519 AAAA... luvia-device`).
    pub authorized_keys: String,
    /// Canonical `SHA256:<unpadded-base64>` fingerprint.
    pub fingerprint: String,
}

/// Newly generated key material. `private_key_openssh` is for platform
/// secure storage only; it is never logged.
#[derive(Clone, uniffi::Record)]
pub struct DeviceKey {
    pub public_key: DevicePublicKey,
    pub private_key_openssh: String,
}

impl std::fmt::Debug for DeviceKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeviceKey")
            .field("public_key", &self.public_key)
            .field("private_key_openssh", &"<redacted>")
            .finish()
    }
}

impl std::fmt::Debug for DevicePublicKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DevicePublicKey")
            .field("authorized_keys", &self.public_key_debug())
            .field("fingerprint", &self.fingerprint)
            .finish()
    }
}

impl DevicePublicKey {
    fn public_key_debug(&self) -> &str {
        self.authorized_keys
            .split_whitespace()
            .next()
            .unwrap_or("ssh-ed25519")
    }
}

#[uniffi::export]
pub fn generate_device_key() -> Result<DeviceKey, TransportError> {
    let mut key = PrivateKey::random(&mut crate::rng::OsCryptoRng, Algorithm::Ed25519)
        .map_err(|_| TransportError::invalid_key())?;
    key.set_comment(DEVICE_KEY_COMMENT);
    let private_key_openssh = key
        .to_openssh(LineEnding::LF)
        .map_err(|_| TransportError::invalid_key())?
        .to_string();
    let public_key = identity_from_private(&key)?;
    Ok(DeviceKey {
        public_key,
        private_key_openssh,
    })
}

#[uniffi::export]
pub fn import_device_key(private_key_openssh: String) -> Result<DevicePublicKey, TransportError> {
    let key = parse_private_key(private_key_openssh)?;
    identity_from_private(&key)
}

pub(crate) fn parse_private_key(
    mut private_key_openssh: String,
) -> Result<PrivateKey, TransportError> {
    let parsed =
        PrivateKey::from_openssh(&private_key_openssh).map_err(|_| TransportError::invalid_key());
    private_key_openssh.zeroize();
    let key = parsed?;
    if key.is_encrypted() {
        return Err(TransportError::InvalidKey {
            reason: "encrypted private keys are not supported".into(),
        });
    }
    Ok(key)
}

fn identity_from_private(key: &PrivateKey) -> Result<DevicePublicKey, TransportError> {
    let public = key.public_key();
    let authorized_keys = public
        .to_openssh()
        .map_err(|_| TransportError::invalid_key())?;
    Ok(DevicePublicKey {
        fingerprint: format_public_key_sha256(public),
        authorized_keys,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_import_round_trip() {
        let generated = generate_device_key().unwrap();
        assert!(generated
            .public_key
            .authorized_keys
            .starts_with("ssh-ed25519 "));
        assert!(generated
            .public_key
            .authorized_keys
            .contains(DEVICE_KEY_COMMENT));
        assert!(generated.public_key.fingerprint.starts_with("SHA256:"));
        assert!(generated
            .private_key_openssh
            .contains("BEGIN OPENSSH PRIVATE KEY"));

        let imported = import_device_key(generated.private_key_openssh.clone()).unwrap();
        assert_eq!(imported, generated.public_key);

        let again = import_device_key(generated.private_key_openssh.clone()).unwrap();
        assert_eq!(again.fingerprint, generated.public_key.fingerprint);
    }

    #[test]
    fn debug_redacts_private_key() {
        let generated = generate_device_key().unwrap();
        let debug = format!("{generated:?}");
        assert!(debug.contains("<redacted>"));
        assert!(!debug.contains(&generated.private_key_openssh));
        assert!(!debug.contains("BEGIN OPENSSH"));
        assert!(!debug.contains("SECRET"));
    }

    #[test]
    fn import_rejects_garbage() {
        let err = import_device_key("not-a-key".into()).unwrap_err();
        let shown = format!("{err:?}{err}");
        assert!(matches!(err, TransportError::InvalidKey { .. }));
        assert!(!shown.contains("BEGIN OPENSSH"));
    }
}
