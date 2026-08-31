use serde::de::{Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};
use std::collections::HashSet;
use std::fmt;

use crate::error::{Error, Result};

struct Unique;

impl<'de> Deserialize<'de> for Unique {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        deserializer.deserialize_any(UniqueVisitor)
    }
}

struct UniqueVisitor;

impl<'de> Visitor<'de> for UniqueVisitor {
    type Value = Unique;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("valid JSON without duplicate object keys")
    }

    fn visit_map<A>(self, mut map: A) -> std::result::Result<Unique, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut keys = HashSet::new();
        while let Some(key) = map.next_key::<String>()? {
            if !keys.insert(key.clone()) {
                return Err(serde::de::Error::custom(format!(
                    "duplicate object key: {key}"
                )));
            }
            map.next_value::<Unique>()?;
        }
        Ok(Unique)
    }

    fn visit_seq<A>(self, mut sequence: A) -> std::result::Result<Unique, A::Error>
    where
        A: SeqAccess<'de>,
    {
        while sequence.next_element::<Unique>()?.is_some() {}
        Ok(Unique)
    }

    fn visit_bool<E>(self, _: bool) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_i64<E>(self, _: i64) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_u64<E>(self, _: u64) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_f64<E>(self, _: f64) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_str<E>(self, _: &str) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_string<E>(self, _: String) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_none<E>(self) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_unit<E>(self) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_some<D>(self, deserializer: D) -> std::result::Result<Unique, D::Error>
    where
        D: Deserializer<'de>,
    {
        Unique::deserialize(deserializer)
    }
    fn visit_newtype_struct<D>(self, deserializer: D) -> std::result::Result<Unique, D::Error>
    where
        D: Deserializer<'de>,
    {
        Unique::deserialize(deserializer)
    }
    fn visit_bytes<E>(self, _: &[u8]) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
    fn visit_byte_buf<E>(self, _: Vec<u8>) -> std::result::Result<Unique, E> {
        Ok(Unique)
    }
}

pub fn reject_duplicate_keys(bytes: &[u8]) -> Result<()> {
    let mut deserializer = serde_json::Deserializer::from_slice(bytes);
    Unique::deserialize(&mut deserializer).map_err(|error| {
        Error::new(
            "invalid_json",
            format!("JSON contains duplicate object keys: {error}"),
        )
    })?;
    deserializer
        .end()
        .map_err(|error| Error::new("invalid_json", error.to_string()))?;
    Ok(())
}

pub fn parse_unique_value(bytes: &[u8]) -> Result<serde_json::Value> {
    reject_duplicate_keys(bytes)?;
    Ok(serde_json::from_slice(bytes)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn duplicate_keys_are_rejected() {
        assert!(reject_duplicate_keys(br#"{"a":1,"a":2}"#).is_err());
    }

    #[test]
    fn unique_keys_are_accepted() {
        reject_duplicate_keys(br#"{"version":1,"operation":"discover"}"#).unwrap();
    }
}
