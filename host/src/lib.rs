//! Secure macOS/Linux host companion for Luvia.
//!
//! Pairings are durable device grants. Delegated UHP tokens are minted per
//! bridge invocation, never persisted, and revoked on close when the local
//! server supports `uhp.token.revoke`.

#[cfg(not(unix))]
compile_error!("luvia-host supports macOS and Linux only");

pub mod authkeys;
pub mod bridge;
pub mod cli;
pub mod discovery;
pub mod endpoint;
pub mod error;
pub mod frames;
pub mod grant;
pub mod paths;
pub mod prelude;
pub mod role;
pub mod sshkey;
pub mod uhp;
pub mod unique_json;

pub use cli::run;
pub use error::{Error, Result};
