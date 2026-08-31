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

//! Pinned SSH transport for Luvia. The only remote command is `luvia-host bridge`.

uniffi::setup_scaffolding!("luvia_transport");

mod channel;
mod connection;
mod error;
mod fingerprint;
mod keys;
mod rng;
mod runtime;

pub use channel::{BridgeChannel, ChannelRead, MAX_READ_BYTES};
pub use connection::SshConnection;
pub use error::TransportError;
pub use keys::{generate_device_key, import_device_key, DeviceKey, DevicePublicKey};

/// Exact forced-command executed on every session channel.
pub const BRIDGE_COMMAND: &str = "luvia-host bridge";

#[uniffi::export]
pub fn bridge_command() -> String {
    BRIDGE_COMMAND.to_string()
}

#[uniffi::export]
pub fn max_read_bytes() -> u32 {
    MAX_READ_BYTES
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forced_command_is_exactly_luvia_host_bridge() {
        assert_eq!(BRIDGE_COMMAND, "luvia-host bridge");
        assert_eq!(bridge_command(), "luvia-host bridge");
        assert_eq!(BRIDGE_COMMAND.as_bytes(), b"luvia-host bridge");
    }

    #[test]
    fn read_bound_is_one_mib() {
        assert_eq!(MAX_READ_BYTES, 1024 * 1024);
        assert_eq!(max_read_bytes(), MAX_READ_BYTES);
    }
}
