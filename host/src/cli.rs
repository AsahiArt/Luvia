use std::fs;
use std::io::{self, Read};
use std::path::PathBuf;

use clap::{Parser, Subcommand};

use crate::error::{Error, Result};
use crate::grant::{self, PublicGrant};
use crate::paths::Paths;
use crate::role::Role;
use crate::sshkey::parse_openssh_public_key;

/// Owner-local companion for Luvia.
///
/// Pair devices with one OpenSSH public key. sshd then runs
/// `luvia-host bridge --device <id>` through a `restrict` forced command.
/// The bridge discovers running Luvus sessions and proxies one UHP connection
/// after minting a short-lived delegated token for the device's role.
#[derive(Debug, Parser)]
#[command(
    name = "luvia-host",
    version,
    about = "Pair SSH devices and bridge Luvus UHP over a forced command"
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Read one OpenSSH public key from stdin and grant a durable device pairing
    Pair {
        /// Human-readable device name stored with the grant
        #[arg(long)]
        name: String,
        /// Observer (read) or controller (read, workspace, agent, terminal, orchestration)
        #[arg(long, value_enum)]
        role: Role,
    },
    /// List paired device grants without secrets
    Devices {
        /// Emit JSON instead of a table
        #[arg(long)]
        json: bool,
    },
    /// Remove a pairing grant and its managed authorized_keys line
    Revoke {
        /// Device id printed by `pair`
        id: String,
    },
    /// Forced-command UHP bridge. Invoked by sshd, not by the device client directly.
    Bridge {
        /// Paired device id supplied by the authorized_keys forced command
        #[arg(long)]
        device: String,
    },
}

pub fn run() -> Result<()> {
    let cli = Cli::parse();
    let paths = Paths::from_env()?;
    match cli.command {
        Command::Pair { name, role } => {
            let mut input = String::new();
            io::stdin().read_to_string(&mut input)?;
            let key = parse_openssh_public_key(&input)?;
            let exe = host_executable()?;
            let grant = grant::pair_device(&paths, &name, role, &key, &exe)?;
            println!(
                "paired {} {} {} {}",
                grant.id, grant.name, grant.role, grant.fingerprint
            );
            Ok(())
        }
        Command::Devices { json } => {
            let grants = grant::list_grants(&paths)?;
            let public: Vec<PublicGrant> = grants.iter().map(grant::Grant::to_public).collect();
            if json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&serde_json::json!({"devices": public}))?
                );
            } else if public.is_empty() {
                println!("no paired devices");
            } else {
                println!(
                    "{:<32} {:<16} {:<12} {}",
                    "id", "name", "role", "fingerprint"
                );
                for grant in public {
                    println!(
                        "{:<32} {:<16} {:<12} {}",
                        grant.id, grant.name, grant.role, grant.fingerprint
                    );
                }
            }
            Ok(())
        }
        Command::Revoke { id } => {
            let grant = grant::revoke_device(&paths, &id)?;
            println!("revoked {} {}", grant.id, grant.name);
            Ok(())
        }
        Command::Bridge { device } => crate::bridge::run(&paths, &device),
    }
}

fn host_executable() -> Result<PathBuf> {
    let exe = std::env::current_exe()?;
    let canonical = fs::canonicalize(&exe)?;
    if !canonical.is_absolute() {
        return Err(Error::new(
            "unsafe_path",
            "luvia-host executable path is not absolute",
        ));
    }
    Ok(canonical)
}
