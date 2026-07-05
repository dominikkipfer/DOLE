#[cfg(target_os = "android")]
mod android;
#[cfg(any(target_os = "ios", target_os = "macos"))]
mod apple;
#[cfg(target_os = "windows")]
mod windows;

use std::sync::{LazyLock, Mutex};

use crate::constants::BLE_SERVICE_DATA_UUID_LE;
use crate::logging;
use crate::network::session::SessionId;
use crate::network::{
    build_initial_ble_advertising_payload, next_ble_advertising_payload,
    session_id_from_ble_payload, start_ble_advertiser_queue, stop_ble_advertiser_queue
};

const LOG_TARGET: &str = "dole::ble";
static LAST_TX_LOGGED_PAYLOAD: LazyLock<Mutex<Option<Vec<u8>>>> =
    LazyLock::new(|| Mutex::new(None));

pub(crate) fn build_initial_payload(storage_path: &str) -> Vec<u8> {
    let payload = build_initial_ble_advertising_payload(storage_path);
    log_tx_payload("TX BLE presence", &payload);
    payload
}

pub(crate) fn next_payload(storage_path: &str, max_payload_bytes: usize) -> Option<Vec<u8>> {
    let payload = next_ble_advertising_payload(storage_path, max_payload_bytes)?;
    log_tx_payload("TX BLE payload", &payload);
    Some(payload)
}

pub(crate) fn ingest_payload(storage_path: &str, payload: &[u8]) -> bool {
    crate::network::ingest_ble_advertising_payload(storage_path, payload)
}

pub(crate) fn session_id_from_payload_or_service_data(bytes: &[u8]) -> Option<SessionId> {
    let payload = bytes.strip_prefix(&BLE_SERVICE_DATA_UUID_LE).unwrap_or(bytes);
    session_id_from_ble_payload(payload)
}

pub(super) fn advertiser_started() {
    start_ble_advertiser_queue();
}

pub(super) fn advertiser_stopped() {
    stop_ble_advertiser_queue();
    reset_tx_payload_log();
}

fn log_tx_payload(label: &str, payload: &[u8]) {
    let Ok(mut last) = LAST_TX_LOGGED_PAYLOAD.lock() else {
        log_payload(label, payload);
        return;
    };
    if last.as_deref() == Some(payload) {
        return;
    }
    *last = Some(payload.to_vec());
    log_payload(label, payload);
}

fn reset_tx_payload_log() {
    if let Ok(mut last) = LAST_TX_LOGGED_PAYLOAD.lock() {
        *last = None;
    }
}

pub(crate) fn log_payload(label: &str, payload: &[u8]) {
    let session = session_id_from_payload_or_service_data(payload)
        .map(|session| session.short())
        .unwrap_or_else(|| "unknown".to_string());
    log::debug!(
        target: LOG_TARGET,
        "{} session={} payloadBytes={}",
        label,
        session,
        payload.len()
    );
}

#[uniffi::export]
pub fn start_ble_advertising(storage_path: String) -> bool {
    logging::init_logging();
    platform::start(&storage_path)
}

#[uniffi::export]
pub fn stop_ble_advertising() {
    logging::init_logging();
    platform::stop();
}

#[cfg(target_os = "android")]
mod platform {
    pub(super) use super::android::{start, stop};
}
#[cfg(target_os = "windows")]
mod platform {
    pub(super) use super::windows::{start, stop};
}
#[cfg(any(target_os = "ios", target_os = "macos"))]
mod platform {
    pub(super) use super::apple::{start, stop};
}
