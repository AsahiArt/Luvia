use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use qrcode::render::unicode::Dense1x2;
use qrcode::{EcLevel, QrCode};
use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::grant::{self, Grant};
use crate::hostinfo::HostFacts;
use crate::role::Role;

const PREFIX: &str = "luvia2:";

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairingPayload {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fqdn: Option<String>,
    pub user: String,
    pub port: u16,
    pub bridge: String,
    pub hk: Vec<String>,
    pub hints: Vec<String>,
    pub host_id: String,
    pub device_id: String,
    pub device_key: String,
    pub role: Role,
}

impl PairingPayload {
    pub fn from_grant(grant: &Grant, facts: &HostFacts) -> Result<Self> {
        let payload = Self {
            name: facts.name.clone(),
            fqdn: facts.fqdn.clone(),
            user: facts.user.clone(),
            port: facts.port,
            bridge: facts.bridge.clone(),
            hk: facts.hk.clone(),
            hints: facts.addrs.clone(),
            host_id: facts.host_id.clone(),
            device_id: grant.id.clone(),
            device_key: grant.fingerprint.clone(),
            role: grant.role,
        };
        payload.validate()?;
        Ok(payload)
    }

    pub fn encode(&self) -> Result<String> {
        self.validate()?;
        let json = serde_json::to_vec(self)?;
        Ok(format!("{PREFIX}{}", URL_SAFE_NO_PAD.encode(json)))
    }

    pub fn decode(code: &str) -> Result<Self> {
        let rest = split_prefix(code.trim())?;
        let bytes = URL_SAFE_NO_PAD
            .decode(rest.as_bytes())
            .map_err(|_| Error::new("invalid_pairing", "pairing code is not valid base64url"))?;
        let payload: Self = serde_json::from_slice(&bytes)?;
        payload.validate()?;
        Ok(payload)
    }

    fn validate(&self) -> Result<()> {
        if self.hk.is_empty() {
            return Err(Error::new(
                "no_host_keys",
                "cannot emit a pairing code with an empty host-key pin set",
            ));
        }
        if self.hints.is_empty() && self.fqdn.is_none() {
            return Err(Error::new(
                "no_address",
                "cannot emit a pairing code with no hints and no tailnet name",
            ));
        }
        if self.name.is_empty() || self.user.is_empty() || self.port == 0 {
            return Err(Error::new(
                "invalid_pairing",
                "pairing payload name, user, and port are required",
            ));
        }
        if !std::path::Path::new(&self.bridge).is_absolute() {
            return Err(Error::new(
                "invalid_pairing",
                "bridge path must be absolute",
            ));
        }
        if !is_host_id(&self.host_id) {
            return Err(Error::new(
                "invalid_pairing",
                "host_id must be 32 lowercase hex characters",
            ));
        }
        grant::validate_device_id(&self.device_id)?;
        if self.device_key.is_empty() {
            return Err(Error::new("invalid_pairing", "device_key is required"));
        }
        Ok(())
    }
}

fn is_host_id(value: &str) -> bool {
    value.len() == 32
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

fn split_prefix(code: &str) -> Result<&str> {
    if code.len() >= 7 && code[..7].eq_ignore_ascii_case("luvia1:") {
        return Err(Error::new(
            "invalid_pairing",
            "luvia1: pairing codes are not accepted; use luvia2:",
        ));
    }
    if code.len() >= PREFIX.len() && code[..PREFIX.len()].eq_ignore_ascii_case(PREFIX) {
        return Ok(&code[PREFIX.len()..]);
    }
    Err(Error::new(
        "invalid_pairing",
        "pairing code must start with luvia2:",
    ))
}

pub fn pairing_code(grant: &Grant, facts: &HostFacts) -> Result<String> {
    PairingPayload::from_grant(grant, facts)?.encode()
}

pub fn render_qr(code: &str) -> Result<String> {
    let qr = QrCode::with_error_correction_level(code.as_bytes(), EcLevel::L).map_err(|error| {
        Error::new(
            "invalid_pairing",
            format!("cannot render pairing QR: {error}"),
        )
    })?;
    Ok(qr.render::<Dense1x2>().quiet_zone(true).build())
}

pub fn json_output(grant: &Grant, code: &str, payload: &PairingPayload) -> Result<String> {
    Ok(serde_json::to_string(&serde_json::json!({
        "id": grant.id,
        "code": code,
        "payload": payload,
    }))?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grant::Grant;

    fn grant() -> Grant {
        Grant {
            id: "ab".repeat(16),
            name: "phone".into(),
            role: Role::Observer,
            fingerprint: "SHA256:devicekey".into(),
            key_type: "ssh-ed25519".into(),
            key: "AAAA".into(),
            comment: String::new(),
            created_at: 0,
            push: None,
        }
    }

    fn facts() -> HostFacts {
        HostFacts {
            name: "studio".into(),
            user: "misaka".into(),
            port: 22,
            addrs: vec!["192.168.1.4".into(), "100.64.0.8".into()],
            hk: vec!["SHA256:hostkeyone".into(), "SHA256:hostkeytwo".into()],
            fqdn: None,
            bridge: "/usr/local/bin/luvia-host".into(),
            host_id: "cd".repeat(16),
        }
    }

    #[test]
    fn luvia2_payload_round_trips() {
        let mut facts = facts();
        facts.fqdn = Some("yui.java-tiyanki.ts.net".into());
        let payload = PairingPayload::from_grant(&grant(), &facts).unwrap();
        let code = payload.encode().unwrap();
        assert!(code.starts_with("luvia2:"));
        assert!(!code.contains('\n'));
        assert!(code
            .bytes()
            .all(|byte| byte.is_ascii() && byte != b'+' && byte != b'/'));
        let decoded = PairingPayload::decode(&code).unwrap();
        assert_eq!(decoded, payload);
        assert_eq!(decoded.fqdn.as_deref(), Some("yui.java-tiyanki.ts.net"));
        assert_eq!(decoded.role, Role::Observer);
        assert_eq!(decoded.hk.len(), 2);
        assert_eq!(decoded.hints, facts.addrs);
        assert_eq!(decoded.host_id, facts.host_id);
        assert_eq!(decoded.bridge, facts.bridge);
    }

    #[test]
    fn luvia1_is_rejected() {
        let err = PairingPayload::decode("luvia1:eyJ2IjoxfQ").unwrap_err();
        assert_eq!(err.code, "invalid_pairing");
        assert!(err.message.contains("luvia1:"));
        assert!(err.message.contains("luvia2:"));
        let err = PairingPayload::decode("https://example").unwrap_err();
        assert!(err.message.contains("must start with luvia2:"));
    }

    #[test]
    fn tailnet_name_ends_up_in_fqdn() {
        let mut facts = facts();
        facts.fqdn = Some("yui.java-tiyanki.ts.net".into());
        let payload = PairingPayload::from_grant(&grant(), &facts).unwrap();
        assert_eq!(payload.fqdn.as_deref(), Some("yui.java-tiyanki.ts.net"));
        let decoded = PairingPayload::decode(&payload.encode().unwrap()).unwrap();
        assert_eq!(decoded.fqdn, payload.fqdn);
    }

    #[test]
    fn pair_code_matches_pair_for_same_inputs() {
        let grant = grant();
        let facts = facts();
        let from_pair = pairing_code(&grant, &facts).unwrap();
        let from_pair_code = pairing_code(&grant, &facts).unwrap();
        assert_eq!(from_pair, from_pair_code);
        let payload = PairingPayload::decode(&from_pair).unwrap();
        assert_eq!(payload.device_id, grant.id);
        assert_eq!(payload.device_key, grant.fingerprint);
        assert_eq!(payload.hints, facts.addrs);
        assert_eq!(payload.hk, facts.hk);
        assert_eq!(payload.user, facts.user);
        assert_eq!(payload.port, facts.port);
        assert_eq!(payload.name, facts.name);
        assert_eq!(payload.fqdn, None);
    }

    #[test]
    fn empty_host_key_set_is_a_hard_error() {
        let mut bare = facts();
        bare.hk.clear();
        let err = PairingPayload::from_grant(&grant(), &bare).unwrap_err();
        assert_eq!(err.code, "no_host_keys");
        let mut payload = PairingPayload::from_grant(&grant(), &facts()).unwrap();
        payload.hk.clear();
        let err = payload.encode().unwrap_err();
        assert_eq!(err.code, "no_host_keys");
    }

    #[test]
    fn qr_fits_a_normal_terminal() {
        let code = pairing_code(&grant(), &facts()).unwrap();
        let qr = render_qr(&code).unwrap();
        assert!(qr.contains('█') || qr.contains('▀') || qr.contains('▄') || qr.contains(' '));
        assert!(qr.lines().count() > 4);
        let width = qr
            .lines()
            .map(|line| line.chars().count())
            .max()
            .unwrap_or(0);
        assert!(
            width <= 80,
            "QR is {width} columns wide; must fit a normal terminal"
        );
    }
}
