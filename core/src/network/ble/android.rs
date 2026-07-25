use std::sync::{LazyLock, Mutex};
use std::thread;
use std::time::Duration;

use jni::objects::{Global, JByteArray, JObject, JString, JValue};
use jni::signature::RuntimeMethodSignature;
use jni::strings::JNIString;
use jni::sys::{jint, jobject};
use jni::{Env, EnvUnowned, JValueOwned, JavaVM};

use crate::constants::{BLE_PAYLOAD_DWELL_MS, BLE_SERVICE_DATA_OVERHEAD_SIZE, BLE_SERVICE_UUID};

use super::{
    LOG_TARGET, advertiser_started, advertiser_stopped, build_initial_payload, ingest_payload,
    log_payload, next_payload
};

static STATE: LazyLock<Mutex<AndroidBleState>> = LazyLock::new(|| Mutex::new(AndroidBleState::default()));

type GlobalObject = Global<JObject<'static>>;

#[derive(Default)]
struct AndroidBleState {
    storage_path: Option<String>,
    max_payload_bytes: usize,
    own_payload: Option<Vec<u8>>,
    vm: Option<JavaVM>,
    context: Option<GlobalObject>,
    advertiser: Option<GlobalObject>,
    advertising_set: Option<GlobalObject>,
    advertising_callback: Option<GlobalObject>,
    scanner: Option<GlobalObject>,
    scan_callback: Option<GlobalObject>,
    service_uuid: Option<GlobalObject>,
    active: bool,
    payload_worker_started: bool,
    worker_generation: u64
}

pub(super) fn start(storage_path: &str) -> bool {
    let Some((vm, context)) = android_vm_and_context() else {
        log::warn!(target: LOG_TARGET, "Android BLE cannot start because ndk-context is missing");
        return false;
    };

    match vm.attach_current_thread(|env| start_with_env(env, &vm, context, storage_path)) {
        Ok(started) => started,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE cannot attach JNI thread: {e:?}");
            false
        }
    }
}

fn start_with_env(env: &mut Env<'_>, vm: &JavaVM, context: GlobalObject, storage_path: &str) -> jni::errors::Result<bool> {
    let service_uuid = match build_parcel_uuid(env) {
        Ok(uuid) => uuid,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE service UUID setup failed: {e:?}");
            return Ok(false);
        }
    };

    let adapter = match bluetooth_adapter(env, context.as_obj()) {
        Ok(adapter) => adapter,
        Err(e) => {
            log::warn!(target: LOG_TARGET, "Android BLE adapter unavailable: {e:?}");
            return Ok(false);
        }
    };

    if !call_bool(env, &adapter, "isEnabled") {
        log::warn!(target: LOG_TARGET, "Android BLE disabled because Bluetooth is off");
        return Ok(false);
    }
    if !call_bool(env, &adapter, "isLeExtendedAdvertisingSupported") {
        log::warn!(target: LOG_TARGET, "Android BLE disabled because extended advertising is not supported");
        return Ok(false);
    }

    let max_adv_len = match call_int(env, &adapter, "getLeMaximumAdvertisingDataLength") {
        Ok(value) if value > 0 => value as usize,
        Ok(value) => {
            log::warn!(
                target: LOG_TARGET,
                "Android BLE disabled because maximum advertising data length is invalid: {}",
                value
            );
            return Ok(false);
        }
        Err(e) => {
            log::warn!(
                target: LOG_TARGET,
                "Android BLE disabled because maximum advertising data length could not be read: {e:?}"
            );
            return Ok(false);
        }
    };
    let max_payload_bytes = max_adv_len.saturating_sub(BLE_SERVICE_DATA_OVERHEAD_SIZE as usize);
    let initial_payload = build_initial_payload(storage_path);
    if initial_payload.len() > max_payload_bytes {
        log::warn!(
            target: LOG_TARGET,
            "Android BLE initial payload too large payloadBytes={} maxServicePayloadBytes={}",
            initial_payload.len(),
            max_payload_bytes
        );
        return Ok(false);
    }

    let advertiser = match call_object(
        env,
        &adapter,
        "getBluetoothLeAdvertiser",
        "()Landroid/bluetooth/le/BluetoothLeAdvertiser;",
        &[]
    ) {
        Ok(obj) if !obj.is_null() => match env.new_global_ref(obj) {
            Ok(global) => global,
            Err(e) => {
                log::error!(target: LOG_TARGET, "Android BLE advertiser global ref failed: {e:?}");
                return Ok(false);
            }
        },
        _ => {
            log::warn!(target: LOG_TARGET, "Android BLE advertiser unavailable");
            return Ok(false);
        }
    };

    let scanner = match call_object(
        env,
        &adapter,
        "getBluetoothLeScanner",
        "()Landroid/bluetooth/le/BluetoothLeScanner;",
        &[]
    ) {
        Ok(obj) if !obj.is_null() => match env.new_global_ref(obj) {
            Ok(global) => global,
            Err(e) => {
                log::error!(target: LOG_TARGET, "Android BLE scanner global ref failed: {e:?}");
                return Ok(false);
            }
        },
        _ => {
            log::warn!(target: LOG_TARGET, "Android BLE scanner unavailable");
            return Ok(false);
        }
    };

    let advertising_callback =
        match new_global_object(env, "dole/ble/AdvertisingSetCallback", "()V", &[]) {
            Ok(obj) => obj,
            Err(e) => {
                log::error!(target: LOG_TARGET, "Android BLE advertising callback missing: {e:?}");
                return Ok(false);
            }
        };
    let scan_callback = match new_global_object(env, "dole/ble/ScanCallback", "()V", &[]) {
        Ok(obj) => obj,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE scan callback missing: {e:?}");
            return Ok(false);
        }
    };

    if let Ok(mut state) = STATE.lock() {
        state.storage_path = Some(storage_path.to_string());
        state.max_payload_bytes = max_payload_bytes;
        state.own_payload = Some(initial_payload.clone());
        state.vm = Some(vm.clone());
        state.context = Some(context);
        state.advertiser = Some(advertiser);
        state.scanner = Some(scanner);
        state.advertising_callback = Some(advertising_callback);
        state.scan_callback = Some(scan_callback);
        state.service_uuid = Some(service_uuid);
        state.active = true;
    }
    advertiser_started();

    if !start_advertising(env, &initial_payload) {
        stop();
        return Ok(false);
    }
    if !start_scanning(env) {
        stop();
        return Ok(false);
    }

    log::info!(
        target: LOG_TARGET,
        "Android BLE JNI started maxAdvertisementDataLength={} maxServicePayloadBytes={} payloadBytes={}",
        max_adv_len,
        max_payload_bytes,
        initial_payload.len()
    );
    Ok(true)
}

pub(super) fn is_advertising() -> bool {
    STATE.lock().map(|state| state.active).unwrap_or(false)
}

pub(super) fn stop() {
    advertiser_stopped();
    let (vm, advertiser, advertising_callback, scanner, scan_callback) = {
        let Ok(mut state) = STATE.lock() else {
            return;
        };
        state.active = false;
        (
            state.vm.take(),
            state.advertiser.take(),
            state.advertising_callback.take(),
            state.scanner.take(),
            state.scan_callback.take()
        )
    };

    let Some(vm) = vm else {
        return;
    };
    let _ = vm.attach_current_thread(|env| {
        if let (Some(scanner), Some(callback)) = (scanner, scan_callback) {
            let _ = call_method_value(
                env,
                scanner.as_obj(),
                "stopScan",
                "(Landroid/bluetooth/le/ScanCallback;)V",
                &[JValue::Object(callback.as_obj())]
            );
        }
        if let (Some(advertiser), Some(callback)) = (advertiser, advertising_callback) {
            let _ = call_method_value(
                env,
                advertiser.as_obj(),
                "stopAdvertisingSet",
                "(Landroid/bluetooth/le/AdvertisingSetCallback;)V",
                &[JValue::Object(callback.as_obj())]
            );
        }
        Ok::<(), jni::errors::Error>(())
    });

    if let Ok(mut state) = STATE.lock() {
        state.context = None;
        state.advertising_set = None;
        state.own_payload = None;
        state.service_uuid = None;
        state.max_payload_bytes = 0;
        state.storage_path = None;
        state.payload_worker_started = false;
    }
    log::info!(target: LOG_TARGET, "Android BLE JNI stopped");
}

fn start_payload_worker_once() {
    let generation = {
        let Ok(mut state) = STATE.lock() else {
            return;
        };
        if !state.active || state.payload_worker_started {
            return;
        }
        state.payload_worker_started = true;
        state.worker_generation += 1;
        state.worker_generation
    };

    thread::spawn(move || {
        run_payload_worker(generation);
        if let Ok(mut state) = STATE.lock()
            && state.worker_generation == generation
        {
            state.payload_worker_started = false;
        }
    });
}

fn run_payload_worker(generation: u64) {
    let Some(vm) = state_vm_clone() else {
        return;
    };
    let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        loop {
            thread::sleep(Duration::from_millis(BLE_PAYLOAD_DWELL_MS as u64));

            let (active, storage_path, max_payload_bytes) = {
                let Ok(state) = STATE.lock() else {
                    return Ok(());
                };
                if state.worker_generation != generation {
                    return Ok(());
                }
                (
                    state.active,
                    state.storage_path.clone(),
                    state.max_payload_bytes
                )
            };
            if !active || max_payload_bytes == 0 {
                return Ok(());
            }
            let Some(storage_path) = storage_path else {
                continue;
            };
            let Some(payload) = next_payload(&storage_path, max_payload_bytes) else {
                return Ok(());
            };

            env.with_local_frame(16, |env| -> jni::errors::Result<()> {
                update_advertising_payload(env, &payload);
                Ok(())
            })?;
        }
    });
}

fn state_vm_clone() -> Option<JavaVM> {
    let state = STATE.lock().ok()?;
    state.vm.clone()
}

fn start_advertising(env: &mut Env<'_>, payload: &[u8]) -> bool {
    let (advertiser, callback, service_uuid) = {
        let Ok(state) = STATE.lock() else {
            return false;
        };
        let Some(advertiser) = state.advertiser.as_ref() else {
            return false;
        };
        let Some(callback) = state.advertising_callback.as_ref() else {
            return false;
        };
        let Some(service_uuid) = state.service_uuid.as_ref() else {
            return false;
        };
        let Ok(advertiser) = env.new_local_ref(advertiser.as_obj()) else {
            return false;
        };
        let Ok(callback) = env.new_local_ref(callback.as_obj()) else {
            return false;
        };
        let Ok(service_uuid) = env.new_local_ref(service_uuid.as_obj()) else {
            return false;
        };
        (advertiser, callback, service_uuid)
    };

    let params = match build_advertising_set_parameters(env) {
        Ok(params) => params,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE parameters build failed: {e:?}");
            return false;
        }
    };
    let data = match build_advertise_data(env, &service_uuid, payload) {
        Ok(data) => data,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE advertise data build failed: {e:?}");
            return false;
        }
    };

    let null = JObject::null();
    let result = call_method_value(
        env,
        &advertiser,
        "startAdvertisingSet",
        "(Landroid/bluetooth/le/AdvertisingSetParameters;Landroid/bluetooth/le/AdvertiseData;Landroid/bluetooth/le/AdvertiseData;Landroid/bluetooth/le/PeriodicAdvertisingParameters;Landroid/bluetooth/le/AdvertiseData;Landroid/bluetooth/le/AdvertisingSetCallback;)V",
        &[
            JValue::Object(&params),
            JValue::Object(&data),
            JValue::Object(&null),
            JValue::Object(&null),
            JValue::Object(&null),
            JValue::Object(&callback)
        ]
    );
    if let Err(e) = result {
        log::error!(target: LOG_TARGET, "Android BLE startAdvertisingSet failed: {e:?}");
        return false;
    }

    log::debug!(target: LOG_TARGET, "Android BLE advertising requested payloadBytes={}", payload.len());
    true
}

fn update_advertising_payload(env: &mut Env<'_>, payload: &[u8]) {
    let (advertising_set, service_uuid) = {
        let Ok(mut state) = STATE.lock() else {
            return;
        };
        if state.own_payload.as_deref() == Some(payload) {
            return;
        }
        state.own_payload = Some(payload.to_vec());
        let (Some(advertising_set), Some(service_uuid)) =
            (state.advertising_set.as_ref(), state.service_uuid.as_ref())
        else {
            return;
        };
        match (
            env.new_local_ref(advertising_set.as_obj()),
            env.new_local_ref(service_uuid.as_obj())
        ) {
            (Ok(advertising_set), Ok(service_uuid)) => (advertising_set, service_uuid),
            (Err(e), _) | (_, Err(e)) => {
                log::warn!(target: LOG_TARGET, "Android BLE advertising set local ref failed: {e:?}");
                return;
            }
        }
    };

    let Ok(data) = build_advertise_data(env, &service_uuid, payload) else {
        return;
    };
    let result = call_method_value(
        env,
        &advertising_set,
        "setAdvertisingData",
        "(Landroid/bluetooth/le/AdvertiseData;)V",
        &[JValue::Object(&data)]
    );
    if let Err(e) = result {
        log::warn!(target: LOG_TARGET, "Android BLE setAdvertisingData failed: {e:?}");
    }
}

fn start_scanning(env: &mut Env<'_>) -> bool {
    let (scanner, callback, service_uuid) = {
        let Ok(state) = STATE.lock() else {
            return false;
        };
        let Some(scanner) = state.scanner.as_ref() else {
            return false;
        };
        let Some(callback) = state.scan_callback.as_ref() else {
            return false;
        };
        let Some(service_uuid) = state.service_uuid.as_ref() else {
            return false;
        };
        let Ok(scanner) = env.new_local_ref(scanner.as_obj()) else {
            return false;
        };
        let Ok(callback) = env.new_local_ref(callback.as_obj()) else {
            return false;
        };
        let Ok(service_uuid) = env.new_local_ref(service_uuid.as_obj()) else {
            return false;
        };
        (scanner, callback, service_uuid)
    };

    let settings = match build_scan_settings(env) {
        Ok(settings) => settings,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE scan settings build failed: {e:?}");
            return false;
        }
    };
    let filter = match build_scan_filter(env, &service_uuid) {
        Ok(filter) => filter,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE scan filter build failed: {e:?}");
            return false;
        }
    };
    let filters = match call_static_method_value(
        env,
        "java/util/Collections",
        "singletonList",
        "(Ljava/lang/Object;)Ljava/util/List;",
        &[JValue::Object(&filter)]
    ) {
        Ok(value) => match value.l() {
            Ok(obj) => obj,
            Err(e) => {
                log::error!(target: LOG_TARGET, "Android BLE singletonList result invalid: {e:?}");
                return false;
            }
        },
        Err(e) => {
            log::error!(target: LOG_TARGET, "Android BLE singletonList failed: {e:?}");
            return false;
        }
    };

    let result = call_method_value(
        env,
        &scanner,
        "startScan",
        "(Ljava/util/List;Landroid/bluetooth/le/ScanSettings;Landroid/bluetooth/le/ScanCallback;)V",
        &[
            JValue::Object(&filters),
            JValue::Object(&settings),
            JValue::Object(&callback)
        ]
    );
    if let Err(e) = result {
        log::error!(target: LOG_TARGET, "Android BLE startScan failed: {e:?}");
        return false;
    }
    log::info!(target: LOG_TARGET, "Android BLE scan started");
    true
}

fn build_advertising_set_parameters<'local>(env: &mut Env<'local>) -> jni::errors::Result<JObject<'local>> {
    let builder = new_object(
        env,
        "android/bluetooth/le/AdvertisingSetParameters$Builder",
        "()V",
        &[]
    )?;
    let ret = "android/bluetooth/le/AdvertisingSetParameters$Builder";
    chain_bool(env, &builder, "setLegacyMode", ret, false)?;
    chain_bool(env, &builder, "setConnectable", ret, false)?;
    chain_bool(env, &builder, "setScannable", ret, false)?;
    chain_bool(env, &builder, "setAnonymous", ret, false)?;
    chain_bool(env, &builder, "setIncludeTxPower", ret, false)?;

    call_object(
        env,
        &builder,
        "build",
        "()Landroid/bluetooth/le/AdvertisingSetParameters;",
        &[]
    )
}

fn build_advertise_data<'local>(
    env: &mut Env<'local>,
    service_uuid: &JObject<'_>,
    payload: &[u8]
) -> jni::errors::Result<JObject<'local>> {
    let builder = new_object(
        env,
        "android/bluetooth/le/AdvertiseData$Builder",
        "()V",
        &[]
    )?;
    let ret = "android/bluetooth/le/AdvertiseData$Builder";
    chain_bool(env, &builder, "setIncludeDeviceName", ret, false)?;
    chain_bool(env, &builder, "setIncludeTxPowerLevel", ret, false)?;
    let bytes = env.byte_array_from_slice(payload)?;
    let bytes = JObject::from(bytes);
    call_method_value(
        env,
        &builder,
        "addServiceData",
        "(Landroid/os/ParcelUuid;[B)Landroid/bluetooth/le/AdvertiseData$Builder;",
        &[JValue::Object(service_uuid), JValue::Object(&bytes)]
    )?;
    call_object(
        env,
        &builder,
        "build",
        "()Landroid/bluetooth/le/AdvertiseData;",
        &[]
    )
}

fn build_scan_settings<'local>(env: &mut Env<'local>) -> jni::errors::Result<JObject<'local>> {
    let builder = new_object(env, "android/bluetooth/le/ScanSettings$Builder", "()V", &[])?;
    let ret = "android/bluetooth/le/ScanSettings$Builder";
    chain_bool(env, &builder, "setLegacy", ret, false)?;
    call_object(
        env,
        &builder,
        "build",
        "()Landroid/bluetooth/le/ScanSettings;",
        &[]
    )
}

fn build_scan_filter<'local>(env: &mut Env<'local>, service_uuid: &JObject<'_>) -> jni::errors::Result<JObject<'local>> {
    let builder = new_object(env, "android/bluetooth/le/ScanFilter$Builder", "()V", &[])?;
    let null = JObject::null();
    call_method_value(
        env,
        &builder,
        "setServiceData",
        "(Landroid/os/ParcelUuid;[B)Landroid/bluetooth/le/ScanFilter$Builder;",
        &[JValue::Object(service_uuid), JValue::Object(&null)]
    )?;
    call_object(
        env,
        &builder,
        "build",
        "()Landroid/bluetooth/le/ScanFilter;",
        &[]
    )
}

fn build_parcel_uuid(env: &mut Env<'_>) -> jni::errors::Result<GlobalObject> {
    let uuid_string: JString = env.new_string(BLE_SERVICE_UUID)?;
    let uuid_string = JObject::from(uuid_string);
    let uuid = call_static_method_value(
        env,
        "java/util/UUID",
        "fromString",
        "(Ljava/lang/String;)Ljava/util/UUID;",
        &[JValue::Object(&uuid_string)]
    )?
    .l()?;
    let parcel = new_object(
        env,
        "android/os/ParcelUuid",
        "(Ljava/util/UUID;)V",
        &[JValue::Object(&uuid)]
    )?;
    env.new_global_ref(parcel)
}

fn bluetooth_adapter<'local>(env: &mut Env<'local>, context: &JObject<'_>) -> jni::errors::Result<JObject<'local>> {
    let bluetooth_manager_class = env.find_class(&JNIString::new("android/bluetooth/BluetoothManager"))?;
    let bluetooth_manager_class = JObject::from(bluetooth_manager_class);
    let manager = call_method_value(
        env,
        context,
        "getSystemService",
        "(Ljava/lang/Class;)Ljava/lang/Object;",
        &[JValue::Object(&bluetooth_manager_class)]
    )?
    .l()?;
    call_object(
        env,
        &manager,
        "getAdapter",
        "()Landroid/bluetooth/BluetoothAdapter;",
        &[]
    )
}

fn android_vm_and_context() -> Option<(JavaVM, GlobalObject)> {
    let ctx = ndk_context::android_context();
    let vm_raw = ctx.vm() as *mut jni::sys::JavaVM;
    let context_raw = ctx.context() as jobject;
    if vm_raw.is_null() || context_raw.is_null() {
        return None;
    }

    let vm = unsafe { JavaVM::from_raw(vm_raw) };
    let context = vm
        .attach_current_thread(|env| {
            let context = unsafe { JObject::from_raw(env, context_raw) };
            env.new_global_ref(&context)
        })
        .ok()?;
    Some((vm, context))
}

fn new_global_object(
    env: &mut Env<'_>,
    class: &str,
    sig: &str,
    args: &[JValue<'_>]
) -> jni::errors::Result<GlobalObject> {
    let obj = new_object(env, class, sig, args)?;
    env.new_global_ref(obj)
}

fn new_object<'local>(
    env: &mut Env<'local>,
    class: &str,
    sig: &str,
    args: &[JValue<'_>]
) -> jni::errors::Result<JObject<'local>> {
    let class = JNIString::new(class);
    let sig = RuntimeMethodSignature::from_str(sig)?;
    let sig = sig.method_signature();
    env.new_object(&class, &sig, args)
}

fn call_object<'local>(
    env: &mut Env<'local>,
    obj: &JObject<'_>,
    name: &str,
    sig: &str,
    args: &[JValue<'_>]
) -> jni::errors::Result<JObject<'local>> {
    call_method_value(env, obj, name, sig, args)?.l()
}

fn call_method_value<'local>(
    env: &mut Env<'local>,
    obj: &JObject<'_>,
    name: &str,
    sig: &str,
    args: &[JValue<'_>]
) -> jni::errors::Result<JValueOwned<'local>> {
    let name = JNIString::new(name);
    let sig = RuntimeMethodSignature::from_str(sig)?;
    let sig = sig.method_signature();
    env.call_method(obj, &name, &sig, args)
}

fn call_static_method_value<'local>(
    env: &mut Env<'local>,
    class: &str,
    name: &str,
    sig: &str,
    args: &[JValue<'_>]
) -> jni::errors::Result<JValueOwned<'local>> {
    let class = JNIString::new(class);
    let name = JNIString::new(name);
    let sig = RuntimeMethodSignature::from_str(sig)?;
    let sig = sig.method_signature();
    env.call_static_method(&class, &name, &sig, args)
}

fn call_bool(env: &mut Env<'_>, obj: &JObject<'_>, name: &str) -> bool {
    call_method_value(env, obj, name, "()Z", &[]).and_then(|v| v.z()).unwrap_or(false)
}

fn call_int(env: &mut Env<'_>, obj: &JObject<'_>, name: &str) -> jni::errors::Result<jint> {
    call_method_value(env, obj, name, "()I", &[])?.i()
}

fn chain_bool(
    env: &mut Env<'_>,
    builder: &JObject<'_>,
    name: &str,
    return_type: &str,
    value: bool
) -> jni::errors::Result<()> {
    let sig = format!("(Z)L{return_type};");
    call_method_value(env, builder, name, sig.as_str(), &[JValue::Bool(value)])?;
    Ok(())
}

#[unsafe(export_name = "Java_dole_ble_BleNative_onAdvertisingSetStarted")]
pub extern "system" fn java_dole_ble_rust_ble_native_on_advertising_set_started<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    advertising_set: JObject<'local>,
    tx_power: jint,
    status: jint
) {
    let _ = unowned_env.with_env(|env| {
        if status == 0 && !advertising_set.is_null() {
            if let Ok(global) = env.new_global_ref(&advertising_set) {
                if let Ok(mut state) = STATE.lock() {
                    state.advertising_set = Some(global);
                }
            }
            start_payload_worker_once();
            log::info!(target: LOG_TARGET, "Android BLE advertising active txPower={}", tx_power);
        } else {
            log::warn!(target: LOG_TARGET, "Android BLE advertising failed status={}", status);
        }
        Ok::<(), jni::errors::Error>(())
    });
}

#[unsafe(export_name = "Java_dole_ble_BleNative_onAdvertisingDataSet")]
pub extern "system" fn java_dole_ble_rust_ble_native_on_advertising_data_set<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>,
    status: jint
) {
    if status != 0 {
        log::warn!(target: LOG_TARGET, "Android BLE advertising data update failed status={}", status);
    }
}

#[unsafe(export_name = "Java_dole_ble_BleNative_onAdvertisingSetStopped")]
pub extern "system" fn java_dole_ble_rust_ble_native_on_advertising_set_stopped<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>
) {
    if let Ok(mut state) = STATE.lock() {
        state.advertising_set = None;
    }
    log::info!(target: LOG_TARGET, "Android BLE advertising stopped");
}

#[unsafe(export_name = "Java_dole_ble_BleNative_onScanFailed")]
pub extern "system" fn java_dole_ble_rust_ble_native_on_scan_failed<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>,
    error_code: jint
) {
    log::warn!(target: LOG_TARGET, "Android BLE scan failed errorCode={}", error_code);
}

#[unsafe(export_name = "Java_dole_ble_BleNative_onScanResult")]
pub extern "system" fn java_dole_ble_rust_ble_native_on_scan_result<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    result: JObject<'local>
) {
    let _ = unowned_env.with_env(|env| {
        let payload = match payload_from_scan_result(env, &result) {
            Some(payload) => payload,
            None => return Ok::<(), jni::errors::Error>(())
        };

        let storage_path = {
            let Ok(state) = STATE.lock() else {
                return Ok(());
            };
            state.storage_path.clone()
        };
        if super::session_id_from_payload_or_service_data(&payload)
            == Some(crate::network::session::get_session_id())
        {
            return Ok(());
        }
        let Some(storage_path) = storage_path else {
            return Ok(());
        };

        let accepted = ingest_payload(&storage_path, &payload);
        if accepted {
            log_payload("RX Android BLE sync", &payload);
        }
        Ok(())
    });
}

fn payload_from_scan_result(env: &mut Env<'_>, result: &JObject<'_>) -> Option<Vec<u8>> {
    let service_uuid = {
        let state = STATE.lock().ok()?;
        env.new_local_ref(state.service_uuid.as_ref()?.as_obj())
            .ok()?
    };
    let scan_record = call_method_value(
        env,
        result,
        "getScanRecord",
        "()Landroid/bluetooth/le/ScanRecord;",
        &[]
    )
    .ok()?
    .l()
    .ok()?;
    if scan_record.is_null() {
        return None;
    }
    let data = call_method_value(
        env,
        &scan_record,
        "getServiceData",
        "(Landroid/os/ParcelUuid;)[B",
        &[JValue::Object(&service_uuid)]
    )
    .ok()?
    .l()
    .ok()?;
    if data.is_null() {
        return None;
    }
    let array = JByteArray::cast_local(env, data).ok()?;
    env.convert_byte_array(&array).ok()
}
