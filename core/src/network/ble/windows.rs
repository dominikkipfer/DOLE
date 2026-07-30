use super::LOG_TARGET;
use crate::constants::BLE_SERVICE_DATA_UUID_LE;
use crate::network::session_id_from_ble_payload;

#[cfg(target_os = "windows")]
mod windows_state {
    pub(super) use std::sync::atomic::Ordering;
    use std::sync::{
        Mutex,
        atomic::{AtomicBool, AtomicU64},
    };
    use std::time::Duration;

    use crate::constants::{
        BLE_PUBLISHER_DWELL_MS, BLE_SCAN_STATUS_LOG_INTERVAL_MS, BLE_SERVICE_DATA_OVERHEAD_SIZE,
    };

    pub(super) const BLE_PAYLOAD_DWELL: Duration =
        Duration::from_millis(BLE_PUBLISHER_DWELL_MS as u64);
    pub(super) const BLE_SCAN_STATUS_LOG_INTERVAL: Duration =
        Duration::from_millis(BLE_SCAN_STATUS_LOG_INTERVAL_MS as u64);
    pub(super) const SERVICE_DATA_OVERHEAD_BYTES: u32 = BLE_SERVICE_DATA_OVERHEAD_SIZE as u32;

    pub(super) struct WindowsBleCapabilities {
        pub(super) extended_advertising: bool,
        pub(super) max_advertisement_data_length: u32,
        pub(super) phy_2m: bool,
        pub(super) coded_phy: bool,
    }

    pub(super) struct WindowsBleWatcher {
        pub(super) watcher:
            windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementWatcher,
        pub(super) received_token: i64,
    }

    pub(super) static WINDOWS_BLE_PUBLISHER: Mutex<
        Option<windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementPublisher>,
    > = Mutex::new(None);
    pub(super) static WINDOWS_BLE_WATCHER: Mutex<Option<WindowsBleWatcher>> = Mutex::new(None);
    pub(super) static WINDOWS_BLE_OWN_PAYLOAD: Mutex<Option<Vec<u8>>> = Mutex::new(None);
    pub(super) static WINDOWS_BLE_SEEN_SESSIONS: Mutex<Vec<String>> = Mutex::new(Vec::new());
    pub(super) static WINDOWS_BLE_STORAGE_PATH: Mutex<Option<String>> = Mutex::new(None);
    pub(super) static WINDOWS_BLE_ADVERTISING_ACTIVE: AtomicBool = AtomicBool::new(false);
    pub(super) static WINDOWS_BLE_SCAN_ACTIVE: AtomicBool = AtomicBool::new(false);
    pub(super) static WINDOWS_BLE_OBSERVED_PACKETS: AtomicU64 = AtomicU64::new(0);
    pub(super) static WINDOWS_BLE_OBSERVED_FRAMES: AtomicU64 = AtomicU64::new(0);
    pub(super) static WINDOWS_BLE_LAST_OBSERVED_PAYLOAD: Mutex<Option<Vec<u8>>> = Mutex::new(None);
}

#[cfg(target_os = "windows")]
use windows_state::*;

#[cfg(target_os = "windows")]
fn service_data_from_payload(payload: &[u8]) -> Vec<u8> {
    let mut service_data = Vec::with_capacity(BLE_SERVICE_DATA_UUID_LE.len() + payload.len());
    service_data.extend_from_slice(&BLE_SERVICE_DATA_UUID_LE);
    service_data.extend_from_slice(payload);
    service_data
}

#[cfg(target_os = "windows")]
fn init_windows_ble_runtime() {
    use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize};

    unsafe {
        let _ = RoInitialize(RO_INIT_MULTITHREADED);
    }
}

#[cfg(target_os = "windows")]
fn windows_ble_capabilities() -> windows::core::Result<WindowsBleCapabilities> {
    let adapter = windows::Devices::Bluetooth::BluetoothAdapter::GetDefaultAsync()?.join()?;
    Ok(WindowsBleCapabilities {
        extended_advertising: adapter.IsExtendedAdvertisingSupported()?,
        max_advertisement_data_length: adapter.MaxAdvertisementDataLength()?,
        phy_2m: adapter.IsLowEnergyUncoded2MPhySupported()?,
        coded_phy: adapter.IsLowEnergyCodedPhySupported()?,
    })
}

#[cfg(target_os = "windows")]
fn log_windows_ble_capabilities(capabilities: &WindowsBleCapabilities) {
    let max_payload_bytes =
        max_windows_ble_service_payload_bytes(capabilities.max_advertisement_data_length);
    log::info!(
        target: LOG_TARGET,
        "Windows BLE capabilities: extendedAdvertising={}, maxAdvertisementDataLength={}, maxServicePayloadBytes={}, phy2M={}, codedPhy={}",
        capabilities.extended_advertising,
        capabilities.max_advertisement_data_length,
        max_payload_bytes,
        capabilities.phy_2m,
        capabilities.coded_phy
    );
}

#[cfg(target_os = "windows")]
fn max_windows_ble_service_payload_bytes(max_advertisement_data_length: u32) -> u32 {
    max_advertisement_data_length.saturating_sub(SERVICE_DATA_OVERHEAD_BYTES)
}

#[cfg(target_os = "windows")]
pub(super) fn start(storage_path: &str) -> bool {
    init_windows_ble_runtime();

    let capabilities = match windows_ble_capabilities() {
        Ok(capabilities) => capabilities,
        Err(e) => {
            log::warn!(
                target: LOG_TARGET,
                "Windows BLE disabled because capabilities could not be read: {e:?}"
            );
            return false;
        }
    };

    if !capabilities.extended_advertising {
        log::warn!(
            target: LOG_TARGET,
            "Windows BLE disabled because extended advertising is not supported"
        );
        return false;
    }

    let max_payload_bytes =
        max_windows_ble_service_payload_bytes(capabilities.max_advertisement_data_length) as usize;

    if let Ok(mut storage_path_guard) = WINDOWS_BLE_STORAGE_PATH.lock() {
        *storage_path_guard = Some(storage_path.to_string());
    }
    if WINDOWS_BLE_ADVERTISING_ACTIVE.load(Ordering::Relaxed) {
        return start_windows_ble_watcher();
    }

    if !start_windows_ble_watcher() {
        clear_windows_ble_session_state();
        return false;
    }

    log_windows_ble_capabilities(&capabilities);
    WINDOWS_BLE_ADVERTISING_ACTIVE.store(true, Ordering::Relaxed);
    super::advertiser_started(max_payload_bytes);
    start_windows_ble_payload_worker(storage_path.to_string(), max_payload_bytes);
    true
}

#[cfg(target_os = "windows")]
fn set_windows_ble_advertising_payload(payload: Vec<u8>) -> bool {
    if is_windows_ble_current_payload(&payload) {
        return true;
    }

    let service_data = service_data_from_payload(&payload);

    let Ok(mut publisher_guard) = WINDOWS_BLE_PUBLISHER.lock() else {
        return false;
    };
    let reused = publisher_guard
        .as_ref()
        .is_some_and(|publisher| update_windows_ble_publisher(publisher, &service_data).is_ok());
    if !reused {
        if let Some(stale) = publisher_guard.take() {
            let _ = stale.Stop();
        }
        match start_windows_ble_publisher(&service_data) {
            Ok(publisher) => *publisher_guard = Some(publisher),
            Err(e) => {
                log::error!(target: LOG_TARGET, "Windows BLE advertising could not start: {e:?}");
                return false;
            }
        }
    }
    drop(publisher_guard);
    if let Ok(mut own_payload) = WINDOWS_BLE_OWN_PAYLOAD.lock() {
        *own_payload = Some(payload.clone());
    }
    true
}

#[cfg(target_os = "windows")]
fn windows_ble_service_data_section(
    service_data: &[u8],
) -> windows::core::Result<
    windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementDataSection,
> {
    use windows::Devices::Bluetooth::Advertisement::{
        BluetoothLEAdvertisementDataSection, BluetoothLEAdvertisementDataTypes,
    };
    use windows::Storage::Streams::DataWriter;

    let writer = DataWriter::new()?;
    writer.WriteBytes(service_data)?;
    let buffer = writer.DetachBuffer()?;
    BluetoothLEAdvertisementDataSection::Create(
        BluetoothLEAdvertisementDataTypes::ServiceData16BitUuids()?,
        &buffer,
    )
}

#[cfg(target_os = "windows")]
fn update_windows_ble_publisher(
    publisher: &windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementPublisher,
    service_data: &[u8],
) -> windows::core::Result<()> {
    publisher.Stop()?;
    let sections = publisher.Advertisement()?.DataSections()?;
    sections.Clear()?;
    sections.Append(&windows_ble_service_data_section(service_data)?)?;
    publisher.Start()
}

#[cfg(target_os = "windows")]
fn start_windows_ble_publisher(
    service_data: &[u8],
) -> windows::core::Result<
    windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementPublisher,
> {
    use windows::Devices::Bluetooth::Advertisement::{
        BluetoothLEAdvertisement, BluetoothLEAdvertisementPublisher,
    };

    let advertisement = BluetoothLEAdvertisement::new()?;
    advertisement
        .DataSections()?
        .Append(&windows_ble_service_data_section(service_data)?)?;

    let publisher = BluetoothLEAdvertisementPublisher::Create(&advertisement)?;
    publisher.SetUseExtendedAdvertisement(true)?;
    publisher.Start()?;
    Ok(publisher)
}

#[cfg(target_os = "windows")]
fn start_windows_ble_payload_worker(storage_path: String, max_payload_bytes: usize) {
    std::thread::spawn(move || {
        while WINDOWS_BLE_ADVERTISING_ACTIVE.load(Ordering::Relaxed) {
            std::thread::sleep(BLE_PAYLOAD_DWELL);
            if !WINDOWS_BLE_ADVERTISING_ACTIVE.load(Ordering::Relaxed) {
                break;
            }
            let Some(payload) = super::next_payload(&storage_path, max_payload_bytes) else {
                break;
            };
            set_windows_ble_advertising_payload(payload);
        }
    });
}

#[cfg(target_os = "windows")]
fn start_windows_ble_watcher() -> bool {
    use windows::Devices::Bluetooth::Advertisement::{
        BluetoothLEAdvertisementReceivedEventArgs, BluetoothLEAdvertisementWatcher,
        BluetoothLEScanningMode,
    };
    use windows::Foundation::TypedEventHandler;

    let mut watcher_guard = match WINDOWS_BLE_WATCHER.lock() {
        Ok(guard) => guard,
        Err(_) => return false,
    };
    if watcher_guard.is_some() {
        return true;
    }

    let watcher = (|| -> windows::core::Result<WindowsBleWatcher> {
        let watcher = BluetoothLEAdvertisementWatcher::new()?;
        watcher.SetAllowExtendedAdvertisements(true)?;
        watcher.SetScanningMode(BluetoothLEScanningMode::Active)?;

        let received_token = watcher.Received(&TypedEventHandler::<
            BluetoothLEAdvertisementWatcher,
            BluetoothLEAdvertisementReceivedEventArgs,
        >::new(|_sender, args| {
            if let Some(args) = args.as_ref() {
                log_windows_ble_received(args);
            }
            Ok(())
        }))?;

        watcher.Start()?;
        Ok(WindowsBleWatcher {
            watcher,
            received_token,
        })
    })();

    match watcher {
        Ok(watcher) => {
            *watcher_guard = Some(watcher);
            WINDOWS_BLE_SCAN_ACTIVE.store(true, Ordering::Relaxed);
            start_windows_ble_scan_status_logger();
            log::info!(target: LOG_TARGET, "RX Windows BLE scan started");
            true
        }
        Err(e) => {
            log::error!(target: LOG_TARGET, "Windows BLE scan could not start: {e:?}");
            false
        }
    }
}

#[cfg(target_os = "windows")]
const ABORTED_SCAN_STATE: &str = "aborted, bluetooth unavailable";

fn windows_ble_scan_state_label() -> &'static str {
    use windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementWatcherStatus as Status;

    let Ok(guard) = WINDOWS_BLE_WATCHER.lock() else {
        return "unknown";
    };
    let Some(watcher) = guard.as_ref() else {
        return "stopped";
    };
    match watcher.watcher.Status() {
        Ok(Status::Started) => "active",
        Ok(Status::Aborted) => ABORTED_SCAN_STATE,
        Ok(Status::Stopping) | Ok(Status::Stopped) => "stopped",
        Ok(Status::Created) => "not started",
        _ => "unknown",
    }
}

fn start_windows_ble_scan_status_logger() {
    std::thread::spawn(|| {
        while WINDOWS_BLE_SCAN_ACTIVE.load(Ordering::Relaxed) {
            std::thread::sleep(BLE_SCAN_STATUS_LOG_INTERVAL);
            if !WINDOWS_BLE_SCAN_ACTIVE.load(Ordering::Relaxed) {
                break;
            }

            if windows_ble_scan_state_label() == ABORTED_SCAN_STATE {
                log::warn!(
                    target: LOG_TARGET,
                    "Windows BLE stopping, radio turned off while scanning"
                );
                stop();
                break;
            }

            let packets = WINDOWS_BLE_OBSERVED_PACKETS.swap(0, Ordering::Relaxed);
            let frames = WINDOWS_BLE_OBSERVED_FRAMES.swap(0, Ordering::Relaxed);
            if packets > 0 {
                log::info!(
                    target: LOG_TARGET,
                    "RX Windows BLE scan active, packets={} frames={} intervalMs={}",
                    packets,
                    frames,
                    BLE_SCAN_STATUS_LOG_INTERVAL.as_millis()
                );
            }
        }
    });
}

#[cfg(target_os = "windows")]
fn log_windows_ble_received(
    args: &windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementReceivedEventArgs,
) {
    use windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementDataTypes;

    let advertisement = match args.Advertisement() {
        Ok(advertisement) => advertisement,
        Err(_) => return,
    };
    let data_type = match BluetoothLEAdvertisementDataTypes::ServiceData16BitUuids() {
        Ok(data_type) => data_type,
        Err(_) => return,
    };
    let sections = match advertisement.GetSectionsByType(data_type) {
        Ok(sections) => sections,
        Err(_) => return,
    };
    let section_count = sections.Size().unwrap_or_default();

    for index in 0..section_count {
        let section = match sections.GetAt(index) {
            Ok(section) => section,
            Err(_) => continue,
        };
        let buffer = match section.Data() {
            Ok(buffer) => buffer,
            Err(_) => continue,
        };
        let service_data = match read_windows_ble_buffer(buffer) {
            Ok(service_data) => service_data,
            Err(_) => continue,
        };
        let Some(payload) = windows_ble_payload_from_service_data(&service_data) else {
            continue;
        };
        if is_windows_ble_own_payload(payload) {
            continue;
        }

        count_windows_ble_observed_payload(payload);
        note_windows_ble_sender(payload);
        ingest_windows_ble_payload(payload);
    }
}

#[cfg(target_os = "windows")]
fn count_windows_ble_observed_payload(payload: &[u8]) {
    WINDOWS_BLE_OBSERVED_PACKETS.fetch_add(1, Ordering::Relaxed);

    let Ok(mut last_payload) = WINDOWS_BLE_LAST_OBSERVED_PAYLOAD.lock() else {
        return;
    };
    if last_payload.as_deref() == Some(payload) {
        return;
    }
    *last_payload = Some(payload.to_vec());
    WINDOWS_BLE_OBSERVED_FRAMES.fetch_add(1, Ordering::Relaxed);
}

#[cfg(target_os = "windows")]
fn ingest_windows_ble_payload(payload: &[u8]) -> bool {
    let Ok(storage_path_guard) = WINDOWS_BLE_STORAGE_PATH.lock() else {
        return false;
    };
    let Some(storage_path) = storage_path_guard.as_deref() else {
        return false;
    };

    super::ingest_payload(storage_path, payload)
}

#[cfg(target_os = "windows")]
fn read_windows_ble_buffer(
    buffer: windows::Storage::Streams::IBuffer,
) -> windows::core::Result<Vec<u8>> {
    use windows::Storage::Streams::DataReader;

    let reader = DataReader::FromBuffer(&buffer)?;
    let len = reader.UnconsumedBufferLength()?;
    let mut bytes = vec![0; len as usize];
    reader.ReadBytes(&mut bytes)?;
    Ok(bytes)
}

#[cfg(target_os = "windows")]
fn windows_ble_payload_from_service_data(service_data: &[u8]) -> Option<&[u8]> {
    let payload = service_data.strip_prefix(&BLE_SERVICE_DATA_UUID_LE)?;
    session_id_from_ble_payload(payload)?;
    Some(payload)
}

#[cfg(target_os = "windows")]
fn is_windows_ble_own_payload(payload: &[u8]) -> bool {
    session_id_from_ble_payload(payload)
        .is_some_and(|session_id| session_id == crate::network::session::get_session_id())
}

#[cfg(target_os = "windows")]
fn note_windows_ble_sender(payload: &[u8]) {
    let label = session_id_from_ble_payload(payload)
        .map(|session_id| session_id.short())
        .unwrap_or_else(|| "undecodable".to_string());
    let Ok(mut seen) = WINDOWS_BLE_SEEN_SESSIONS.lock() else {
        return;
    };
    if seen.iter().any(|known| known == &label) {
        return;
    }
    seen.push(label.clone());
    log::info!(
        target: LOG_TARGET,
        "Windows BLE receiving from session={} bytes={}",
        label,
        payload.len()
    );
}

#[cfg(target_os = "windows")]
fn is_windows_ble_current_payload(payload: &[u8]) -> bool {
    let Ok(own_payload) = WINDOWS_BLE_OWN_PAYLOAD.lock() else {
        return false;
    };

    own_payload
        .as_deref()
        .is_some_and(|own_payload| own_payload == payload)
}

#[cfg(target_os = "windows")]
pub(super) fn is_advertising() -> bool {
    use windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementPublisherStatus as Status;

    if !WINDOWS_BLE_ADVERTISING_ACTIVE.load(Ordering::Relaxed) {
        return false;
    }
    let Ok(guard) = WINDOWS_BLE_PUBLISHER.lock() else {
        return false;
    };
    let Some(publisher) = guard.as_ref() else {
        return false;
    };
    !matches!(
        publisher.Status(),
        Ok(Status::Aborted) | Ok(Status::Created)
    )
}

pub(super) fn stop() {
    WINDOWS_BLE_ADVERTISING_ACTIVE.store(false, Ordering::Relaxed);
    super::advertiser_stopped();
    if let Ok(mut publisher_guard) = WINDOWS_BLE_PUBLISHER.lock()
        && let Some(publisher) = publisher_guard.take()
    {
        let _ = publisher.Stop();
        log::info!(target: LOG_TARGET, "Windows BLE advertising stopped");
    }

    stop_windows_ble_watcher();
}

#[cfg(target_os = "windows")]
fn stop_windows_ble_watcher() {
    let mut stopped = false;
    if let Ok(mut watcher_guard) = WINDOWS_BLE_WATCHER.lock()
        && let Some(watcher) = watcher_guard.take()
    {
        WINDOWS_BLE_SCAN_ACTIVE.store(false, Ordering::Relaxed);
        let _ = watcher.watcher.RemoveReceived(watcher.received_token);
        let _ = watcher.watcher.Stop();
        stopped = true;
    }

    clear_windows_ble_session_state();
    if stopped {
        log::info!(target: LOG_TARGET, "Windows BLE scan stopped");
    }
}

#[cfg(target_os = "windows")]
fn clear_windows_ble_session_state() {
    if let Ok(mut own_payload) = WINDOWS_BLE_OWN_PAYLOAD.lock() {
        *own_payload = None;
    }
    if let Ok(mut seen) = WINDOWS_BLE_SEEN_SESSIONS.lock() {
        seen.clear();
    }
    if let Ok(mut storage_path) = WINDOWS_BLE_STORAGE_PATH.lock() {
        *storage_path = None;
    }
}

#[cfg(not(target_os = "windows"))]
pub(super) fn stop() {
    log::info!(target: LOG_TARGET, "BLE advertising stop requested");
}
