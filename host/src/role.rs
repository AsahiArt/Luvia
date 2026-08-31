use clap::ValueEnum;
use serde::{Deserialize, Serialize};

/// Durable pairing roles. Delegated UHP tokens use these fixed scopes.
#[derive(Clone, Copy, Debug, Eq, PartialEq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    Observer,
    Controller,
}

impl Role {
    pub fn as_str(self) -> &'static str {
        match self {
            Role::Observer => "observer",
            Role::Controller => "controller",
        }
    }

    /// Least-scope UHP auth for this role. Never includes `extensions`, `admin`, or `all`.
    pub fn scopes(self) -> &'static [&'static str] {
        match self {
            Role::Observer => &["read"],
            Role::Controller => &["read", "workspace", "agent", "terminal", "orchestration"],
        }
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
    fn observer_is_read_only() {
        assert_eq!(Role::Observer.scopes(), &["read"]);
    }

    #[test]
    fn controller_excludes_admin_and_extensions() {
        assert_eq!(
            Role::Controller.scopes(),
            &["read", "workspace", "agent", "terminal", "orchestration"]
        );
        assert!(!Role::Controller.scopes().contains(&"admin"));
        assert!(!Role::Controller.scopes().contains(&"extensions"));
        assert!(!Role::Controller.scopes().contains(&"all"));
    }
}
