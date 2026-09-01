//! Secure macOS/Linux host companion for Luvia.
//!
//! macOS and Linux only. Windows is not supported and there is no named-pipe
//! port.
//!
//! Pairings are durable device grants. Delegated UHP tokens are minted per
//! bridge invocation, never persisted, and revoked on close when the local
//! server supports `uhp.token.revoke`.
//!
//! The bridge mints two tokens: an action token with the device role's scopes,
//! and a session token with `read`+`admin` used only for `session.snapshot`
//! and `events.subscribe`. Neither token is ever sent to the device.

#[cfg(not(unix))]
compile_error!("luvia-host supports macOS and Linux only; Windows is not supported");

pub mod audit;
pub mod authkeys;
pub mod bridge;
pub mod capabilities;
pub mod channels;
pub mod cli;
pub mod discovery;
pub mod endpoint;
pub mod error;
pub mod frames;
pub mod grant;
pub mod hostinfo;
pub mod paircode;
pub mod paths;
pub mod prelude;
pub mod role;
pub mod sshkey;
pub mod uhp;
pub mod unique_json;

pub use cli::run;
pub use error::{Error, Result};
