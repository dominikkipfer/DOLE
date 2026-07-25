mod ble;
mod mdns;
mod peerbook;
mod queue;
mod session;

use std::collections::VecDeque;
use std::sync::{Condvar, LazyLock, Mutex, atomic::{AtomicBool, AtomicU8, Ordering}};
use std::thread;
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};
use tokio::sync::mpsc;

use crate::constants::{
    NETWORK_FRONTIER_BROADCAST_INTERVAL_MS, PEER_LIVENESS_TIMEOUT_MS,
    PEERBOOK_MAINTENANCE_INTERVAL_MS, RX_DEDUP_LIMIT, RX_DEDUP_WINDOW_MS,
    SYNC_FRONTIER_ANNOUNCEMENT
};
use crate::logging;
use crate::sync::MessageKind;

pub(crate) use queue::OutboundMessage;

use peerbook::{PeerBook, clear_peer_snapshot};
use queue::{TransportLimit, TransportQueue};
use session::{SESSION_ID_BYTES, SessionId};

const LOG_TARGET: &str = "dole::network";
const FRONTIER_BROADCAST_INTERVAL: Duration = Duration::from_millis(NETWORK_FRONTIER_BROADCAST_INTERVAL_MS as u64);
const PEERBOOK_MAINTENANCE_INTERVAL: Duration = Duration::from_millis(PEERBOOK_MAINTENANCE_INTERVAL_MS as u64);
const PEER_LIVENESS_TIMEOUT: Duration = Duration::from_millis(PEER_LIVENESS_TIMEOUT_MS as u64);
const RX_DEDUP_WINDOW: Duration = Duration::from_millis(RX_DEDUP_WINDOW_MS as u64);
const RX_DEDUP_CACHE_LIMIT: usize = RX_DEDUP_LIMIT as usize;
const BLE_TRANSPORT_TICK_BYTES: usize = 1;

static BLE_INBOUND_TX: Mutex<Option<mpsc::UnboundedSender<InboundPayload>>> = Mutex::new(None);
static IROH_ENABLED: AtomicBool = AtomicBool::new(true);
static IROH_COMMAND_TX: Mutex<Option<mpsc::UnboundedSender<TransportToggle>>> = Mutex::new(None);
static IROH_ACTIVE: AtomicBool = AtomicBool::new(false);
static INTERNET_ACTIVE: AtomicBool = AtomicBool::new(false);
static BLE_ADVERTISING_TICK: AtomicU8 = AtomicU8::new(0);
static BLE_ADVERTISER_STATE: LazyLock<(Mutex<BleAdvertiserState>, Condvar)> = LazyLock::new(|| {
    (
        Mutex::new(BleAdvertiserState {
            active: false,
            queue: TransportQueue::new()
        }),
        Condvar::new()
    )
});

struct BleAdvertiserState {
    active: bool,
    queue: TransportQueue
}

#[derive(uniffi::Record)]
pub struct TransportStatus {
    pub ble: bool,
    pub iroh: bool,
    pub internet: bool
}

#[uniffi::export]
pub fn transport_status() -> TransportStatus {
    let ble = BLE_ADVERTISER_STATE.0.lock().map(|state| state.active).unwrap_or(false)
        && ble::advertising_active();
    TransportStatus {
        ble,
        iroh: IROH_ACTIVE.load(Ordering::Relaxed),
        internet: INTERNET_ACTIVE.load(Ordering::Relaxed)
    }
}

pub(super) fn set_internet_active(active: bool) {
    INTERNET_ACTIVE.store(active, Ordering::Relaxed);
}

pub(super) fn set_iroh_active(active: bool) {
    IROH_ACTIVE.store(active, Ordering::Relaxed);
}

#[derive(Clone, Copy, Eq, PartialEq)]
pub(crate) enum TransportKind {
    Ble,
    Iroh
}

impl TransportKind {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Ble => "ble",
            Self::Iroh => "iroh"
        }
    }
}

struct InboundPayload {
    transport: TransportKind,
    source: InboundSource,
    payload: Vec<u8>
}

struct IrohTransport {
    command_tx: mpsc::UnboundedSender<mdns::Command>,
    shutdown_tx: mpsc::UnboundedSender<()>
}

fn wants_iroh_endpoint() -> bool {
    IROH_ENABLED.load(Ordering::Relaxed) || mdns::is_internet_enabled()
}

fn spawn_iroh_transport(event_tx: &mpsc::UnboundedSender<mdns::Event>) -> IrohTransport {
    let (command_tx, command_rx) = mpsc::unbounded_channel();
    let (shutdown_tx, shutdown_rx) = mpsc::unbounded_channel();
    tokio::spawn(mdns::run(command_rx, event_tx.clone(), shutdown_rx));
    IrohTransport {
        command_tx,
        shutdown_tx
    }
}

#[derive(Clone, Copy)]
enum TransportToggle {
    Iroh(bool),
    Internet(bool)
}

fn signal_transport_toggle(toggle: TransportToggle) {
    if let Ok(guard) = IROH_COMMAND_TX.lock()
        && let Some(tx) = guard.as_ref()
    {
        let _ = tx.send(toggle);
    }
}

#[uniffi::export]
pub fn set_iroh_enabled(enabled: bool) {
    IROH_ENABLED.store(enabled, Ordering::Relaxed);
    mdns::set_iroh_discovery_enabled(enabled);
    signal_transport_toggle(TransportToggle::Iroh(enabled));
}

#[uniffi::export]
pub fn set_internet_enabled(enabled: bool) {
    mdns::set_internet_enabled_flag(enabled);
    signal_transport_toggle(TransportToggle::Internet(enabled));
}

#[derive(Clone, Copy)]
pub(crate) enum InboundSource {
    Ble(SessionId),
    Iroh(iroh::EndpointId)
}

impl InboundSource {
    fn label(self) -> String {
        match self {
            Self::Ble(session_id) => session_id.short(),
            Self::Iroh(endpoint_id) => endpoint_id.fmt_short().to_string()
        }
    }
}

pub(crate) fn start_sync_engine(
    outgoing_rx: mpsc::UnboundedReceiver<OutboundMessage>,
    incoming_tx: mpsc::UnboundedSender<Vec<u8>>,
    frontier_request_tx: mpsc::UnboundedSender<()>,
    shutdown_rx: mpsc::UnboundedReceiver<()>
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
            ble_inbound_rx
        ));
    });
}

async fn run_network(
    mut outgoing_rx: mpsc::UnboundedReceiver<OutboundMessage>,
    incoming_tx: mpsc::UnboundedSender<Vec<u8>>,
    frontier_request_tx: mpsc::UnboundedSender<()>,
    mut shutdown_rx: mpsc::UnboundedReceiver<()>,
    mut ble_inbound_rx: mpsc::UnboundedReceiver<InboundPayload>
) {
    let local_session_id = session::get_session_id();
    let (event_tx, mut event_rx) = mpsc::unbounded_channel();

    let (iroh_command_tx, mut iroh_command_rx) = mpsc::unbounded_channel::<TransportToggle>();
    if let Ok(mut guard) = IROH_COMMAND_TX.lock() {
        *guard = Some(iroh_command_tx);
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
                    let _ = transport.shutdown_tx.send(());
                }
                IROH_ACTIVE.store(false, Ordering::Relaxed);
                set_internet_active(false);
                clear_network_channels();
                break;
            }

            Some(toggle) = iroh_command_rx.recv() => {
                if wants_iroh_endpoint() {
                    if iroh.is_none() {
                        iroh = Some(spawn_iroh_transport(&event_tx));
                        log::info!(target: LOG_TARGET, "iroh endpoint started");
                    } else if let Some(transport) = iroh.as_ref() {
                        let command = match toggle {
                            TransportToggle::Iroh(enabled) => mdns::Command::SetDiscovery(enabled),
                            TransportToggle::Internet(enabled) => mdns::Command::SetRelay(enabled)
                        };
                        let _ = transport.command_tx.send(command);
                    }
                    if let TransportToggle::Iroh(false) = toggle {
                        peers.drop_iroh();
                    }
                } else if let Some(transport) = iroh.take() {
                    let _ = transport.shutdown_tx.send(());
                    max_iroh_message_bytes = None;
                    IROH_ACTIVE.store(false, Ordering::Relaxed);
                    set_internet_active(false);
                    peers.drop_iroh();
                    log::info!(target: LOG_TARGET, "iroh endpoint stopped");
                }
            }

            _ = frontier_interval.tick() => {
                request_frontier(&frontier_request_tx, "periodic");
            }

            _ = peerbook_interval.tick() => {
                if peers.expire(Instant::now(), PEER_LIVENESS_TIMEOUT) {
                    peers.log_summary("presence-expired");
                }
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
                    local_session_id,
                    peers.should_use_ble(),
                    peers.iroh_count()
                );
            }

            Some(event) = event_rx.recv() => {
                match event {
                    mdns::Event::Ready { endpoint_id, max_message_size } => {
                        max_iroh_message_bytes = Some(max_message_size);
                        log::info!(
                            target: LOG_TARGET,
                            "transport ready transport=iroh localSession={} endpoint={} maxMessageBytes={}",
                            local_session_id.short(),
                            endpoint_id.fmt_short(),
                            max_message_size
                        );
                        request_frontier(&frontier_request_tx, "iroh-ready");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            iroh.as_ref().map(|transport| &transport.command_tx),
                            max_iroh_message_bytes,
                            local_session_id,
                            peers.iroh_count()
                        );
                    }
                    mdns::Event::PeerDiscovered { endpoint_id } => {
                        if peers.mark_mdns(endpoint_id, true) {
                            peers.log_summary("presence");
                            log::debug!(
                                target: LOG_TARGET,
                                "transport discovery transport=mdns state=discovered endpoint={} session={}",
                                endpoint_id.fmt_short(),
                                peers.session_label(endpoint_id)
                            );
                        }
                        request_frontier(&frontier_request_tx, "iroh-peer-discovered");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            iroh.as_ref().map(|transport| &transport.command_tx),
                            max_iroh_message_bytes,
                            local_session_id,
                            peers.iroh_count()
                        );
                    }
                    mdns::Event::PeerExpired { endpoint_id } => {
                        if peers.mark_mdns(endpoint_id, false) {
                            peers.log_summary("presence");
                            log::debug!(
                                target: LOG_TARGET,
                                "transport discovery transport=mdns state=expired endpoint={}",
                                endpoint_id.fmt_short()
                            );
                        }
                    }
                    mdns::Event::PeerConnected { endpoint_id } => {
                        if peers.mark_iroh_connected(endpoint_id, Instant::now()) {
                            peers.log_summary("presence");
                        }
                        log::debug!(
                            target: LOG_TARGET,
                            "transport discovery transport=iroh state=connected endpoint={} session={}",
                            endpoint_id.fmt_short(),
                            peers.session_label(endpoint_id)
                        );
                        request_frontier(&frontier_request_tx, "iroh-peer-connected");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            iroh.as_ref().map(|transport| &transport.command_tx),
                            max_iroh_message_bytes,
                            local_session_id,
                            peers.iroh_count()
                        );
                    }
                    mdns::Event::PeerDisconnected { endpoint_id } => {
                        if peers.mark_iroh_disconnected(endpoint_id) {
                            peers.log_summary("iroh-disconnected");
                        }
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

fn request_frontier(frontier_request_tx: &mpsc::UnboundedSender<()>, reason: &'static str) {
    log::debug!(target: LOG_TARGET, "queue frontier-request reason={reason}");
    if frontier_request_tx.send(()).is_err() {
        log::warn!(target: LOG_TARGET, "queue frontier-request failed reason=receiver-closed");
    }
}

fn enqueue_outbound_message(
    message: OutboundMessage,
    iroh_queue: &mut TransportQueue,
    iroh_tx: Option<&mpsc::UnboundedSender<mdns::Command>>,
    max_iroh_message_bytes: Option<usize>,
    local_session_id: SessionId,
    use_ble: bool,
    iroh_peers: usize
) {
    let (kind, item_count, byte_count) = outbound_message_stats(&message);

    let enqueue_ble = use_ble || kind == MessageKind::Frontier;
    if enqueue_ble {
        enqueue_ble_message(&message);
        let reason = if use_ble { "selected" } else { "presence" };
        log::debug!(
            target: LOG_TARGET,
            "queue transport=ble reason={} kind={} items={} bytes={}",
            reason,
            kind.as_str(),
            item_count,
            byte_count
        );
    } else {
        log::debug!(
            target: LOG_TARGET,
            "queue transport=ble skipped reason=iroh-preferred kind={} items={} bytes={}",
            kind.as_str(),
            item_count,
            byte_count
        );
    }

    iroh_queue.enqueue(message);
    log::debug!(
        target: LOG_TARGET,
        "queue transport=iroh kind={} items={} bytes={}",
        kind.as_str(),
        item_count,
        byte_count
    );

    drain_iroh_queue(
        iroh_queue,
        iroh_tx,
        max_iroh_message_bytes,
        local_session_id,
        iroh_peers
    );
}

fn process_inbound(
    inbound: InboundPayload,
    peers: &mut PeerBook,
    rx_dedup: &mut RxDedup,
    incoming_tx: &mpsc::UnboundedSender<Vec<u8>>
) {
    let now = Instant::now();
    let kind = MessageKind::from_payload(&inbound.payload);
    let source_label = inbound.source.label();
    let valid_protocol_payload = crate::sync::is_valid_protocol_payload(&inbound.payload, kind);

    if !valid_protocol_payload {
        log::warn!(
            target: LOG_TARGET,
            "RX protocol skipped reason=invalid transport={} source={} kind={} bytes={}",
            inbound.transport.as_str(),
            source_label,
            kind.as_str(),
            inbound.payload.len()
        );
        return;
    }

    let presence_changed = peers.mark_seen(inbound.source, now);
    if presence_changed {
        peers.log_summary("presence");
    }

    if rx_dedup.remember_or_duplicate(
        inbound.transport,
        source_label.clone(),
        &inbound.payload,
        now
    ) {
        if kind == MessageKind::Frontier {
            log::info!(
                target: LOG_TARGET,
                "RX frontier ignored reason=duplicate transport={} source={} bytes={} peerBookChanged={} peerBookRefreshed=true",
                inbound.transport.as_str(),
                source_label,
                inbound.payload.len(),
                presence_changed
            );
            return;
        }
        log::info!(
            target: LOG_TARGET,
            "RX protocol ignored reason=duplicate transport={} source={} kind={} bytes={} peerBookChanged={} peerBookRefreshed=true",
            inbound.transport.as_str(),
            source_label,
            kind.as_str(),
            inbound.payload.len(),
            presence_changed
        );
        return;
    }

    if kind == MessageKind::Frontier {
        log::info!(
            target: LOG_TARGET,
            "RX frontier accepted transport={} source={} bytes={} peerBookChanged={}",
            inbound.transport.as_str(),
            source_label,
            inbound.payload.len(),
            presence_changed
        );
    } else {
        log::debug!(
            target: LOG_TARGET,
            "RX protocol transport={} source={} kind={} bytes={} peerBookChanged={}",
            inbound.transport.as_str(),
            source_label,
            kind.as_str(),
            inbound.payload.len(),
            presence_changed
        );
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
        state.queue.clear();
        cvar.notify_all();
    }
}

pub(crate) fn next_ble_advertising_payload(_storage_path: &str, max_payload_bytes: usize) -> Option<Vec<u8>> {
    let max_sync_payload_bytes = max_payload_bytes.checked_sub(ble_payload_wrapper_size())?;
    let limit = TransportLimit { transport: TransportKind::Ble, max_payload_bytes: max_sync_payload_bytes };
    let (lock, cvar) = &*BLE_ADVERTISER_STATE;
    let mut state = lock.lock().ok()?;

    loop {
        if !state.active {
            return None;
        }

        if let Some(sync_payload) = state.queue.pop_frame(limit) {
            drop(state);
            return build_ble_advertising_payload(&sync_payload, max_payload_bytes);
        }

        state = cvar.wait(state).ok()?;
    }
}

pub(crate) fn build_initial_ble_advertising_payload(_storage_path: &str) -> Vec<u8> {
    build_ble_advertising_payload(&[SYNC_FRONTIER_ANNOUNCEMENT], usize::MAX)
        .expect("empty frontier presence fits into an unconstrained BLE payload")
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
    let local_session_id = session::get_session_id();
    let Some((session_id, sync_payload)) = decode_ble_advertising_payload(payload) else {
        log::info!(target: LOG_TARGET, "RX BLE ignored reason=decode-failed bytes={}", payload.len());
        return false;
    };
    if session_id == local_session_id {
        log::debug!(
            target: LOG_TARGET,
            "RX BLE ignored reason=self session={} bytes={}",
            session_id.short(),
            payload.len()
        );
        return false;
    }
    if sync_payload.is_empty() {
        log::info!(
            target: LOG_TARGET,
            "RX BLE ignored reason=empty session={} bytes={}",
            session_id.short(),
            payload.len()
        );
        return false;
    }

    let Ok(guard) = BLE_INBOUND_TX.lock() else {
        log::info!(
            target: LOG_TARGET,
            "RX BLE ignored reason=inbound-lock-failed session={} kind={} bytes={}",
            session_id.short(),
            MessageKind::from_payload(sync_payload).as_str(),
            sync_payload.len()
        );
        return false;
    };
    let Some(tx) = guard.as_ref() else {
        log::info!(
            target: LOG_TARGET,
            "RX BLE ignored reason=sync-engine-unavailable session={} kind={} bytes={}",
            session_id.short(),
            MessageKind::from_payload(sync_payload).as_str(),
            sync_payload.len()
        );
        return false;
    };

    let kind = MessageKind::from_payload(sync_payload);
    let queued = tx
        .send(InboundPayload {
            transport: TransportKind::Ble,
            source: InboundSource::Ble(session_id),
            payload: sync_payload.to_vec()
        })
        .is_ok();
    if kind == MessageKind::Frontier {
        log::info!(
            target: LOG_TARGET,
            "RX BLE frontier queued={} session={} bytes={}",
            queued,
            session_id.short(),
            sync_payload.len()
        );
    }
    queued
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
    local_session_id: SessionId,
    connected_peers: usize
) {
    let Some(iroh_tx) = iroh_tx else {
        return;
    };
    let Some(max_payload_bytes) = max_message_bytes else {
        return;
    };
    let limit = TransportLimit {
        transport: TransportKind::Iroh,
        max_payload_bytes
    };

    while let Some(payload) = queue.pop_frame(limit) {
        let kind = MessageKind::from_payload(&payload);
        log::debug!(
            target: LOG_TARGET,
            "TX protocol transport=iroh session={} peers={} kind={} bytes={}",
            local_session_id.short(),
            connected_peers,
            kind.as_str(),
            payload.len()
        );

        if iroh_tx.send(mdns::Command::Broadcast(payload)).is_err() {
            log::warn!(target: LOG_TARGET, "TX protocol failed transport=iroh reason=sender-closed");
            return;
        }
    }
}

fn outbound_message_stats(message: &OutboundMessage) -> (MessageKind, usize, usize) {
    match message {
        OutboundMessage::Raw(payload) => (MessageKind::from_payload(payload), 1, payload.len()),
        OutboundMessage::Transactions(transactions) => (
            MessageKind::TxBatch,
            transactions.len(),
            transactions.iter().map(Vec::len).sum()
        )
    }
}

#[derive(Default)]
struct RxDedup {
    entries: VecDeque<RxDedupEntry>
}

struct RxDedupEntry {
    transport: TransportKind,
    source: String,
    hash: [u8; 32],
    seen_at: Instant
}

impl RxDedup {
    fn remember_or_duplicate(
        &mut self,
        transport: TransportKind,
        source: String,
        payload: &[u8],
        now: Instant
    ) -> bool {
        while self
            .entries
            .front()
            .is_some_and(|entry| now.saturating_duration_since(entry.seen_at) >= RX_DEDUP_WINDOW)
        {
            self.entries.pop_front();
        }

        let hash = payload_hash(payload);
        if let Some(entry) = self.entries.iter().find(|entry| entry.hash == hash) {
            log::debug!(
                target: LOG_TARGET,
                "RX duplicate matched previousTransport={} previousSource={}",
                entry.transport.as_str(),
                entry.source
            );
            return true;
        }

        while self.entries.len() >= RX_DEDUP_CACHE_LIMIT {
            self.entries.pop_front();
        }
        self.entries.push_back(RxDedupEntry {
            transport,
            source,
            hash,
            seen_at: now
        });
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
    context: jni::objects::JObject<'local>
) {
    let _ = unowned_env.with_env(|env| {
        let vm = env.get_java_vm().expect("Failed to get JavaVM");
        let context_ref = env.new_global_ref(&context).expect("Failed to create global ref");

        unsafe {
            ndk_context::initialize_android_context(
                vm.get_raw() as *mut std::ffi::c_void,
                context_ref.as_obj().as_raw() as *mut std::ffi::c_void
            );
        }
        std::mem::forget(context_ref);
        log::info!(target: "dole::android", "ndk-context initialized");
        Ok::<(), jni::errors::Error>(())
    });
}
