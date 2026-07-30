#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "windows")]
mod windows;

use crate::constants::BLE_SERVICE_DATA_UUID_LE;
use crate::logging;
use crate::network::session::SessionId;
use crate::network::{
    next_ble_advertising_payload, session_id_from_ble_payload, start_ble_advertiser_queue,
    stop_ble_advertiser_queue,
};

const LOG_TARGET: &str = "dole::ble";

pub(crate) fn next_payload(storage_path: &str, max_payload_bytes: usize) -> Option<Vec<u8>> {
    next_ble_advertising_payload(storage_path, max_payload_bytes)
}

pub(crate) fn ingest_payload(storage_path: &str, payload: &[u8]) -> bool {
    crate::network::ingest_ble_advertising_payload(storage_path, payload)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) fn session_id_from_payload_or_service_data(bytes: &[u8]) -> Option<SessionId> {
    let payload = bytes
        .strip_prefix(&BLE_SERVICE_DATA_UUID_LE)
        .unwrap_or(bytes);
    session_id_from_ble_payload(payload)
}

pub(super) fn advertiser_started(max_payload_bytes: usize) {
    crate::network::set_local_ble_payload_limit(max_payload_bytes);
    start_ble_advertiser_queue();
}

pub(super) fn advertiser_stopped() {
    stop_ble_advertiser_queue();
}

#[uniffi::export]
pub fn start_ble_advertising(storage_path: String) -> bool {
    logging::init_logging();
    platform::start(&storage_path)
}

pub(crate) fn advertising_active() -> bool {
    platform::is_advertising()
}

#[uniffi::export]
pub fn stop_ble_advertising() {
    logging::init_logging();
    platform::stop();
}

#[cfg(target_os = "android")]
mod platform {
    pub(super) use super::android::{is_advertising, start, stop};
}
#[cfg(target_os = "windows")]
mod platform {
    pub(super) use super::windows::{is_advertising, start, stop};
}
#[cfg(not(any(target_os = "android", target_os = "windows")))]
mod platform {
    pub(super) fn start(_storage_path: &str) -> bool {
        log::info!(target: super::LOG_TARGET, "BLE transport unavailable on this platform");
        false
    }

    pub(super) fn stop() {}

    pub(super) fn is_advertising() -> bool {
        false
    }
}
