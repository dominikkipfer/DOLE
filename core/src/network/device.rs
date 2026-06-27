use sha2::{Digest, Sha256};
use std::fmt;
use std::sync::LazyLock;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::constants::DEVICE_ID_SIZE;

pub const DEVICE_ID_BYTES: usize = DEVICE_ID_SIZE as usize;
static SESSION_DEVICE_ID: LazyLock<DeviceId> = LazyLock::new(generate_device_id);

#[derive(Clone, Copy, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct DeviceId([u8; DEVICE_ID_BYTES]);

impl DeviceId {
    pub fn from_slice(bytes: &[u8]) -> Option<Self> {
        Some(Self(bytes.try_into().ok()?))
    }

    pub fn bytes(self) -> [u8; DEVICE_ID_BYTES] {
        self.0
    }

    pub fn hex(self) -> String {
        hex::encode_upper(self.0)
    }

    pub fn short(self) -> String {
        let hex = self.hex();
        hex[..10.min(hex.len())].to_string()
    }
}

impl fmt::Debug for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.hex())
    }
}

impl fmt::Display for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.hex())
    }
}

pub fn get_session_device_id() -> DeviceId {
    *SESSION_DEVICE_ID
}

fn generate_device_id() -> DeviceId {
    let mut id = [0u8; DEVICE_ID_BYTES];
    if getrandom::fill(&mut id).is_ok() {
        return DeviceId(id);
    }

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let marker = 0u8;
    let marker_addr = (&marker as *const u8 as usize).to_be_bytes();

    let mut hasher = Sha256::new();
    hasher.update(b"DOLE session device id");
    hasher.update(now.to_be_bytes());
    hasher.update(std::process::id().to_be_bytes());
    hasher.update(marker_addr);

    let digest = hasher.finalize();
    DeviceId::from_slice(&digest[..DEVICE_ID_BYTES]).expect("digest is long enough")
}