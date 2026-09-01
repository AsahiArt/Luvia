use serde_json::Value;
use std::collections::BTreeMap;

use crate::error::{Error, Result};
use crate::role::{self, Role, TokenKind};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Access {
    Read,
    Write,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MethodContract {
    pub method: String,
    pub access: Access,
    pub scope: String,
    pub idempotent: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Capabilities {
    pub contracts: BTreeMap<String, MethodContract>,
    pub connection_capacity: usize,
    pub frame_bytes: usize,
}

impl Capabilities {
    pub fn parse(value: &Value) -> Result<Self> {
        let root = if value.get("method_contracts").is_some() {
            value
        } else {
            value
                .get("result")
                .ok_or_else(|| Error::new("auth_failed", "uhp.capabilities returned no result"))?
        };
        let contracts_value = root
            .get("method_contracts")
            .and_then(Value::as_array)
            .ok_or_else(|| {
                Error::new(
                    "auth_failed",
                    "uhp.capabilities returned no method_contracts",
                )
            })?;
        let mut contracts = BTreeMap::new();
        for entry in contracts_value {
            let method = entry
                .get("method")
                .and_then(Value::as_str)
                .ok_or_else(|| Error::new("auth_failed", "method contract is missing method"))?;
            let access = match entry.get("access").and_then(Value::as_str) {
                Some("read") => Access::Read,
                Some("write") => Access::Write,
                _ => {
                    return Err(Error::new(
                        "auth_failed",
                        format!("method contract {method} has an unknown access"),
                    ));
                }
            };
            let scope = entry
                .get("scope")
                .and_then(Value::as_str)
                .ok_or_else(|| {
                    Error::new(
                        "auth_failed",
                        format!("method contract {method} is missing scope"),
                    )
                })?
                .to_string();
            let idempotent = entry
                .get("idempotent")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            contracts.insert(
                method.to_string(),
                MethodContract {
                    method: method.to_string(),
                    access,
                    scope,
                    idempotent,
                },
            );
        }
        if contracts.is_empty() {
            return Err(Error::new(
                "auth_failed",
                "uhp.capabilities returned an empty method allow-list",
            ));
        }
        let limits = root.get("limits").cloned().unwrap_or(Value::Null);
        let connection_capacity = limits
            .get("connection_capacity")
            .and_then(Value::as_u64)
            .unwrap_or(80) as usize;
        let frame_bytes = limits
            .get("frame_bytes")
            .and_then(Value::as_u64)
            .unwrap_or(1024 * 1024) as usize;
        if connection_capacity == 0 {
            return Err(Error::new(
                "auth_failed",
                "uhp.capabilities returned a zero connection_capacity",
            ));
        }
        Ok(Self {
            contracts,
            connection_capacity,
            frame_bytes,
        })
    }

    pub fn lookup(&self, method: &str) -> Option<&MethodContract> {
        self.contracts.get(method)
    }

    pub fn authorize(&self, role: Role, method: &str) -> Result<TokenKind> {
        let Some(contract) = self.lookup(method) else {
            return Err(Error::new(
                "forbidden",
                format!("unknown UHP method {method}"),
            ));
        };
        let kind = role::token_kind_for(method);
        if kind == TokenKind::Session {
            return Ok(TokenKind::Session);
        }
        let allowed = match contract.access {
            Access::Read => contract.scope != "admin",
            Access::Write => role
                .action_scopes()
                .iter()
                .any(|scope| *scope == contract.scope),
        };
        if allowed {
            Ok(TokenKind::Action)
        } else {
            Err(Error::new(
                "forbidden",
                format!("method {method} is not permitted for this device role"),
            ))
        }
    }
}

pub fn is_streaming(method: &str) -> bool {
    matches!(
        method,
        "events.subscribe"
            | "terminal.backend.events.subscribe"
            | "terminal.backend.observe"
            | "terminal.backend.control"
    )
}

pub fn is_bidirectional(method: &str) -> bool {
    method == "terminal.backend.control"
}

#[cfg(test)]
pub fn fixture() -> Capabilities {
    let json = serde_json::json!({
        "method_contracts": [
            {"method":"uhp.capabilities","access":"read","scope":"read","idempotent":true},
            {"method":"ping","access":"read","scope":"read","idempotent":true},
            {"method":"session.snapshot","access":"read","scope":"admin","idempotent":true},
            {"method":"events.subscribe","access":"read","scope":"admin","idempotent":false},
            {"method":"events.wait","access":"read","scope":"admin","idempotent":true},
            {"method":"server.stop","access":"write","scope":"admin","idempotent":false},
            {"method":"config.patch","access":"write","scope":"admin","idempotent":false},
            {"method":"workspace.list","access":"read","scope":"workspace","idempotent":true},
            {"method":"agent.list","access":"read","scope":"agent","idempotent":true},
            {"method":"task.list","access":"read","scope":"orchestration","idempotent":true},
            {"method":"terminal.backend.inventory","access":"read","scope":"terminal","idempotent":true},
            {"method":"terminal.backend.observe","access":"read","scope":"terminal","idempotent":false},
            {"method":"terminal.backend.control","access":"write","scope":"terminal","idempotent":false},
            {"method":"module.enable","access":"write","scope":"extensions","idempotent":false},
            {"method":"agent.start","access":"write","scope":"agent","idempotent":false}
        ],
        "limits": {"connection_capacity": 80, "frame_bytes": 1048576}
    });
    Capabilities::parse(&json).unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn observer_reads_non_admin_and_session_methods() {
        let caps = fixture();
        assert_eq!(
            caps.authorize(Role::Observer, "ping").unwrap(),
            TokenKind::Action
        );
        assert_eq!(
            caps.authorize(Role::Observer, "workspace.list").unwrap(),
            TokenKind::Action
        );
        assert_eq!(
            caps.authorize(Role::Observer, "terminal.backend.observe")
                .unwrap(),
            TokenKind::Action
        );
        assert_eq!(
            caps.authorize(Role::Observer, "session.snapshot").unwrap(),
            TokenKind::Session
        );
        assert_eq!(
            caps.authorize(Role::Observer, "events.subscribe").unwrap(),
            TokenKind::Session
        );
        assert_eq!(
            caps.authorize(Role::Observer, "server.stop")
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            caps.authorize(Role::Observer, "config.patch")
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            caps.authorize(Role::Observer, "terminal.backend.control")
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            caps.authorize(Role::Observer, "not.a.method")
                .unwrap_err()
                .code,
            "forbidden"
        );
    }

    #[test]
    fn controller_writes_in_role_domains_only() {
        let caps = fixture();
        assert_eq!(
            caps.authorize(Role::Controller, "terminal.backend.control")
                .unwrap(),
            TokenKind::Action
        );
        assert_eq!(
            caps.authorize(Role::Controller, "agent.start").unwrap(),
            TokenKind::Action
        );
        assert_eq!(
            caps.authorize(Role::Controller, "module.enable")
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            caps.authorize(Role::Controller, "server.stop")
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            caps.authorize(Role::Controller, "session.snapshot")
                .unwrap(),
            TokenKind::Session
        );
    }

    #[test]
    fn session_token_is_never_selected_for_other_admin_methods() {
        let caps = fixture();
        assert_eq!(role::token_kind_for("server.stop"), TokenKind::Action);
        assert_eq!(role::token_kind_for("config.patch"), TokenKind::Action);
        assert_eq!(role::token_kind_for("events.wait"), TokenKind::Action);
        assert!(caps.authorize(Role::Observer, "server.stop").is_err());
        assert!(caps.authorize(Role::Observer, "config.patch").is_err());
    }
}
