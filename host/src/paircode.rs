use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use qrcode::render::unicode::Dense1x2;
use qrcode::{EcLevel, QrCode};
use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::grant::Grant;
use crate::hostinfo::HostFacts;
use crate::role::Role;

const PREFIX: &str = "luvia1:";

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PairingPayload {
    pub v: u32,
    pub id: String,
    pub dk: String,
    pub name: String,
    pub user: String,
    pub port: u16,
    pub addrs: Vec<String>,
    pub hk: Vec<String>,
    pub role: Role,
}

impl PairingPayload {
    pub fn from_grant(grant: &Grant, facts: &HostFacts) -> Result<Self> {
        if facts.hk.is_empty() {
            return Err(Error::new(
                "no_host_keys",
                "cannot emit a pairing code with an empty host-key pin set",
            ));
        }
        if facts.addrs.is_empty() {
            return Err(Error::new(
                "no_address",
                "cannot emit a pairing code with an empty address list",
            ));
        }
        Ok(Self {
            v: 1,
            id: grant.id.clone(),
            dk: grant.fingerprint.clone(),
            name: facts.name.clone(),
            user: facts.user.clone(),
            port: facts.port,
            addrs: facts.addrs.clone(),
            hk: facts.hk.clone(),
            role: grant.role,
        })
    }

    pub fn encode(&self) -> Result<String> {
        if self.v != 1 {
            return Err(Error::new(
                "invalid_pairing",
                "pairing payload version must be 1",
            ));
        }
        if self.hk.is_empty() {
            return Err(Error::new(
                "no_host_keys",
                "cannot emit a pairing code with an empty host-key pin set",
            ));
        }
        let json = serde_json::to_vec(self)?;
        Ok(format!("{PREFIX}{}", URL_SAFE_NO_PAD.encode(json)))
    }

    pub fn decode(code: &str) -> Result<Self> {
        let rest = code
            .strip_prefix(PREFIX)
            .ok_or_else(|| Error::new("invalid_pairing", "pairing code must start with luvia1:"))?;
        let bytes = URL_SAFE_NO_PAD
            .decode(rest.as_bytes())
            .map_err(|_| Error::new("invalid_pairing", "pairing code is not valid base64url"))?;
        let payload: Self = serde_json::from_slice(&bytes)?;
        if payload.v != 1 {
            return Err(Error::new(
                "invalid_pairing",
                "pairing payload version must be 1",
            ));
        }
        Ok(payload)
    }
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
        }
    }

    fn facts() -> HostFacts {
        HostFacts {
            name: "studio".into(),
            user: "misaka".into(),
            port: 22,
            addrs: vec!["192.168.1.4".into(), "studio.local".into()],
            hk: vec!["SHA256:hostkeyone".into(), "SHA256:hostkeytwo".into()],
        }
    }

    #[test]
    fn luvia1_payload_round_trips() {
        let payload = PairingPayload::from_grant(&grant(), &facts()).unwrap();
        let code = payload.encode().unwrap();
        assert!(code.starts_with("luvia1:"));
        assert!(!code.contains('\n'));
        assert!(code
            .bytes()
            .all(|byte| byte.is_ascii() && byte != b'+' && byte != b'/'));
        let decoded = PairingPayload::decode(&code).unwrap();
        assert_eq!(decoded, payload);
        assert_eq!(decoded.v, 1);
        assert_eq!(decoded.role, Role::Observer);
        assert_eq!(decoded.hk.len(), 2);
    }

    #[test]
    fn pair_code_matches_pair_for_same_inputs() {
        let grant = grant();
        let facts = facts();
        let from_pair = pairing_code(&grant, &facts).unwrap();
        let from_pair_code = pairing_code(&grant, &facts).unwrap();
        assert_eq!(from_pair, from_pair_code);
        let payload = PairingPayload::decode(&from_pair).unwrap();
        assert_eq!(payload.id, grant.id);
        assert_eq!(payload.dk, grant.fingerprint);
        assert_eq!(payload.addrs, facts.addrs);
        assert_eq!(payload.hk, facts.hk);
        assert_eq!(payload.user, facts.user);
        assert_eq!(payload.port, facts.port);
        assert_eq!(payload.name, facts.name);
    }

    #[test]
    fn empty_host_key_set_is_a_hard_error() {
        let mut facts = facts();
        facts.hk.clear();
        let err = PairingPayload::from_grant(&grant(), &facts).unwrap_err();
        assert_eq!(err.code, "no_host_keys");
        let payload = PairingPayload {
            v: 1,
            id: "x".into(),
            dk: "SHA256:x".into(),
            name: "h".into(),
            user: "u".into(),
            port: 22,
            addrs: vec!["127.0.0.1".into()],
            hk: Vec::new(),
            role: Role::Controller,
        };
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
