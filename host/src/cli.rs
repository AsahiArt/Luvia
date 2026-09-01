use std::fs;
use std::io::{self, Read};

use std::path::PathBuf;

use clap::{Parser, Subcommand};

use crate::error::{Error, Result};
use crate::grant::{self, PublicGrant};
use crate::hostinfo::HostFacts;
use crate::paircode::{self, PairingPayload};
use crate::paths::Paths;
use crate::role::Role;
use crate::sshkey::parse_openssh_public_key;

/// Owner-local companion for Luvia on macOS and Linux.
///
/// Pair devices with one OpenSSH public key. sshd then runs
/// `luvia-host bridge --device <id>` through a `restrict` forced command.
/// The bridge discovers running Luvus sessions and proxies one UHP connection
/// after minting a short-lived delegated token for the device's role.
///
/// Windows is not supported.
#[derive(Debug, Parser)]
#[command(
    name = "luvia-host",
    version,
    about = "Pair SSH devices and bridge Luvus UHP over a forced command (macOS and Linux only; Windows is not supported)"
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Grant a durable device pairing from one OpenSSH public key
    Pair {
        /// Human-readable device name stored with the grant
        #[arg(long)]
        name: String,
        /// Observer (read) or controller (read, workspace, agent, terminal, orchestration)
        #[arg(long, value_enum)]
        role: Role,
        /// OpenSSH public key. If omitted, the key is read from stdin.
        #[arg(long, value_name = "OPENSSH_PUBLIC_KEY")]
        key: Option<String>,
        /// Reachable host address, best-first. Repeatable. Overrides auto-detection.
        #[arg(long = "address", value_name = "ADDR")]
        addresses: Vec<String>,
        /// SSH port. Overrides sshd_config / default 22.
        #[arg(long)]
        port: Option<u16>,
        /// Do not print the Unicode QR block
        #[arg(long)]
        no_qr: bool,
        /// Emit JSON `{"id","code","payload"}` instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Re-print the pairing QR and luvia1: payload for an existing grant
    PairCode {
        /// Device id printed by `pair`
        id: String,
        /// Reachable host address, best-first. Repeatable. Overrides auto-detection.
        #[arg(long = "address", value_name = "ADDR")]
        addresses: Vec<String>,
        /// SSH port. Overrides sshd_config / default 22.
        #[arg(long)]
        port: Option<u16>,
        /// Do not print the Unicode QR block
        #[arg(long)]
        no_qr: bool,
        /// Emit JSON `{"id","code","payload"}` instead of human output
        #[arg(long)]
        json: bool,
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
        Command::Pair {
            name,
            role,
            key,
            addresses,
            port,
            no_qr,
            json,
        } => {
            let key = parse_openssh_public_key(&read_public_key(key)?)?;
            let exe = host_executable()?;
            let grant = grant::pair_device(&paths, &name, role, &key, &exe)?;
            let _ = crate::audit::paired(&paths, &grant);
            let facts = HostFacts::collect(&addresses, port)?;
            emit_pairing(&grant, &facts, no_qr, json)
        }
        Command::PairCode {
            id,
            addresses,
            port,
            no_qr,
            json,
        } => {
            let grant = grant::load_grant(&paths, &id)?;
            let facts = HostFacts::collect(&addresses, port)?;
            emit_pairing(&grant, &facts, no_qr, json)
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
                println!("{:<32} {:<16} {:<12} fingerprint", "id", "name", "role");
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
            let _ = crate::audit::revoked(&paths, &grant);
            println!("revoked {} {}", grant.id, grant.name);
            Ok(())
        }
        Command::Bridge { device } => crate::bridge::run(&paths, &device),
    }
}

fn read_public_key(key: Option<String>) -> Result<String> {
    if let Some(key) = key {
        let trimmed = key.trim();
        if trimmed.is_empty() {
            return Err(Error::new("invalid_key", "public key flag is empty"));
        }
        return Ok(key);
    }
    let mut input = String::new();
    io::stdin().read_to_string(&mut input)?;
    Ok(input)
}

fn emit_pairing(grant: &grant::Grant, facts: &HostFacts, no_qr: bool, json: bool) -> Result<()> {
    let payload = PairingPayload::from_grant(grant, facts)?;
    let code = payload.encode()?;
    if json {
        println!("{}", paircode::json_output(grant, &code, &payload)?);
        return Ok(());
    }
    println!(
        "paired {} {} {} {}",
        grant.id, grant.name, grant.role, grant.fingerprint
    );
    println!(
        "host {} user {} port {} addresses {}",
        facts.name,
        facts.user,
        facts.port,
        facts.addrs.join(",")
    );
    println!("Scan this QR code in the Luvia app.");
    if !no_qr {
        match paircode::render_qr(&code) {
            Ok(qr) => println!("{qr}"),
            Err(error) => eprintln!("luvia-host: {error}"),
        }
    }
    println!("{code}");
    Ok(())
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

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    #[test]
    fn pair_accepts_app_generated_command() {
        let cli = Cli::try_parse_from([
            "luvia-host",
            "pair",
            "--name",
            "iPhone",
            "--role",
            "controller",
            "--key",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJustATestKeyNotReal========== comment",
        ])
        .expect("app-generated pair command must parse");
        match cli.command {
            Command::Pair {
                name, role, key, ..
            } => {
                assert_eq!(name, "iPhone");
                assert_eq!(role, Role::Controller);
                assert_eq!(
                    key.as_deref(),
                    Some("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJustATestKeyNotReal========== comment")
                );
            }
            other => panic!("expected pair, got {other:?}"),
        }
    }

    #[test]
    fn help_states_macos_linux_only() {
        let mut cmd = Cli::command();
        let mut buf = Vec::new();
        cmd.write_help(&mut buf).unwrap();
        let help = String::from_utf8(buf).unwrap();
        assert!(help.contains("macOS and Linux only"));
        assert!(help.contains("Windows is not supported"));
    }
}
