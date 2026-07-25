use std::fmt;
use std::sync::LazyLock;
use std::time::{SystemTime, UNIX_EPOCH};

use iroh::{EndpointId, SecretKey};
use sha2::{Digest, Sha256};

use crate::constants::SESSION_ID_SIZE;

pub(crate) const SESSION_ID_BYTES: usize = SESSION_ID_SIZE as usize;
const SECRET_KEY_BYTES: usize = 32;

static SESSION_SECRET_KEY: LazyLock<SecretKey> = LazyLock::new(generate_secret_key);

#[derive(Clone, Copy, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub(crate) struct SessionId([u8; SESSION_ID_BYTES]);

impl SessionId {
    pub(crate) fn from_slice(bytes: &[u8]) -> Option<Self> {
        Some(Self(bytes.try_into().ok()?))
    }

    pub(crate) fn from_endpoint_id(endpoint_id: EndpointId) -> Self {
        let mut id = [0u8; SESSION_ID_BYTES];
        id.copy_from_slice(&endpoint_id.as_bytes()[..SESSION_ID_BYTES]);
        Self(id)
    }

    pub(crate) fn bytes(self) -> [u8; SESSION_ID_BYTES] {
        self.0
    }

    pub(crate) fn hex(self) -> String {
        hex::encode_upper(self.0)
    }

    pub(crate) fn short(self) -> String {
        self.hex()
    }
}

impl fmt::Debug for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.hex())
    }
}

impl fmt::Display for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.hex())
    }
}

pub(crate) fn session_secret_key() -> SecretKey {
    SESSION_SECRET_KEY.clone()
}

pub(crate) fn get_session_id() -> SessionId {
    SessionId::from_endpoint_id(session_secret_key().public())
}

fn generate_secret_key() -> SecretKey {
    let mut seed = [0u8; SECRET_KEY_BYTES];
    if getrandom::fill(&mut seed).is_ok() {
        return SecretKey::from_bytes(&seed);
    }

    let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_nanos()).unwrap_or_default();
    let marker = 0u8;
    let marker_addr = (&marker as *const u8 as usize).to_be_bytes();

    let mut hasher = Sha256::new();
    hasher.update(b"DOLE session secret key");
    hasher.update(now.to_be_bytes());
    hasher.update(std::process::id().to_be_bytes());
    hasher.update(marker_addr);

    seed.copy_from_slice(&hasher.finalize()[..SECRET_KEY_BYTES]);
    SecretKey::from_bytes(&seed)
}
