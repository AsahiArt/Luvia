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

/// Transport failures visible across UniFFI.
///
/// Messages never include private-key material, passphrases, or raw
/// OpenSSH PEM. Host-key mismatch carries only SHA-256 fingerprints.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum TransportError {
    #[error("invalid device key: {reason}")]
    InvalidKey { reason: String },
    #[error("invalid host-key fingerprint: {reason}")]
    InvalidFingerprint { reason: String },
    #[error("host key mismatch: expected {expected}, actual {actual}")]
    HostKeyMismatch { expected: String, actual: String },
    #[error("public-key authentication failed")]
    AuthenticationFailed,
    #[error("disconnected: {reason}")]
    Disconnected { reason: String },
    #[error("channel closed")]
    ChannelClosed,
    #[error("invalid read bound {max_bytes}")]
    InvalidReadBound { max_bytes: u32 },
    #[error("ssh transport failure: {reason}")]
    Io { reason: String },
}

impl TransportError {
    pub(crate) fn invalid_key() -> Self {
        Self::InvalidKey {
            reason: "unreadable or unsupported openssh private key".into(),
        }
    }

    pub(crate) fn invalid_fingerprint(reason: impl Into<String>) -> Self {
        Self::InvalidFingerprint {
            reason: reason.into(),
        }
    }

    pub(crate) fn disconnected(reason: impl Into<String>) -> Self {
        Self::Disconnected {
            reason: reason.into(),
        }
    }

    pub(crate) fn io(reason: impl Into<String>) -> Self {
        Self::Io {
            reason: reason.into(),
        }
    }
}

impl From<russh::Error> for TransportError {
    fn from(err: russh::Error) -> Self {
        // Never format the russh error payload: IO/key variants can carry
        // path or key-adjacent text.
        match err {
            russh::Error::CouldNotReadKey | russh::Error::Keys(_) | russh::Error::SshKey(_) => {
                Self::invalid_key()
            }
            russh::Error::UnknownKey | russh::Error::KeyChanged { .. } => Self::HostKeyMismatch {
                expected: String::new(),
                actual: String::new(),
            },
            russh::Error::NotAuthenticated
            | russh::Error::NoAuthMethod
            | russh::Error::UnsupportedAuthMethod => Self::AuthenticationFailed,
            russh::Error::Disconnect
            | russh::Error::HUP
            | russh::Error::ConnectionTimeout
            | russh::Error::KeepaliveTimeout
            | russh::Error::InactivityTimeout => Self::disconnected("disconnected"),
            russh::Error::RecvError | russh::Error::WrongChannel => Self::ChannelClosed,
            russh::Error::SendError | russh::Error::Pending => Self::disconnected("session closed"),
            _ => Self::io("ssh transport failure"),
        }
    }
}

impl From<russh::keys::Error> for TransportError {
    fn from(_err: russh::keys::Error) -> Self {
        Self::invalid_key()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_and_display_omit_pem_and_secrets() {
        let pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRETMATERIAL\n-----END OPENSSH PRIVATE KEY-----";
        let errors = [
            TransportError::invalid_key(),
            TransportError::AuthenticationFailed,
            TransportError::HostKeyMismatch {
                expected: "SHA256:abc".into(),
                actual: "SHA256:xyz".into(),
            },
            TransportError::io("ssh transport failure"),
        ];
        for err in errors {
            let shown = format!("{err:?}{err}");
            assert!(!shown.contains(pem), "{shown}");
            assert!(!shown.contains("SECRETMATERIAL"), "{shown}");
            assert!(!shown.contains("BEGIN OPENSSH"), "{shown}");
        }
    }

    #[test]
    fn russh_error_mapping_does_not_echo_source() {
        let err = TransportError::from(russh::Error::CouldNotReadKey);
        let shown = format!("{err:?}{err}");
        assert!(!shown.contains("BEGIN"));
        assert!(matches!(err, TransportError::InvalidKey { .. }));
    }
}
