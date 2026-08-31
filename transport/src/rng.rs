use russh::keys::ssh_key::rand_core::{Infallible, TryCryptoRng, TryRng};

/// OS entropy that implements ssh-key 0.7 / rand_core 0.10 `CryptoRng`.
pub struct OsCryptoRng;

impl TryRng for OsCryptoRng {
    type Error = Infallible;

    fn try_next_u32(&mut self) -> Result<u32, Self::Error> {
        let mut bytes = [0u8; 4];
        fill(&mut bytes);
        Ok(u32::from_le_bytes(bytes))
    }

    fn try_next_u64(&mut self) -> Result<u64, Self::Error> {
        let mut bytes = [0u8; 8];
        fill(&mut bytes);
        Ok(u64::from_le_bytes(bytes))
    }

    fn try_fill_bytes(&mut self, dst: &mut [u8]) -> Result<(), Self::Error> {
        fill(dst);
        Ok(())
    }
}

impl TryCryptoRng for OsCryptoRng {}

fn fill(dst: &mut [u8]) {
    getrandom::fill(dst).expect("operating-system entropy");
}

#[cfg(test)]
mod tests {
    use super::*;
    use russh::keys::ssh_key::rand_core::{CryptoRng, Rng};

    #[test]
    fn fills_nonzero_entropy() {
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        OsCryptoRng.try_fill_bytes(&mut a).unwrap();
        OsCryptoRng.try_fill_bytes(&mut b).unwrap();
        assert_ne!(a, [0u8; 32]);
        assert_ne!(a, b);
        let _crypto: &mut dyn CryptoRng = &mut OsCryptoRng;
        let _rng: &mut dyn Rng = &mut OsCryptoRng;
    }
}
