mod ble;
mod device;
mod mdns;

use std::collections::{HashMap, VecDeque};
use std::sync::{
    Condvar, LazyLock, Mutex,
    atomic::{AtomicU8, Ordering},
};
use std::thread;
use std::time::{Duration, Instant};

use modular_bitfield::prelude::*;
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;

use crate::constants::{
    ID_SIZE, LONG_SIZE, NETWORK_FRONTIER_BROADCAST_INTERVAL_MS, OP_BURN, OP_GENESIS, OP_MINT,
    OP_SEND, PEER_LIVENESS_TIMEOUT_MS, PEERBOOK_MAINTENANCE_INTERVAL_MS, RX_DEDUP_LIMIT,
    RX_DEDUP_WINDOW_MS, SIGNATURE_SIZE, WIRE_FRONTIER_ANNOUNCEMENT, WIRE_TX_BATCH,
};
use crate::logging;

use device::{DEVICE_ID_BYTES, DeviceId};

const LOG_TARGET: &str = "dole::network";
const FRONTIER_BROADCAST_INTERVAL: Duration =
    Duration::from_millis(NETWORK_FRONTIER_BROADCAST_INTERVAL_MS as u64);
const PEERBOOK_MAINTENANCE_INTERVAL: Duration =
    Duration::from_millis(PEERBOOK_MAINTENANCE_INTERVAL_MS as u64);
const PEER_LIVENESS_TIMEOUT: Duration = Duration::from_millis(PEER_LIVENESS_TIMEOUT_MS as u64);
const RX_DEDUP_WINDOW: Duration = Duration::from_millis(RX_DEDUP_WINDOW_MS as u64);
const RX_DEDUP_CACHE_LIMIT: usize = RX_DEDUP_LIMIT as usize;
const BATCH_HEADER_BYTES: usize = 3;
const BATCH_ENTRY_HEADER_BYTES: usize = 2;
const MAX_BATCH_TRANSACTIONS: usize = u16::MAX as usize;
const TRANSPORT_OUTBOUND_MESSAGE_LIMIT: usize = 128;
const TRANSPORT_OUTBOUND_FRAME_LIMIT: usize = 128;
const WIRE_ID_SIZE: usize = ID_SIZE as usize;
const WIRE_SIGNATURE_SIZE: usize = SIGNATURE_SIZE as usize;
const BLE_TRANSPORT_TICK_BYTES: usize = 1;

#[bitfield]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WireHeader {
    pub tx_type: B2,
    pub recovery_id: B2,
    pub ts_len: B3,
    #[skip]
    unused: B1,
}

#[bitfield]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WirePrefix {
    pub seq_len: B3,
    pub goc_len: B3,
    #[skip]
    unused: B2,
}

impl WireHeader {
    pub(crate) fn checked(tx_type: u8, recovery_id: u8, ts_len: u8) -> Option<Self> {
        if !matches!(tx_type, OP_GENESIS | OP_MINT | OP_BURN | OP_SEND) {
            return None;
        }
        if recovery_id > 3 || ts_len >= LONG_SIZE {
            return None;
        }

        let mut header = Self::new();
        header.set_tx_type(tx_type);
        header.set_recovery_id(recovery_id);
        header.set_ts_len(ts_len);
        Some(header)
    }

    pub(crate) fn decode(byte: u8) -> Option<Self> {
        let header = Self::from_bytes([byte]);
        let canonical = Self::checked(header.tx_type(), header.recovery_id(), header.ts_len())?;
        (canonical.into_bytes()[0] == byte).then_some(header)
    }
}

impl WirePrefix {
    pub(crate) fn checked(seq_len: u8, goc_len: u8) -> Option<Self> {
        if seq_len >= LONG_SIZE || goc_len >= LONG_SIZE {
            return None;
        }

        let mut prefix = Self::new();
        prefix.set_seq_len(seq_len);
        prefix.set_goc_len(goc_len);
        Some(prefix)
    }

    pub(crate) fn decode(byte: u8) -> Option<Self> {
        let prefix = Self::from_bytes([byte]);
        let canonical = Self::checked(prefix.seq_len(), prefix.goc_len())?;
        (canonical.into_bytes()[0] == byte).then_some(prefix)
    }
}

static BLE_INBOUND_TX: Mutex<Option<mpsc::UnboundedSender<InboundPayload>>> = Mutex::new(None);
static BLE_ADVERTISING_TICK: AtomicU8 = AtomicU8::new(0);
static BLE_ADVERTISER_STATE: LazyLock<(Mutex<BleAdvertiserState>, Condvar)> = LazyLock::new(|| {
    (
        Mutex::new(BleAdvertiserState {
            active: false,
            queue: TransportQueue::new(TransportKind::Ble),
        }),
        Condvar::new(),
    )
});

struct BleAdvertiserState {
    active: bool,
    queue: TransportQueue,
}

#[derive(Clone, Eq, PartialEq)]
pub(crate) enum OutboundMessage {
    Raw(Vec<u8>),
    Transactions(Vec<Vec<u8>>),
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum TransportKind {
    Ble,
    Iroh,
}

impl TransportKind {
    fn as_str(self) -> &'static str {
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

#[derive(Clone, Copy)]
enum InboundSource {
    Ble(DeviceId),
    Iroh(iroh::EndpointId),
}

impl InboundSource {
    fn label(self) -> String {
        match self {
            Self::Ble(device_id) => device_id.short(),
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
    let local_device_id = device::get_session_device_id();
    let (iroh_tx, iroh_rx) = mpsc::unbounded_channel();
    let (event_tx, mut event_rx) = mpsc::unbounded_channel();
    let (iroh_shutdown_tx, iroh_shutdown_rx) = mpsc::unbounded_channel();

    tokio::spawn(mdns::run(
        local_device_id,
        iroh_rx,
        event_tx,
        iroh_shutdown_rx,
    ));

    let mut peers = PeerBook::default();
    let mut rx_dedup = RxDedup::default();
    let mut iroh_queue = TransportQueue::new(TransportKind::Iroh);
    let mut max_iroh_message_bytes = None;
    let mut frontier_interval = tokio::time::interval(FRONTIER_BROADCAST_INTERVAL);
    frontier_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut peerbook_interval = tokio::time::interval(PEERBOOK_MAINTENANCE_INTERVAL);
    peerbook_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    request_frontier(&frontier_request_tx, "startup");

    loop {
        tokio::select! {
            _ = shutdown_rx.recv() => {
                let _ = iroh_shutdown_tx.send(());
                clear_network_channels();
                break;
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
                    &iroh_tx,
                    max_iroh_message_bytes,
                    local_device_id,
                    peers.should_use_ble(),
                    peers.iroh_count(),
                );
            }

            Some(event) = event_rx.recv() => {
                match event {
                    mdns::Event::Ready { endpoint_id, max_message_size } => {
                        max_iroh_message_bytes = Some(max_message_size);
                        log::info!(
                            target: LOG_TARGET,
                            "transport ready transport=iroh localDevice={} endpoint={} maxMessageBytes={}",
                            local_device_id.short(),
                            endpoint_id.fmt_short(),
                            max_message_size
                        );
                        request_frontier(&frontier_request_tx, "iroh-ready");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            &iroh_tx,
                            max_iroh_message_bytes,
                            local_device_id,
                            peers.iroh_count(),
                        );
                    }
                    mdns::Event::PeerDiscovered { endpoint_id, device_id } => {
                        if peers.mark_mdns(endpoint_id, device_id, true) {
                            peers.log_summary("presence");
                            log::debug!(
                                target: LOG_TARGET,
                                "transport discovery transport=mdns state=discovered endpoint={} device={}",
                                endpoint_id.fmt_short(),
                                peers.device_label(endpoint_id)
                            );
                        }
                        request_frontier(&frontier_request_tx, "iroh-peer-discovered");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            &iroh_tx,
                            max_iroh_message_bytes,
                            local_device_id,
                            peers.iroh_count(),
                        );
                    }
                    mdns::Event::PeerExpired { endpoint_id } => {
                        if peers.mark_mdns(endpoint_id, None, false) {
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
                            "transport discovery transport=iroh state=connected endpoint={} device={}",
                            endpoint_id.fmt_short(),
                            peers.device_label(endpoint_id)
                        );
                        request_frontier(&frontier_request_tx, "iroh-peer-connected");
                        drain_iroh_queue(
                            &mut iroh_queue,
                            &iroh_tx,
                            max_iroh_message_bytes,
                            local_device_id,
                            peers.iroh_count(),
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
                                payload,
                            },
                            &mut peers,
                            &mut rx_dedup,
                            &incoming_tx,
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
    iroh_tx: &mpsc::UnboundedSender<mdns::Command>,
    max_iroh_message_bytes: Option<usize>,
    local_device_id: DeviceId,
    use_ble: bool,
    iroh_peers: usize,
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
        local_device_id,
        iroh_peers,
    );
}

fn process_inbound(
    inbound: InboundPayload,
    peers: &mut PeerBook,
    rx_dedup: &mut RxDedup,
    incoming_tx: &mpsc::UnboundedSender<Vec<u8>>,
) {
    let now = Instant::now();
    let kind = MessageKind::from_payload(&inbound.payload);
    let source_label = inbound.source.label();
    let valid_protocol_payload = is_valid_protocol_payload(&inbound.payload, kind);

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
        now,
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

pub(crate) fn next_ble_advertising_payload(
    _storage_path: &str,
    max_payload_bytes: usize,
) -> Option<Vec<u8>> {
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
            drop(state);
            return build_ble_advertising_payload(&sync_payload, max_payload_bytes);
        }

        state = cvar.wait(state).ok()?;
    }
}

pub(crate) fn build_initial_ble_advertising_payload(_storage_path: &str) -> Vec<u8> {
    build_ble_advertising_payload(&[WIRE_FRONTIER_ANNOUNCEMENT], usize::MAX)
        .expect("empty frontier presence fits into an unconstrained BLE payload")
}

fn build_ble_advertising_payload(sync_payload: &[u8], max_payload_bytes: usize) -> Option<Vec<u8>> {
    let frame_len = ble_payload_wrapper_size().checked_add(sync_payload.len())?;
    if frame_len > max_payload_bytes {
        return None;
    }

    let device_id = device::get_session_device_id();
    let mut payload = Vec::with_capacity(frame_len);
    payload.extend_from_slice(&device_id.bytes());
    payload.extend_from_slice(sync_payload);
    payload.push(next_ble_advertising_tick());
    Some(payload)
}

pub(crate) fn ingest_ble_advertising_payload(_storage_path: &str, payload: &[u8]) -> bool {
    let local_device_id = device::get_session_device_id();
    let Some((device_id, sync_payload)) = decode_ble_advertising_payload(payload) else {
        log::info!(
            target: LOG_TARGET,
            "RX BLE ignored reason=decode-failed bytes={}",
            payload.len()
        );
        return false;
    };
    if device_id == local_device_id {
        log::debug!(
            target: LOG_TARGET,
            "RX BLE ignored reason=self device={} bytes={}",
            device_id.short(),
            payload.len()
        );
        return false;
    }
    if sync_payload.is_empty() {
        log::info!(
            target: LOG_TARGET,
            "RX BLE ignored reason=empty device={} bytes={}",
            device_id.short(),
            payload.len()
        );
        return false;
    }

    let Ok(guard) = BLE_INBOUND_TX.lock() else {
        log::info!(
            target: LOG_TARGET,
            "RX BLE ignored reason=inbound-lock-failed device={} kind={} bytes={}",
            device_id.short(),
            MessageKind::from_payload(sync_payload).as_str(),
            sync_payload.len()
        );
        return false;
    };
    let Some(tx) = guard.as_ref() else {
        log::info!(
            target: LOG_TARGET,
            "RX BLE ignored reason=sync-engine-unavailable device={} kind={} bytes={}",
            device_id.short(),
            MessageKind::from_payload(sync_payload).as_str(),
            sync_payload.len()
        );
        return false;
    };

    let kind = MessageKind::from_payload(sync_payload);
    let queued = tx
        .send(InboundPayload {
            transport: TransportKind::Ble,
            source: InboundSource::Ble(device_id),
            payload: sync_payload.to_vec(),
        })
        .is_ok();
    if kind == MessageKind::Frontier {
        log::info!(
            target: LOG_TARGET,
            "RX BLE frontier queued={} device={} bytes={}",
            queued,
            device_id.short(),
            sync_payload.len()
        );
    }
    queued
}

pub(super) fn device_id_from_ble_payload(payload: &[u8]) -> Option<DeviceId> {
    decode_ble_advertising_payload(payload).map(|(device_id, _)| device_id)
}

fn decode_ble_advertising_payload(payload: &[u8]) -> Option<(DeviceId, &[u8])> {
    if payload.len() <= ble_payload_wrapper_size() {
        return None;
    }
    let device_id = DeviceId::from_slice(&payload[..DEVICE_ID_BYTES])?;
    let sync_end = payload.len().checked_sub(BLE_TRANSPORT_TICK_BYTES)?;
    Some((device_id, &payload[DEVICE_ID_BYTES..sync_end]))
}

fn ble_payload_header_size() -> usize {
    DEVICE_ID_BYTES
}

fn ble_payload_wrapper_size() -> usize {
    ble_payload_header_size() + BLE_TRANSPORT_TICK_BYTES
}

fn next_ble_advertising_tick() -> u8 {
    BLE_ADVERTISING_TICK.fetch_add(1, Ordering::Relaxed)
}

fn drain_iroh_queue(
    queue: &mut TransportQueue,
    iroh_tx: &mpsc::UnboundedSender<mdns::Command>,
    max_message_bytes: Option<usize>,
    local_device_id: DeviceId,
    connected_peers: usize,
) {
    let Some(max_payload_bytes) = max_message_bytes else {
        return;
    };
    let limit = TransportLimit {
        transport: TransportKind::Iroh,
        max_payload_bytes,
    };

    while let Some(payload) = queue.pop_frame(limit) {
        let kind = MessageKind::from_payload(&payload);
        log::debug!(
            target: LOG_TARGET,
            "TX protocol transport=iroh device={} peers={} kind={} bytes={}",
            local_device_id.short(),
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
            transactions.iter().map(Vec::len).sum(),
        ),
    }
}

struct TransportQueue {
    messages: VecDeque<OutboundMessage>,
    frames: VecDeque<Vec<u8>>,
}

impl TransportQueue {
    fn new(transport: TransportKind) -> Self {
        let _ = transport;
        Self {
            messages: VecDeque::new(),
            frames: VecDeque::new(),
        }
    }

    fn enqueue(&mut self, message: OutboundMessage) {
        let kind = outbound_message_kind(&message);
        if kind == MessageKind::Frontier {
            self.messages
                .retain(|queued| outbound_message_kind(queued) != MessageKind::Frontier);
            self.frames
                .retain(|frame| MessageKind::from_payload(frame) != MessageKind::Frontier);
        }

        if kind == MessageKind::Frontier
            && let OutboundMessage::Raw(payload) = message
        {
            self.frames.push_front(payload);
            return;
        }

        while self.messages.len() >= TRANSPORT_OUTBOUND_MESSAGE_LIMIT {
            self.messages.pop_front();
        }
        self.messages.push_back(message);
    }

    fn pop_frame(&mut self, limit: TransportLimit) -> Option<Vec<u8>> {
        loop {
            if let Some(frame) = self.frames.pop_front() {
                if frame.len() <= limit.max_payload_bytes {
                    return Some(frame);
                }
                log::warn!(
                    target: LOG_TARGET,
                    "TX protocol skipped transport={} reason=frame-too-large bytes={} maxBytes={}",
                    limit.transport.as_str(),
                    frame.len(),
                    limit.max_payload_bytes
                );
                continue;
            }

            let message = self.messages.pop_front()?;
            let frames = encode_outbound_frames(&message, limit);
            for frame in frames {
                while self.frames.len() >= TRANSPORT_OUTBOUND_FRAME_LIMIT {
                    self.frames.pop_front();
                }
                self.frames.push_back(frame);
            }
        }
    }

    fn clear(&mut self) {
        self.messages.clear();
        self.frames.clear();
    }
}

#[derive(Clone, Copy)]
struct TransportLimit {
    transport: TransportKind,
    max_payload_bytes: usize,
}

fn encode_outbound_frames(message: &OutboundMessage, limit: TransportLimit) -> Vec<Vec<u8>> {
    match message {
        OutboundMessage::Raw(payload) => {
            if payload.len() > limit.max_payload_bytes {
                log::warn!(
                    target: LOG_TARGET,
                    "TX protocol skipped transport={} reason=payload-too-large bytes={} maxBytes={}",
                    limit.transport.as_str(),
                    payload.len(),
                    limit.max_payload_bytes
                );
                Vec::new()
            } else {
                vec![payload.clone()]
            }
        }
        OutboundMessage::Transactions(transactions) => {
            encode_transaction_batches(transactions, limit)
        }
    }
}

fn encode_transaction_batches(transactions: &[Vec<u8>], limit: TransportLimit) -> Vec<Vec<u8>> {
    if limit.max_payload_bytes <= BATCH_HEADER_BYTES + BATCH_ENTRY_HEADER_BYTES {
        return Vec::new();
    }

    let mut batches = Vec::new();
    let mut current = Vec::new();
    current.push(WIRE_TX_BATCH);
    current.extend_from_slice(&0u16.to_be_bytes());
    let mut count = 0usize;

    for tx in transactions {
        let Ok(tx_len) = u16::try_from(tx.len()) else {
            log::warn!(
                target: LOG_TARGET,
                "TX protocol skipped transport={} reason=tx-too-large bytes={}",
                limit.transport.as_str(),
                tx.len()
            );
            continue;
        };
        let entry_bytes = BATCH_ENTRY_HEADER_BYTES + tx.len();
        if entry_bytes + BATCH_HEADER_BYTES > limit.max_payload_bytes {
            log::warn!(
                target: LOG_TARGET,
                "TX protocol skipped transport={} reason=tx-exceeds-payload bytes={} maxBytes={}",
                limit.transport.as_str(),
                tx.len(),
                limit.max_payload_bytes
            );
            continue;
        }

        if count > 0
            && (count == MAX_BATCH_TRANSACTIONS
                || current.len() + entry_bytes > limit.max_payload_bytes)
        {
            set_batch_count(&mut current, count);
            batches.push(current);
            current = Vec::new();
            current.push(WIRE_TX_BATCH);
            current.extend_from_slice(&0u16.to_be_bytes());
            count = 0;
        }

        current.extend_from_slice(&tx_len.to_be_bytes());
        current.extend_from_slice(tx);
        count += 1;
    }

    if count > 0 {
        set_batch_count(&mut current, count);
        batches.push(current);
    }

    batches
}

fn set_batch_count(payload: &mut [u8], count: usize) {
    let count = u16::try_from(count).unwrap_or(u16::MAX);
    payload[1..3].copy_from_slice(&count.to_be_bytes());
}

fn outbound_message_kind(message: &OutboundMessage) -> MessageKind {
    match message {
        OutboundMessage::Raw(payload) => MessageKind::from_payload(payload),
        OutboundMessage::Transactions(_) => MessageKind::TxBatch,
    }
}

pub(crate) fn encode_frontier_announcement(entries: Vec<(String, u64)>) -> Vec<u8> {
    let mut entries = entries
        .into_iter()
        .filter_map(|(branch, seq)| {
            let id = id_hex_to_array(&branch)?;
            Some((id, seq))
        })
        .collect::<Vec<_>>();

    if entries.is_empty() {
        return vec![WIRE_FRONTIER_ANNOUNCEMENT];
    }
    if entries.len() > u16::MAX as usize {
        entries.truncate(u16::MAX as usize);
    }

    let count = u16::try_from(entries.len()).ok().unwrap_or(u16::MAX);
    let payload_len = entries
        .iter()
        .map(|(_, seq)| WIRE_ID_SIZE + prefix_varint_len(*seq))
        .sum::<usize>();
    let mut msg = Vec::with_capacity(3 + payload_len);

    msg.push(WIRE_FRONTIER_ANNOUNCEMENT);
    msg.extend_from_slice(&count.to_be_bytes());

    for (id, seq) in entries {
        msg.extend_from_slice(&id);
        encode_prefix_varint(seq, &mut msg);
    }

    msg
}

pub(crate) fn decode_frontier_announcement(payload: &[u8]) -> Option<HashMap<String, u64>> {
    let mut reader = WireReader::new(payload);

    if reader.read_u8()? != WIRE_FRONTIER_ANNOUNCEMENT {
        return None;
    }

    if reader.is_done() {
        return Some(HashMap::new());
    }

    let count = usize::from(reader.read_u16()?);
    let mut sequences = HashMap::new();

    for _ in 0..count {
        let branch = hex::encode_upper(reader.read_bytes(WIRE_ID_SIZE)?);
        let seq = read_prefix_varint(&mut reader)?;
        sequences.insert(branch, seq);
    }

    reader.is_done().then_some(sequences)
}

pub(crate) fn decode_transaction_batch(payload: &[u8]) -> Option<Vec<Vec<u8>>> {
    if payload.first() != Some(&WIRE_TX_BATCH) {
        return None;
    }

    let mut reader = WireReader::new(payload);
    reader.read_u8()?;
    let count = usize::from(reader.read_u16()?);
    if count == 0 {
        return None;
    }

    let mut transactions = Vec::with_capacity(count);
    for _ in 0..count {
        let tx_len = usize::from(reader.read_u16()?);
        transactions.push(reader.read_bytes(tx_len)?.to_vec());
    }

    reader.is_done().then_some(transactions)
}

pub(crate) fn group_u64_len_code(value: u64) -> u8 {
    let bytes = value.to_le_bytes();
    let len = bytes
        .iter()
        .rposition(|byte| *byte != 0)
        .map(|index| index + 1)
        .unwrap_or(1);
    (len - 1) as u8
}

pub(crate) fn push_group_u64(msg: &mut Vec<u8>, value: u64, len_code: u8) {
    let len = group_u64_len_from_code(len_code)
        .expect("group-u64 length code is generated by group_u64_len_code");
    msg.extend_from_slice(&value.to_le_bytes()[..len]);
}

fn group_u64_len_from_code(len_code: u8) -> Option<usize> {
    if len_code >= LONG_SIZE {
        return None;
    }

    Some(usize::from(len_code) + 1)
}

fn prefix_varint_len(value: u64) -> usize {
    let bits = (u64::BITS - value.leading_zeros()) as usize;
    if bits > 56 {
        9
    } else {
        bits.div_ceil(7).max(1)
    }
}

fn encode_prefix_varint(value: u64, buf: &mut Vec<u8>) {
    let total_len = prefix_varint_len(value);
    if total_len == 9 {
        buf.push(u8::MAX);
        buf.extend_from_slice(&value.to_le_bytes());
        return;
    }

    let first_payload_bits = 8 - total_len;
    let prefix = if total_len == 1 {
        0
    } else {
        u8::MAX << (9 - total_len)
    };
    let payload_mask = if first_payload_bits == 0 {
        0
    } else {
        (1u8 << first_payload_bits) - 1
    };
    buf.push(prefix | ((value as u8) & payload_mask));

    if total_len > 1 {
        let remaining = value >> first_payload_bits;
        buf.extend_from_slice(&remaining.to_le_bytes()[..total_len - 1]);
    }
}

fn read_prefix_varint(reader: &mut WireReader<'_>) -> Option<u64> {
    let first = reader.read_u8()?;
    let leading_ones = first.leading_ones() as usize;

    let value = if leading_ones == 8 {
        let bytes = reader.read_bytes(8)?;
        let mut raw = [0; 8];
        raw.copy_from_slice(bytes);
        u64::from_le_bytes(raw)
    } else {
        let total_len = leading_ones + 1;
        let first_payload_bits = 8 - total_len;
        let payload_mask = if first_payload_bits == 0 {
            0
        } else {
            (1u8 << first_payload_bits) - 1
        };
        let mut value = u64::from(first & payload_mask);

        if total_len > 1 {
            let bytes = reader.read_bytes(total_len - 1)?;
            let mut remaining = [0; 8];
            remaining[..bytes.len()].copy_from_slice(bytes);
            value |= u64::from_le_bytes(remaining) << first_payload_bits;
        }

        value
    };

    let total_len = if leading_ones == 8 {
        9
    } else {
        leading_ones + 1
    };
    (prefix_varint_len(value) == total_len).then_some(value)
}

fn id_hex_to_array(value: &str) -> Option<[u8; WIRE_ID_SIZE]> {
    let bytes = hex::decode(value).ok()?;
    if bytes.len() != WIRE_ID_SIZE {
        return None;
    }

    let mut out = [0; WIRE_ID_SIZE];
    out.copy_from_slice(&bytes);
    Some(out)
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum MessageKind {
    Frontier,
    TxBatch,
    Tx,
}

impl MessageKind {
    fn from_payload(payload: &[u8]) -> Self {
        if payload.first() == Some(&WIRE_FRONTIER_ANNOUNCEMENT) {
            Self::Frontier
        } else if payload.first() == Some(&WIRE_TX_BATCH) {
            Self::TxBatch
        } else {
            Self::Tx
        }
    }

    fn as_str(self) -> &'static str {
        match self {
            Self::Frontier => "frontier",
            Self::TxBatch => "tx-batch",
            Self::Tx => "tx",
        }
    }
}

fn is_valid_protocol_payload(payload: &[u8], kind: MessageKind) -> bool {
    match kind {
        MessageKind::Frontier => decode_frontier_announcement(payload).is_some(),
        MessageKind::TxBatch => decode_transaction_batch(payload).is_some(),
        MessageKind::Tx => is_valid_single_tx_payload_shape(payload),
    }
}

fn is_valid_single_tx_payload_shape(payload: &[u8]) -> bool {
    let mut reader = WireReader::new(payload);
    let Some(header) = reader.read_u8().and_then(WireHeader::decode) else {
        return false;
    };
    let tx_type = header.tx_type();
    let ts_len = header.ts_len();

    match tx_type {
        OP_GENESIS => {
            reader.read_bytes(WIRE_SIGNATURE_SIZE).is_some()
                && reader.read_bytes(WIRE_SIGNATURE_SIZE).is_some()
                && reader.read_group_u64(ts_len).is_some()
                && reader.is_done()
        }
        OP_MINT | OP_BURN => {
            let Some(prefix) = reader.read_u8().and_then(WirePrefix::decode) else {
                return false;
            };
            reader.read_group_u64(prefix.seq_len()).is_some()
                && reader.read_group_u64(prefix.goc_len()).is_some()
                && reader.read_group_u64(ts_len).is_some()
                && reader.read_bytes(WIRE_SIGNATURE_SIZE).is_some()
                && reader.is_done()
        }
        OP_SEND => {
            let Some(prefix) = reader.read_u8().and_then(WirePrefix::decode) else {
                return false;
            };
            reader.read_group_u64(prefix.seq_len()).is_some()
                && reader.read_group_u64(prefix.goc_len()).is_some()
                && reader.read_group_u64(ts_len).is_some()
                && reader.read_bytes(WIRE_ID_SIZE).is_some()
                && reader.read_bytes(WIRE_SIGNATURE_SIZE).is_some()
                && reader.is_done()
        }
        _ => false,
    }
}

pub(crate) struct WireReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> WireReader<'a> {
    pub(crate) fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    pub(crate) fn read_u8(&mut self) -> Option<u8> {
        let byte = *self.data.get(self.pos)?;
        self.pos += 1;
        Some(byte)
    }

    pub(crate) fn read_u16(&mut self) -> Option<u16> {
        let bytes = self.read_bytes(2)?;
        Some(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    pub(crate) fn read_group_u64(&mut self, len_code: u8) -> Option<u64> {
        let len = group_u64_len_from_code(len_code)?;
        let bytes = self.read_bytes(len)?;
        let mut value = [0u8; LONG_SIZE as usize];
        value[..len].copy_from_slice(bytes);
        Some(u64::from_le_bytes(value))
    }

    pub(crate) fn read_bytes(&mut self, len: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(len)?;
        let bytes = self.data.get(self.pos..end)?;
        self.pos = end;
        Some(bytes)
    }

    pub(crate) fn is_done(&self) -> bool {
        self.pos == self.data.len()
    }
}

#[derive(Default)]
struct RxDedup {
    entries: VecDeque<RxDedupEntry>,
}

struct RxDedupEntry {
    transport: TransportKind,
    source: String,
    hash: [u8; 32],
    seen_at: Instant,
}

impl RxDedup {
    fn remember_or_duplicate(
        &mut self,
        transport: TransportKind,
        source: String,
        payload: &[u8],
        now: Instant,
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
            seen_at: now,
        });
        false
    }
}

fn payload_hash(payload: &[u8]) -> [u8; 32] {
    let digest = Sha256::digest(payload);
    let mut hash = [0; 32];
    hash.copy_from_slice(&digest);
    hash
}

#[derive(Default)]
struct PeerBook {
    ble_devices: HashMap<DeviceId, BlePeerState>,
    endpoints: HashMap<iroh::EndpointId, EndpointPeerState>,
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
struct BlePeerState {
    ble_seen_at: Option<Instant>,
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
struct EndpointPeerState {
    device_id: Option<DeviceId>,
    mdns: bool,
    iroh_seen_at: Option<Instant>,
}

impl PeerBook {
    fn mark_mdns(
        &mut self,
        endpoint_id: iroh::EndpointId,
        device_id: Option<DeviceId>,
        active: bool,
    ) -> bool {
        let Some(device_id) = device_id.or_else(|| {
            self.endpoints
                .get(&endpoint_id)
                .and_then(|state| state.device_id)
        }) else {
            return false;
        };

        let old = self.summary_entries();
        let entry = self.endpoints.entry(endpoint_id).or_default();
        entry.device_id = Some(device_id);
        entry.mdns = active;
        self.cleanup_endpoint(endpoint_id);
        old != self.summary_entries()
    }

    fn mark_seen(&mut self, source: InboundSource, seen_at: Instant) -> bool {
        match source {
            InboundSource::Ble(device_id) => self.mark_ble_seen(device_id, seen_at),
            InboundSource::Iroh(endpoint_id) => self.mark_iroh_seen(endpoint_id, seen_at),
        }
    }

    fn mark_ble_seen(&mut self, device_id: DeviceId, seen_at: Instant) -> bool {
        let old = self.summary_entries();
        let entry = self.ble_devices.entry(device_id).or_default();
        entry.ble_seen_at = Some(seen_at);
        old != self.summary_entries()
    }

    fn mark_iroh_seen(&mut self, endpoint_id: iroh::EndpointId, seen_at: Instant) -> bool {
        self.mark_iroh_connected(endpoint_id, seen_at)
    }

    fn mark_iroh_connected(&mut self, endpoint_id: iroh::EndpointId, seen_at: Instant) -> bool {
        let old = self.summary_entries();
        let entry = self.endpoints.entry(endpoint_id).or_default();
        entry.iroh_seen_at = Some(seen_at);
        old != self.summary_entries()
    }

    fn mark_iroh_disconnected(&mut self, endpoint_id: iroh::EndpointId) -> bool {
        let old = self.summary_entries();
        if let Some(entry) = self.endpoints.get_mut(&endpoint_id) {
            entry.iroh_seen_at = None;
        }
        self.cleanup_endpoint(endpoint_id);
        old != self.summary_entries()
    }

    fn device_label(&self, endpoint_id: iroh::EndpointId) -> String {
        self.endpoints
            .get(&endpoint_id)
            .and_then(|state| state.device_id)
            .map(|device_id| device_id.short())
            .unwrap_or_else(|| "unknown".to_string())
    }

    fn iroh_count(&self) -> usize {
        self.endpoints
            .values()
            .filter(|state| state.device_id.is_some() && endpoint_state_iroh_available(state))
            .count()
    }

    fn should_use_ble(&self) -> bool {
        if self.iroh_count() == 0 {
            return true;
        }

        self.ble_devices.iter().any(|(device_id, state)| {
            state.ble_seen_at.is_some() && !self.device_has_iroh(*device_id)
        })
    }

    fn expire(&mut self, now: Instant, timeout: Duration) -> bool {
        let old = self.summary_entries();

        let devices = self.ble_devices.keys().copied().collect::<Vec<_>>();
        for device_id in devices {
            if let Some(state) = self.ble_devices.get_mut(&device_id)
                && state
                    .ble_seen_at
                    .is_some_and(|seen_at| now.saturating_duration_since(seen_at) >= timeout)
            {
                let age_ms = state
                    .ble_seen_at
                    .map(|seen_at| now.saturating_duration_since(seen_at).as_millis())
                    .unwrap_or_default();
                log::info!(
                    target: LOG_TARGET,
                    "PeerBook presence expired transport=ble device={} ageMs={} timeoutMs={}",
                    device_id.short(),
                    age_ms,
                    timeout.as_millis()
                );
                state.ble_seen_at = None;
            }
            self.cleanup_ble_device(device_id);
        }

        let endpoints = self.endpoints.keys().copied().collect::<Vec<_>>();
        for endpoint_id in endpoints {
            if let Some(state) = self.endpoints.get_mut(&endpoint_id)
                && state
                    .iroh_seen_at
                    .is_some_and(|seen_at| now.saturating_duration_since(seen_at) >= timeout)
            {
                let age_ms = state
                    .iroh_seen_at
                    .map(|seen_at| now.saturating_duration_since(seen_at).as_millis())
                    .unwrap_or_default();
                log::info!(
                    target: LOG_TARGET,
                    "PeerBook presence expired transport=iroh endpoint={} device={} ageMs={} timeoutMs={}",
                    endpoint_id.fmt_short(),
                    state
                        .device_id
                        .map(|device_id| device_id.short())
                        .unwrap_or_else(|| "unknown".to_string()),
                    age_ms,
                    timeout.as_millis()
                );
                state.iroh_seen_at = None;
            }
            self.cleanup_endpoint(endpoint_id);
        }

        old != self.summary_entries()
    }

    fn log_summary(&self, reason: &'static str) {
        let peers = self.summary_entries();

        if peers.is_empty() {
            log::info!(target: LOG_TARGET, "PeerBook reason={reason} peers=none");
            return;
        }

        log::info!(
            target: LOG_TARGET,
            "PeerBook reason={} peers={}",
            reason,
            peers.join(", ")
        );
    }

    fn summary_entries(&self) -> Vec<String> {
        let mut peers = self
            .endpoints
            .iter()
            .filter(|(_, state)| state.device_id.is_some() && endpoint_state_iroh_available(state))
            .map(|(endpoint_id, state)| {
                let device_id = state.device_id.expect("filtered known endpoint");
                format!(
                    "device={} endpoint={} ble={} iroh={}",
                    device_id.short(),
                    endpoint_id.fmt_short(),
                    self.ble_label_for_endpoint_device(device_id),
                    connection_label(true)
                )
            })
            .collect::<Vec<_>>();
        peers.extend(
            self.ble_devices
                .iter()
                .filter(|(device_id, state)| {
                    state.ble_seen_at.is_some() && !self.device_has_iroh(**device_id)
                })
                .map(|(device_id, _)| {
                    format!(
                        "device={} endpoint=none ble={} iroh={}",
                        device_id.short(),
                        connection_label(true),
                        connection_label(false)
                    )
                }),
        );
        peers.sort();
        peers
    }

    fn device_has_iroh(&self, device_id: DeviceId) -> bool {
        self.endpoints
            .values()
            .any(|state| state.device_id == Some(device_id) && endpoint_state_iroh_available(state))
    }

    fn ble_label_for_endpoint_device(&self, device_id: DeviceId) -> &'static str {
        if self
            .ble_devices
            .get(&device_id)
            .is_some_and(|state| state.ble_seen_at.is_some())
        {
            "receiving"
        } else {
            "standby"
        }
    }

    fn cleanup_ble_device(&mut self, device_id: DeviceId) {
        if self
            .ble_devices
            .get(&device_id)
            .is_some_and(|state| state.ble_seen_at.is_none())
        {
            self.ble_devices.remove(&device_id);
        }
    }

    fn cleanup_endpoint(&mut self, endpoint_id: iroh::EndpointId) {
        if self
            .endpoints
            .get(&endpoint_id)
            .is_some_and(|state| !state.mdns && state.iroh_seen_at.is_none())
        {
            self.endpoints.remove(&endpoint_id);
        }
    }
}

fn endpoint_state_iroh_available(state: &EndpointPeerState) -> bool {
    state.mdns || state.iroh_seen_at.is_some()
}

fn connection_label(connected: bool) -> &'static str {
    if connected {
        "connected"
    } else {
        "disconnected"
    }
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