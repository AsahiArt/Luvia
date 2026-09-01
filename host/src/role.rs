use clap::ValueEnum;
use serde::{Deserialize, Serialize};

/// Durable pairing roles. The action token uses these fixed scopes.
/// A separate session token (never sent to the device) carries `read`+`admin`
/// for `session.snapshot` and `events.subscribe` only.
#[derive(Clone, Copy, Debug, Eq, PartialEq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    Observer,
    Controller,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TokenKind {
    Session,
    Action,
}

pub const SESSION_TOKEN_METHODS: &[&str] = &["session.snapshot", "events.subscribe"];

impl Role {
    pub fn as_str(self) -> &'static str {
        match self {
            Role::Observer => "observer",
            Role::Controller => "controller",
        }
    }

    /// Action-token scopes for this role. Never includes `extensions`, `admin`, or `all`.
    pub fn action_scopes(self) -> &'static [&'static str] {
        match self {
            Role::Observer => &["read"],
            Role::Controller => &["read", "workspace", "agent", "terminal", "orchestration"],
        }
    }

    pub fn scopes(self) -> &'static [&'static str] {
        self.action_scopes()
    }

    pub fn session_scopes() -> &'static [&'static str] {
        &["read", "admin"]
    }
}

pub fn token_kind_for(method: &str) -> TokenKind {
    if SESSION_TOKEN_METHODS.contains(&method) {
        TokenKind::Session
    } else {
        TokenKind::Action
    }
}

impl std::fmt::Display for Role {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn observer_action_token_is_read_only() {
        assert_eq!(Role::Observer.action_scopes(), &["read"]);
        assert!(!Role::Observer.action_scopes().contains(&"admin"));
    }

    #[test]
    fn action_token_never_includes_admin_or_extensions() {
        assert_eq!(
            Role::Controller.action_scopes(),
            &["read", "workspace", "agent", "terminal", "orchestration"]
        );
        assert!(!Role::Controller.action_scopes().contains(&"admin"));
        assert!(!Role::Controller.action_scopes().contains(&"extensions"));
        assert!(!Role::Controller.action_scopes().contains(&"all"));
        assert!(!Role::Observer.action_scopes().contains(&"admin"));
    }

    #[test]
    fn session_token_is_only_selected_for_snapshot_and_subscribe() {
        assert_eq!(token_kind_for("session.snapshot"), TokenKind::Session);
        assert_eq!(token_kind_for("events.subscribe"), TokenKind::Session);
        for method in [
            "ping",
            "uhp.capabilities",
            "server.stop",
            "config.patch",
            "events.wait",
            "wait.output",
            "terminal.backend.control",
            "terminal.backend.observe",
            "workspace.list",
            "agent.start",
        ] {
            assert_eq!(
                token_kind_for(method),
                TokenKind::Action,
                "{method} must not select the session token"
            );
        }
        assert_eq!(Role::session_scopes(), &["read", "admin"]);
    }
}
