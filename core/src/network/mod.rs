mod ble;
mod mdns;
mod peerbook;
mod queue;
mod session;

use std::collections::VecDeque;
use std::sync::{
    Condvar, LazyLock, Mutex,
    atomic::{AtomicBool, AtomicU8, AtomicU16, Ordering},
};
use std::thread;
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};
use tokio::sync::mpsc;

use crate::constants::{
    IROH_MESSAGE_OVERHEAD_BYTES, IROH_SHUTDOWN_TIMEOUT_MS, NETWORK_FRONTIER_BROADCAST_INTERVAL_MS,
    PEER_LIVENESS_TIMEOUT_MS, PEERBOOK_MAINTENANCE_INTERVAL_MS, RX_DEDUP_LIMIT, RX_DEDUP_WINDOW_MS,
};
use crate::logging;
use crate::sync::MessageKind;

pub(crate) use queue::OutboundMessage;

use peerbook::{PeerBook, clear_peer_snapshot};
use queue::{TransportLimit, TransportQueue, outbound_message_kind};
use session::{SESSION_ID_BYTES, SessionId};

const LOG_TARGET: &str = "dole::network";
const FRONTIER_BROADCAST_INTERVAL: Duration =
    Duration::from_millis(NETWORK_FRONTIER_BROADCAST_INTERVAL_MS as u64);
const PEERBOOK_MAINTENANCE_INTERVAL: Duration =
    Duration::from_millis(PEERBOOK_MAINTENANCE_INTERVAL_MS as u64);
const PEER_LIVENESS_TIMEOUT: Duration = Duration::from_millis(PEER_LIVENESS_TIMEOUT_MS as u64);
const RX_DEDUP_WINDOW: Duration = Duration::from_millis(RX_DEDUP_WINDOW_MS as u64);
const RX_DEDUP_CACHE_LIMIT: usize = RX_DEDUP_LIMIT as usize;
const BLE_TRANSPORT_TICK_BYTES: usize = 1;
const BLE_FRAME_ROTATION: usize = 8;
const IROH_SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(IROH_SHUTDOWN_TIMEOUT_MS as u64);

static BLE_INBOUND_TX: Mutex<Option<mpsc::UnboundedSender<InboundPayload>>> = Mutex::new(None);
static IROH_RESTART_TX: Mutex<Option<mpsc::UnboundedSender<()>>> = Mutex::new(None);
static IROH_ACTIVE: AtomicBool = AtomicBool::new(false);
static INTERNET_ACTIVE: AtomicBool = AtomicBool::new(false);
static INTERNET_RELAY_ONLINE: AtomicBool = AtomicBool::new(false);
static INTERNET_HUB_ONLINE: AtomicBool = AtomicBool::new(false);
static BLE_ADVERTISING_TICK: AtomicU8 = AtomicU8::new(0);
static LOCAL_BLE_PAYLOAD_LIMIT: AtomicU16 = AtomicU16::new(0);
static PEER_BLE_PAYLOAD_LIMIT: AtomicU16 = AtomicU16::new(0);
static BLE_ADVERTISER_STATE: LazyLock<(Mutex<BleAdvertiserState>, Condvar)> = LazyLock::new(|| {
    (
        Mutex::new(BleAdvertiserState {
            active: false,
            queue: TransportQueue::new(),
            recent: VecDeque::new(),
            cursor: 0,
        }),
        Condvar::new(),
    )
});

struct BleAdvertiserState {
    active: bool,
    queue: TransportQueue,
    recent: VecDeque<Vec<u8>>,
    cursor: usize,
}

impl BleAdvertiserState {
    fn clear(&mut self) {
        self.queue.clear();
        self.recent.clear();
        self.cursor = 0;
    }

    fn remember(&mut self, frame: &[u8]) {
        if self.recent.iter().any(|known| known == frame) {
            return;
        }
        while self.recent.len() >= BLE_FRAME_ROTATION {
            self.recent.pop_front();
        }
        self.recent.push_back(frame.to_vec());
    }
}

#[derive(uniffi::Record)]
pub struct TransportStatus {
    pub ble: bool,
    pub iroh: bool,
    pub internet: bool,
}

#[uniffi::export]
pub fn transport_status() -> TransportStatus {
    let ble = BLE_ADVERTISER_STATE
        .0
        .lock()
        .map(|state| state.active)
        .unwrap_or(false)
        && ble::advertising_active();
    TransportStatus {
        ble,
        iroh: IROH_ACTIVE.load(Ordering::Relaxed),
        internet: INTERNET_ACTIVE.load(Ordering::Relaxed),
    }
}

#[uniffi::export]
pub fn local_session_id() -> String {
    session::get_session_id().short()
}

pub(super) fn set_internet_relay_online(online: bool) {
    INTERNET_RELAY_ONLINE.store(online, Ordering::Relaxed);
    refresh_internet_active();
}

pub(super) fn set_internet_hub_online(online: bool) {
    INTERNET_HUB_ONLINE.store(online, Ordering::Relaxed);
    refresh_internet_active();
}

pub(super) fn clear_internet_active() {
    INTERNET_RELAY_ONLINE.store(false, Ordering::Relaxed);
    INTERNET_HUB_ONLINE.store(false, Ordering::Relaxed);
    INTERNET_ACTIVE.store(false, Ordering::Relaxed);
}

fn refresh_internet_active() {
    let reachable = INTERNET_RELAY_ONLINE.load(Ordering::Relaxed)
        || INTERNET_HUB_ONLINE.load(Ordering::Relaxed);
    let active = mdns::is_internet_enabled() && reachable;
    INTERNET_ACTIVE.store(active, Ordering::Relaxed);
}

pub(super) fn set_iroh_active(active: bool) {
    IROH_ACTIVE.store(active, Ordering::Relaxed);
}

#[derive(Clone, Copy, Eq, PartialEq)]
pub(crate) enum TransportKind {
    Ble,
    Iroh,
}

impl TransportKind {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Ble => "ble",
            Self::Iroh => "iroh",
        }
    }
}

struct InboundPayload {
    transport: TransportKind,
    source: InboundSource,
    payload: Vec<u8>,
}

struct IrohTransport {
    command_tx: mpsc::UnboundedSender<mdns::Command>,
    shutdown_tx: mpsc::UnboundedSender<()>,
    task: tokio::task::JoinHandle<()>,
}

async fn shutdown_iroh_transport(transport: IrohTransport) {
    let _ = transport.shutdown_tx.send(());
    if tokio::time::timeout(IROH_SHUTDOWN_TIMEOUT, transport.task)
        .await
        .is_err()
    {
        log::warn!(target: LOG_TARGET, "iroh endpoint shutdown timed out");
    }
}

fn wants_iroh_endpoint() -> bool {
    mdns::is_iroh_enabled() || mdns::is_internet_enabled()
}

fn spawn_iroh_transport(event_tx: &mpsc::UnboundedSender<mdns::Event>) -> IrohTransport {
    let (command_tx, command_rx) = mpsc::unbounded_channel();
    let (shutdown_tx, shutdown_rx) = mpsc::unbounded_channel();
    let task = tokio::spawn(mdns::run(command_rx, event_tx.clone(), shutdown_rx));
    IrohTransport {
        command_tx,
        shutdown_tx,
        task,
    }
}

fn request_iroh_restart() {
    if let Ok(guard) = IROH_RESTART_TX.lock()
        && let Some(tx) = guard.as_ref()
    {
        let _ = tx.send(());
    }
}

pub(crate) fn set_local_ble_payload_limit(max_payload_bytes: usize) {
    let limit = u16::try_from(max_payload_bytes).unwrap_or(u16::MAX);
    LOCAL_BLE_PAYLOAD_LIMIT.store(limit, Ordering::Relaxed);
}

pub(super) fn local_ble_payload_limit() -> u16 {
    LOCAL_BLE_PAYLOAD_LIMIT.load(Ordering::Relaxed)
}

pub(super) fn set_peer_ble_payload_limit(limit: u16) {
    PEER_BLE_PAYLOAD_LIMIT.store(limit, Ordering::Relaxed);
}

fn agreed_ble_payload_limit(hardware_max_bytes: usize) -> usize {
    let peer_limit = PEER_BLE_PAYLOAD_LIMIT.load(Ordering::Relaxed);
    if peer_limit == 0 {
        return hardware_max_bytes;
    }
    hardware_max_bytes.min(peer_limit as usize)
}

pub(crate) fn reset_sync_state() {
    let (lock, cvar) = &*BLE_ADVERTISER_STATE;
    if let Ok(mut state) = lock.lock() {
        state.clear();
        cvar.notify_all();
    }
    request_iroh_restart();
}

#[uniffi::export]
pub fn set_iroh_enabled(enabled: bool) {
    mdns::set_iroh_discovery_enabled(enabled);
    request_iroh_restart();
}

#[uniffi::export]
pub fn set_internet_enabled(enabled: bool) {
    mdns::set_internet_enabled_flag(enabled);
    refresh_internet_active();
    request_iroh_restart();
}

#[derive(Clone, Copy)]
pub(crate) enum InboundSource {
    Ble(SessionId),
    Iroh(iroh::EndpointId),
}

impl InboundSource {
    fn label(self) -> String {
        match self {
            Self::Ble(session_id) => session_id.short(),
            Self::Iroh(endpoint_id) => endpoint_id.fmt_short().to_string(),
        }
    }
}

pub(crate) fn start_sync_engine(
    outgoing_rx: mpsc::UnboundedReceiver<OutboundMessage>,
    incoming_tx: mpsc::UnboundedSender<Vec<u8>>,
    frontier_request_tx: mpsc::UnboundedSender<()>,
    shutdown_rx: mpsc::UnboundedReceiver<()>,
) {
    let (ble_inbound_tx, ble_inbound_rx) = mpsc::unbounded_channel();

    if let Ok(mut guard) = BLE_INBOUND_TX.lock() {
        *guard = Some(ble_inbound_tx);
    }

    thread::spawn(move || {
        logging::init_logging();

        let rt = match tokio::runtime::Runtime::new() {
            Ok(rt) => rt,
            Err(e) => {
                log::error!(target: LOG_TARGET, "sync engine could not start Tokio runtime: {e}");
                clear_network_channels();
                return;
            }
        };

        rt.block_on(run_network(
            outgoing_rx,
            incoming_tx,
            frontier_request_tx,
            shutdown_rx,
            ble_inbound_rx,
        ));
    });
}

async fn run_network(
    mut outgoing_rx: mpsc::UnboundedReceiver<OutboundMessage>,
    incoming_tx: mpsc::UnboundedSender<Vec<u8>>,
    frontier_request_tx: mpsc::UnboundedSender<()>,
    mut shutdown_rx: mpsc::UnboundedReceiver<()>,
    mut ble_inbound_rx: mpsc::UnboundedReceiver<InboundPayload>,
) {
    let local_session_id = session::get_session_id();
    let (event_tx, mut event_rx) = mpsc::unbounded_channel();

    let (iroh_restart_tx, mut iroh_restart_rx) = mpsc::unbounded_channel::<()>();
    if let Ok(mut guard) = IROH_RESTART_TX.lock() {
        *guard = Some(iroh_restart_tx);
    }

    let mut iroh: Option<IrohTransport> = if wants_iroh_endpoint() {
        Some(spawn_iroh_transport(&event_tx))
    } else {
        None
    };

    let mut peers = PeerBook::default();
    let mut rx_dedup = RxDedup::default();
    let mut iroh_queue = TransportQueue::new();
    let mut max_iroh_message_bytes = None;
    let mut frontier_interval = tokio::time::interval(FRONTIER_BROADCAST_INTERVAL);
    frontier_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut peerbook_interval = tokio::time::interval(PEERBOOK_MAINTENANCE_INTERVAL);
    peerbook_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    request_frontier(&frontier_request_tx, "startup");

    loop {
        tokio::select! {
            _ = shutdown_rx.recv() => {
                if let Some(transport) = iroh.take() {
                    shutdown_iroh_transport(transport).await;
                }
                set_iroh_active(false);
                clear_internet_active();
                clear_network_channels();
                break;
            }

            Some(()) = iroh_restart_rx.recv() => {
                while iroh_restart_rx.try_recv().is_ok() {}
                iroh_queue.clear();

                if let Some(transport) = iroh.take() {
                    shutdown_iroh_transport(transport).await;
                    max_iroh_message_bytes = None;
                    set_iroh_active(false);
                    clear_internet_active();
                    peers.drop_iroh();
                    log::info!(target: LOG_TARGET, "iroh endpoint stopped");
                }

                if wants_iroh_endpoint() {
                    iroh = Some(spawn_iroh_transport(&event_tx));
                    log::info!(target: LOG_TARGET, "iroh endpoint restarting");
                }
            }

            _ = frontier_interval.tick() => {
                request_frontier(&frontier_request_tx, "periodic");
            }

            _ = peerbook_interval.tick() => {
                peers.expire(Instant::now(), PEER_LIVENESS_TIMEOUT);
            }

            Some(inbound) = ble_inbound_rx.recv() => {
                process_inbound(inbound, &mut peers, &mut rx_dedup, &incoming_tx);
            }

            Some(message) = outgoing_rx.recv() => {
                enqueue_outbound_message(
                    message,
                    &mut iroh_queue,
                    iroh.as_ref().map(|transport| &transport.command_tx),
                    max_iroh_message_bytes,
                    peers.should_use_ble()
                );
            }

            Some(event) = event_rx.recv() => {
                match event {
                    mdns::Event::Ready { endpoint_id, max_message_size } => {
                        let payload_bytes = iroh_payload_budget(max_message_size);
                        max_iroh_message_bytes = Some(payload_bytes);
                        log::info!(
                            target: LOG_TARGET,
                            "transport ready transport=iroh localSession={} endpoint={} maxPayloadBytes={} gossipFrameBytes={}",
                            local_session_id.short(),
                            endpoint_id.fmt_short(),
                            payload_bytes,
                            max_message_size
                        );
                        request_frontier(&frontier_request_tx, "iroh-ready");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            iroh.as_ref().map(|transport| &transport.command_tx),
                            max_iroh_message_bytes
                        );
                    }
                    mdns::Event::PeerDiscovered { endpoint_id } => {
                        peers.mark_mdns(endpoint_id, true);
                        request_frontier(&frontier_request_tx, "iroh-peer-discovered");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            iroh.as_ref().map(|transport| &transport.command_tx),
                            max_iroh_message_bytes
                        );
                    }
                    mdns::Event::PeerExpired { endpoint_id } => {
                        peers.mark_mdns(endpoint_id, false);
                    }
                    mdns::Event::PeerConnected { endpoint_id } => {
                        peers.mark_iroh_connected(endpoint_id);
                        request_frontier(&frontier_request_tx, "iroh-peer-connected");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            iroh.as_ref().map(|transport| &transport.command_tx),
                            max_iroh_message_bytes
                        );
                    }
                    mdns::Event::PeerDisconnected { endpoint_id } => {
                        peers.mark_iroh_disconnected(endpoint_id);
                    }
                    mdns::Event::Message { endpoint_id, payload } => {
                        process_inbound(
                            InboundPayload {
                                transport: TransportKind::Iroh,
                                source: InboundSource::Iroh(endpoint_id),
                                payload
                            },
                            &mut peers,
                            &mut rx_dedup,
                            &incoming_tx
                        );
                    }
                    mdns::Event::TransportError(message) => {
                        log::error!(target: LOG_TARGET, "transport error transport=iroh message={message}");
                    }
                }
            }
        }
    }
}

fn iroh_payload_budget(max_message_size: usize) -> usize {
    max_message_size.saturating_sub(IROH_MESSAGE_OVERHEAD_BYTES as usize)
}

fn request_frontier(frontier_request_tx: &mpsc::UnboundedSender<()>, _reason: &'static str) {
    if frontier_request_tx.send(()).is_err() {
        log::warn!(target: LOG_TARGET, "queue frontier-request failed reason=receiver-closed");
    }
}

fn enqueue_outbound_message(
    message: OutboundMessage,
    iroh_queue: &mut TransportQueue,
    iroh_tx: Option<&mpsc::UnboundedSender<mdns::Command>>,
    max_iroh_message_bytes: Option<usize>,
    use_ble: bool,
) {
    if use_ble || outbound_message_kind(&message) == MessageKind::Frontier {
        enqueue_ble_message(&message);
    }

    iroh_queue.enqueue(message);
    drain_iroh_queue(iroh_queue, iroh_tx, max_iroh_message_bytes);
}

fn process_inbound(
    inbound: InboundPayload,
    peers: &mut PeerBook,
    rx_dedup: &mut RxDedup,
    incoming_tx: &mpsc::UnboundedSender<Vec<u8>>,
) {
    let now = Instant::now();
    let kind = MessageKind::from_payload(&inbound.payload);

    if !crate::sync::is_valid_protocol_payload(&inbound.payload, kind) {
        log::warn!(
            target: LOG_TARGET,
            "RX protocol skipped reason=invalid transport={} source={} kind={} bytes={}",
            inbound.transport.as_str(),
            inbound.source.label(),
            kind.as_str(),
            inbound.payload.len()
        );
        return;
    }

    if kind == MessageKind::Frontier
        && let Some(limit) = crate::sync::frontier_ble_limit(&inbound.payload)
    {
        peers.mark_ble_limit(inbound.source, limit);
    }

    peers.mark_seen(inbound.source, now);

    if rx_dedup.remember_or_duplicate(&inbound.payload, now) {
        return;
    }

    if incoming_tx.send(inbound.payload).is_err() {
        log::warn!(target: LOG_TARGET, "RX protocol dropped reason=ledger-receiver-closed");
    }
}

fn clear_network_channels() {
    if let Ok(mut guard) = BLE_INBOUND_TX.lock() {
        *guard = None;
    }
    clear_peer_snapshot();
}

fn enqueue_ble_message(message: &OutboundMessage) {
    let (lock, cvar) = &*BLE_ADVERTISER_STATE;
    let Ok(mut state) = lock.lock() else {
        return;
    };
    state.queue.enqueue(message.clone());
    cvar.notify_one();
}

pub(crate) fn start_ble_advertiser_queue() {
    let (lock, cvar) = &*BLE_ADVERTISER_STATE;
    if let Ok(mut state) = lock.lock() {
        state.active = true;
        cvar.notify_all();
    }
}

pub(crate) fn stop_ble_advertiser_queue() {
    let (lock, cvar) = &*BLE_ADVERTISER_STATE;
    if let Ok(mut state) = lock.lock() {
        state.active = false;
        state.clear();
        cvar.notify_all();
    }
}

pub(crate) fn next_ble_advertising_payload(
    _storage_path: &str,
    max_payload_bytes: usize,
) -> Option<Vec<u8>> {
    let max_payload_bytes = agreed_ble_payload_limit(max_payload_bytes);
    let max_sync_payload_bytes = max_payload_bytes.checked_sub(ble_payload_wrapper_size())?;
    let limit = TransportLimit {
        transport: TransportKind::Ble,
        max_payload_bytes: max_sync_payload_bytes,
    };
    let (lock, cvar) = &*BLE_ADVERTISER_STATE;
    let mut state = lock.lock().ok()?;

    loop {
        if !state.active {
            return None;
        }

        if let Some(sync_payload) = state.queue.pop_frame(limit) {
            let frame = build_ble_advertising_payload(&sync_payload, max_payload_bytes)?;
            state.remember(&frame);
            state.cursor = state.recent.len().saturating_sub(1);
            return Some(frame);
        }

        if !state.recent.is_empty() {
            state.cursor = (state.cursor + 1) % state.recent.len();
            return state.recent.get(state.cursor).cloned();
        }

        state = cvar.wait(state).ok()?;
    }
}

fn build_ble_advertising_payload(sync_payload: &[u8], max_payload_bytes: usize) -> Option<Vec<u8>> {
    let frame_len = ble_payload_wrapper_size().checked_add(sync_payload.len())?;
    if frame_len > max_payload_bytes {
        return None;
    }

    let session_id = session::get_session_id();
    let mut payload = Vec::with_capacity(frame_len);
    payload.extend_from_slice(&session_id.bytes());
    payload.extend_from_slice(sync_payload);
    payload.push(next_ble_advertising_tick());
    Some(payload)
}

pub(crate) fn ingest_ble_advertising_payload(_storage_path: &str, payload: &[u8]) -> bool {
    let Some((session_id, sync_payload)) = decode_ble_advertising_payload(payload) else {
        return false;
    };
    if session_id == session::get_session_id() || sync_payload.is_empty() {
        return false;
    }

    queue_ble_inbound(session_id, sync_payload)
}

fn queue_ble_inbound(session_id: SessionId, sync_payload: &[u8]) -> bool {
    let Ok(guard) = BLE_INBOUND_TX.lock() else {
        return false;
    };
    let Some(tx) = guard.as_ref() else {
        return false;
    };

    tx.send(InboundPayload {
        transport: TransportKind::Ble,
        source: InboundSource::Ble(session_id),
        payload: sync_payload.to_vec(),
    })
    .is_ok()
}

pub(super) fn session_id_from_ble_payload(payload: &[u8]) -> Option<SessionId> {
    decode_ble_advertising_payload(payload).map(|(session_id, _)| session_id)
}

fn decode_ble_advertising_payload(payload: &[u8]) -> Option<(SessionId, &[u8])> {
    if payload.len() <= ble_payload_wrapper_size() {
        return None;
    }
    let session_id = SessionId::from_slice(&payload[..SESSION_ID_BYTES])?;
    let sync_end = payload.len().checked_sub(BLE_TRANSPORT_TICK_BYTES)?;
    Some((session_id, &payload[SESSION_ID_BYTES..sync_end]))
}

fn ble_payload_wrapper_size() -> usize {
    SESSION_ID_BYTES + BLE_TRANSPORT_TICK_BYTES
}

fn next_ble_advertising_tick() -> u8 {
    BLE_ADVERTISING_TICK.fetch_add(1, Ordering::Relaxed)
}

fn drain_iroh_queue(
    queue: &mut TransportQueue,
    iroh_tx: Option<&mpsc::UnboundedSender<mdns::Command>>,
    max_message_bytes: Option<usize>,
) {
    let Some(iroh_tx) = iroh_tx else {
        return;
    };
    let Some(max_payload_bytes) = max_message_bytes else {
        return;
    };
    let limit = TransportLimit {
        transport: TransportKind::Iroh,
        max_payload_bytes,
    };

    while let Some(payload) = queue.pop_frame(limit) {
        if iroh_tx.send(mdns::Command::Broadcast(payload)).is_err() {
            log::warn!(target: LOG_TARGET, "TX protocol failed transport=iroh reason=sender-closed");
            return;
        }
    }
}

#[derive(Default)]
struct RxDedup {
    entries: VecDeque<RxDedupEntry>,
}

struct RxDedupEntry {
    hash: [u8; 32],
    seen_at: Instant,
}

impl RxDedup {
    fn remember_or_duplicate(&mut self, payload: &[u8], now: Instant) -> bool {
        while self
            .entries
            .front()
            .is_some_and(|entry| now.saturating_duration_since(entry.seen_at) >= RX_DEDUP_WINDOW)
        {
            self.entries.pop_front();
        }

        let hash = payload_hash(payload);
        if self.entries.iter().any(|entry| entry.hash == hash) {
            return true;
        }

        while self.entries.len() >= RX_DEDUP_CACHE_LIMIT {
            self.entries.pop_front();
        }
        self.entries.push_back(RxDedupEntry { hash, seen_at: now });
        false
    }
}

fn payload_hash(payload: &[u8]) -> [u8; 32] {
    Sha256::digest(payload).into()
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dole_MainActivity_initNdkContext<'local>(
    mut unowned_env: jni::EnvUnowned<'local>,
    _class: jni::objects::JClass<'local>,
    context: jni::objects::JObject<'local>,
) {
    let _ = unowned_env.with_env(|env| {
        let vm = env.get_java_vm().expect("Failed to get JavaVM");
        let context_ref = env
            .new_global_ref(&context)
            .expect("Failed to create global ref");

        unsafe {
            ndk_context::initialize_android_context(
                vm.get_raw() as *mut std::ffi::c_void,
                context_ref.as_obj().as_raw() as *mut std::ffi::c_void,
            );
        }
        std::mem::forget(context_ref);
        log::info!(target: "dole::android", "ndk-context initialized");
        Ok::<(), jni::errors::Error>(())
    });
}
