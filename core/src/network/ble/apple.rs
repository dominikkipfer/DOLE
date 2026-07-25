use std::ops::Deref;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use objc2::rc::Retained;
use objc2::runtime::{AnyObject, NSObject, NSObjectProtocol, ProtocolObject};
use objc2::{AnyThread, define_class, extern_methods};
use objc2_core_bluetooth::{
    CBAdvertisementDataLocalNameKey, CBAdvertisementDataServiceDataKey,
    CBAdvertisementDataServiceUUIDsKey, CBCentralManager, CBCentralManagerDelegate,
    CBCentralManagerScanOptionAllowDuplicatesKey, CBManagerState, CBPeripheral,
    CBPeripheralManager, CBPeripheralManagerDelegate, CBUUID
};
use objc2_foundation::{NSArray, NSData, NSDictionary, NSError, NSNumber, NSString};

use crate::constants::{BLE_PAYLOAD_DWELL_MS, BLE_SCAN_STATUS_LOG_INTERVAL_MS, BLE_SERVICE_DATA_OVERHEAD_SIZE};

use super::{
    LOG_TARGET, advertiser_started, advertiser_stopped, build_initial_payload, ingest_payload,
    log_payload, next_payload
};

const APPLE_BLE_SERVICE_UUID_16: &str = "D01E";
const SCAN_STATUS_LOG_INTERVAL: Duration = Duration::from_millis(BLE_SCAN_STATUS_LOG_INTERVAL_MS as u64);
const BLE_PAYLOAD_DWELL: Duration = Duration::from_millis(BLE_PAYLOAD_DWELL_MS as u64);
const APPLE_BLE_MAX_ADVERTISEMENT_DATA_LENGTH: usize = 251;

static STATE: LazyLock<Mutex<AppleBleState>> = LazyLock::new(|| Mutex::new(AppleBleState::default()));
static APPLE_BLE_OBSERVED_ADVERTISEMENTS: AtomicU64 = AtomicU64::new(0);
static APPLE_BLE_DOLE_RX_EVENTS: AtomicU64 = AtomicU64::new(0);
static APPLE_BLE_LAST_DOLE_RX_MS: AtomicU64 = AtomicU64::new(0);
static APPLE_BLE_LOGGER_EPOCH: AtomicU64 = AtomicU64::new(0);
static APPLE_BLE_ADVERTISING_ACTIVE: AtomicBool = AtomicBool::new(false);
static APPLE_BLE_RADIO_ON: AtomicBool = AtomicBool::new(false);
static APPLE_BLE_TX_EPOCH: AtomicU64 = AtomicU64::new(0);

struct SendRetained<T: ?Sized>(Retained<T>);

unsafe impl<T: ?Sized> Send for SendRetained<T> {}
unsafe impl<T: ?Sized> Sync for SendRetained<T> {}

impl<T: ?Sized> Deref for SendRetained<T> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

#[derive(Default)]
struct AppleBleState {
    storage_path: Option<String>,
    delegate: Option<SendRetained<AppleBleDelegate>>,
    peripheral_manager: Option<SendRetained<CBPeripheralManager>>,
    central_manager: Option<SendRetained<CBCentralManager>>,
    current_payload: Option<Vec<u8>>,
    active: bool
}

define_class!(
    #[unsafe(super(NSObject))]
    #[name = "DoleAppleBleDelegate"]
    struct AppleBleDelegate;

    unsafe impl NSObjectProtocol for AppleBleDelegate {}

    unsafe impl CBPeripheralManagerDelegate for AppleBleDelegate {
        #[unsafe(method(peripheralManagerDidUpdateState:))]
        unsafe fn peripheral_manager_did_update_state(&self, peripheral: &CBPeripheralManager) {
            match unsafe { peripheral.state() } {
                CBManagerState::PoweredOn => {
                    APPLE_BLE_RADIO_ON.store(true, Ordering::Relaxed);
                    start_apple_advertising(peripheral);
                }
                state => {
                    APPLE_BLE_RADIO_ON.store(false, Ordering::Relaxed);
                    log::warn!(target: LOG_TARGET, "Apple BLE peripheral manager unavailable state={state:?}");
                }
            }
        }

        #[unsafe(method(peripheralManagerDidStartAdvertising:error:))]
        unsafe fn peripheral_manager_did_start_advertising_error(
            &self, _peripheral: &CBPeripheralManager, error: Option<&NSError>
        ) {
            match error {
                Some(error) => {
                    log::warn!(target: LOG_TARGET, "Apple BLE advertising failed: {error:?}");
                }
                None => log::debug!(target: LOG_TARGET, "Apple BLE advertising started")
            }
        }
    }

    unsafe impl CBCentralManagerDelegate for AppleBleDelegate {
        #[unsafe(method(centralManagerDidUpdateState:))]
        unsafe fn central_manager_did_update_state(&self, central: &CBCentralManager) {
            match unsafe { central.state() } {
                CBManagerState::PoweredOn => start_scan(central),
                state => {
                    log::warn!(target: LOG_TARGET, "Apple BLE central manager unavailable state={state:?}");
                }
            }
        }

        #[unsafe(method(centralManager:didDiscoverPeripheral:advertisementData:RSSI:))]
        unsafe fn central_manager_did_discover_peripheral_advertisement_data_rssi(
            &self,
            _central: &CBCentralManager,
            _peripheral: &CBPeripheral,
            advertisement_data: &NSDictionary<NSString, AnyObject>,
            _rssi: &NSNumber
        ) {
            APPLE_BLE_OBSERVED_ADVERTISEMENTS.fetch_add(1, Ordering::Relaxed);
            ingest_advertised_service_data(advertisement_data);
        }
    }
);

impl AppleBleDelegate {
    extern_methods!(
        #[unsafe(method(new))]
        #[unsafe(method_family = new)]
        fn new() -> Retained<Self>;
    );
}

pub(super) fn start(storage_path: &str) -> bool {
    stop();

    let initial_payload = build_initial_payload(storage_path);
    let max_payload_bytes =
        APPLE_BLE_MAX_ADVERTISEMENT_DATA_LENGTH.saturating_sub(BLE_SERVICE_DATA_OVERHEAD_SIZE as usize);
    if initial_payload.len() > max_payload_bytes {
        log::warn!(
            target: LOG_TARGET,
            "Apple BLE advertising skipped because payload is too large, payloadBytes={}, maxServicePayloadBytes={}",
            initial_payload.len(),
            max_payload_bytes
        );
        return false;
    }

    let delegate = AppleBleDelegate::new();
    let peripheral_delegate: &ProtocolObject<dyn CBPeripheralManagerDelegate> = ProtocolObject::from_ref(&*delegate);
    let central_delegate: &ProtocolObject<dyn CBCentralManagerDelegate> = ProtocolObject::from_ref(&*delegate);

    let peripheral_manager = unsafe {
        CBPeripheralManager::initWithDelegate_queue(CBPeripheralManager::alloc(), Some(peripheral_delegate), None)
    };
    let central_manager = unsafe {
        CBCentralManager::initWithDelegate_queue(CBCentralManager::alloc(), Some(central_delegate), None)
    };

    let Ok(mut state) = STATE.lock() else {
        return false;
    };
    state.storage_path = Some(storage_path.to_string());
    state.delegate = Some(SendRetained(delegate));
    state.peripheral_manager = Some(SendRetained(peripheral_manager));
    state.central_manager = Some(SendRetained(central_manager));
    state.current_payload = Some(initial_payload);
    state.active = true;
    drop(state);

    APPLE_BLE_OBSERVED_ADVERTISEMENTS.store(0, Ordering::Relaxed);
    APPLE_BLE_DOLE_RX_EVENTS.store(0, Ordering::Relaxed);
    APPLE_BLE_LAST_DOLE_RX_MS.store(0, Ordering::Relaxed);
    APPLE_BLE_ADVERTISING_ACTIVE.store(true, Ordering::Relaxed);
    advertiser_started();
    start_payload_rotation_worker(storage_path.to_string(), max_payload_bytes);
    start_scan_status_logger();

    log::info!(target: LOG_TARGET, "Apple BLE advertising backend starting");
    true
}

fn start_payload_rotation_worker(storage_path: String, max_payload_bytes: usize) {
    let epoch = APPLE_BLE_TX_EPOCH.fetch_add(1, Ordering::Relaxed) + 1;
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(BLE_PAYLOAD_DWELL);
            if APPLE_BLE_TX_EPOCH.load(Ordering::Relaxed) != epoch
                || !APPLE_BLE_ADVERTISING_ACTIVE.load(Ordering::Relaxed)
            {
                return;
            }
            let Some(payload) = next_payload(&storage_path, max_payload_bytes) else {
                return;
            };
            if APPLE_BLE_TX_EPOCH.load(Ordering::Relaxed) != epoch {
                return;
            }
            update_apple_advertising_payload(payload);
        }
    });
}

fn update_apple_advertising_payload(payload: Vec<u8>) {
    let Ok(mut state) = STATE.lock() else {
        return;
    };
    if !state.active {
        return;
    }
    if state.current_payload.as_deref() == Some(payload.as_slice()) {
        return;
    }

    let advertisement = build_apple_advertisement(&payload);
    state.current_payload = Some(payload);
    let Some(manager) = state.peripheral_manager.as_ref() else {
        return;
    };
    unsafe {
        manager.stopAdvertising();
        manager.startAdvertising(Some(&advertisement));
    }
}

fn start_scan_status_logger() {
    let epoch = APPLE_BLE_LOGGER_EPOCH.fetch_add(1, Ordering::Relaxed) + 1;
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(SCAN_STATUS_LOG_INTERVAL);
            if APPLE_BLE_LOGGER_EPOCH.load(Ordering::Relaxed) != epoch || !is_active() {
                return;
            }

            let observed = APPLE_BLE_OBSERVED_ADVERTISEMENTS.load(Ordering::Relaxed);
            let dole_rx = APPLE_BLE_DOLE_RX_EVENTS.load(Ordering::Relaxed);
            let last_rx = APPLE_BLE_LAST_DOLE_RX_MS.load(Ordering::Relaxed);
            let rx_state = if last_rx == 0 {
                "no DOLE advertising received yet".to_string()
            } else {
                let age = now_millis().saturating_sub(last_rx);
                format!("last DOLE RX {age}ms ago")
            };

            log::info!(
                target: LOG_TARGET,
                "RX Apple BLE scan active, observedAdvertisements={}, doleRxEvents={}, {}",
                observed,
                dole_rx,
                rx_state
            );
        }
    });
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis().min(u128::from(u64::MAX)) as u64)
        .unwrap_or_default()
}

pub(super) fn is_advertising() -> bool {
    APPLE_BLE_ADVERTISING_ACTIVE.load(Ordering::Relaxed) && APPLE_BLE_RADIO_ON.load(Ordering::Relaxed)
}

pub(super) fn stop() {
    APPLE_BLE_LOGGER_EPOCH.fetch_add(1, Ordering::Relaxed);
    APPLE_BLE_TX_EPOCH.fetch_add(1, Ordering::Relaxed);
    if APPLE_BLE_ADVERTISING_ACTIVE.swap(false, Ordering::Relaxed) {
        advertiser_stopped();
    }
    let Ok(mut state) = STATE.lock() else {
        return;
    };
    if let Some(manager) = state.peripheral_manager.as_ref() {
        unsafe { manager.stopAdvertising() };
    }
    if let Some(manager) = state.central_manager.as_ref() {
        unsafe { manager.stopScan() };
    }
    if state.active {
        log::info!(target: LOG_TARGET, "Apple BLE backend stopped");
    }
    *state = AppleBleState::default();
}

fn start_apple_advertising(peripheral: &CBPeripheralManager) {
    if !is_active() {
        return;
    }

    let payload = {
        let Ok(state) = STATE.lock() else {
            return;
        };
        state.current_payload.clone()
    };
    let Some(payload) = payload else {
        return;
    };

    let advertisement = build_apple_advertisement(&payload);
    unsafe { peripheral.startAdvertising(Some(&advertisement)) };
}

fn build_apple_advertisement(_payload: &[u8]) -> Retained<NSDictionary<NSString, AnyObject>> {
    let service_uuid = cb_uuid(APPLE_BLE_SERVICE_UUID_16);
    let service_uuids = NSArray::<CBUUID>::from_slice(&[&service_uuid]);
    let local_name = NSString::from_str("DOLE");

    let keys = unsafe {
        [
            CBAdvertisementDataServiceUUIDsKey,
            CBAdvertisementDataLocalNameKey,
        ]
    };
    let values: [Retained<AnyObject>; 2] = [service_uuids.into(), local_name.into()];
    NSDictionary::<NSString, AnyObject>::from_retained_objects(&keys, &values)
}

fn start_scan(central: &CBCentralManager) {
    if !is_active() {
        return;
    }

    let keys = unsafe { [CBCentralManagerScanOptionAllowDuplicatesKey] };
    let values: [Retained<AnyObject>; 1] = [NSNumber::numberWithBool(true).into()];
    let options = NSDictionary::<NSString, AnyObject>::from_retained_objects(&keys, &values);
    unsafe { central.scanForPeripheralsWithServices_options(None, Some(&options)) };
    log::info!(target: LOG_TARGET, "Apple BLE scan started");
}

fn ingest_advertised_service_data(advertisement_data: &NSDictionary<NSString, AnyObject>) {
    let Some(service_data) =
        advertisement_data.objectForKey(unsafe { CBAdvertisementDataServiceDataKey })
    else {
        return;
    };
    let Ok(service_data) = service_data.downcast::<NSDictionary>() else {
        return;
    };

    let storage_path = {
        let Ok(state) = STATE.lock() else {
            return;
        };
        if !state.active {
            return;
        }
        let Some(storage_path) = state.storage_path.clone() else {
            return;
        };
        storage_path
    };

    let (keys, values) = service_data.to_vecs();
    for (key, value) in keys.into_iter().zip(values) {
        let is_dole_service = key
            .downcast_ref::<CBUUID>()
            .is_some_and(|uuid| uuid_matches(uuid, APPLE_BLE_SERVICE_UUID_16));
        if !is_dole_service {
            continue;
        }
        let Some(payload) = value.downcast_ref::<NSData>() else {
            continue;
        };
        let payload = payload.to_vec();
        if ingest_payload(&storage_path, &payload) {
            APPLE_BLE_DOLE_RX_EVENTS.fetch_add(1, Ordering::Relaxed);
            APPLE_BLE_LAST_DOLE_RX_MS.store(now_millis(), Ordering::Relaxed);
            log_payload("RX Apple BLE advertising", &payload);
        }
    }
}

fn is_active() -> bool {
    STATE.lock().map(|state| state.active).unwrap_or(false)
}

fn cb_uuid(value: &str) -> Retained<CBUUID> {
    let value = NSString::from_str(value);
    unsafe { CBUUID::UUIDWithString(&value) }
}

fn uuid_matches(uuid: &CBUUID, expected: &str) -> bool {
    unsafe { uuid.UUIDString() }.to_string().eq_ignore_ascii_case(expected)
}
