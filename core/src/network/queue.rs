use std::collections::VecDeque;

use crate::constants::{BLE_HISTORY_WINDOW_TXS, SYNC_TX_BATCH};
use crate::sync::{MessageKind, encode_frontier_announcement, split_frontier_entries};

use super::local_ble_payload_limit;

use super::TransportKind;

const LOG_TARGET: &str = "dole::network";
const BATCH_HEADER_BYTES: usize = 3;
const BATCH_ENTRY_HEADER_BYTES: usize = 2;
const MAX_BATCH_TRANSACTIONS: usize = u16::MAX as usize;
const TRANSPORT_OUTBOUND_MESSAGE_LIMIT: usize = 128;
const TRANSPORT_OUTBOUND_FRAME_LIMIT: usize = 128;

#[derive(Clone, Eq, PartialEq)]
pub(crate) enum OutboundMessage {
    Frontier(Vec<(String, u64)>),
    History(Vec<Vec<u8>>),
    Transactions(Vec<Vec<u8>>),
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum FrameClass {
    Frontier,
    History,
    Tx,
}

#[derive(Clone, Copy)]
pub(crate) struct TransportLimit {
    pub(crate) transport: TransportKind,
    pub(crate) max_payload_bytes: usize,
}

pub(crate) struct TransportQueue {
    messages: VecDeque<OutboundMessage>,
    frames: VecDeque<(FrameClass, Vec<u8>)>,
    last_history: Option<OutboundMessage>,
}

impl TransportQueue {
    pub(crate) fn new() -> Self {
        Self {
            messages: VecDeque::new(),
            frames: VecDeque::new(),
            last_history: None,
        }
    }

    pub(crate) fn enqueue(&mut self, message: OutboundMessage) {
        match frame_class(&message) {
            FrameClass::Frontier => {
                self.discard(FrameClass::Frontier);
            }
            FrameClass::History => {
                if self.messages.contains(&message) {
                    return;
                }
                if self.last_history.as_ref() != Some(&message) {
                    self.discard(FrameClass::History);
                    self.last_history = Some(message.clone());
                }
            }
            FrameClass::Tx => {
                if self.messages.contains(&message) {
                    return;
                }
            }
        }

        while self.messages.len() >= TRANSPORT_OUTBOUND_MESSAGE_LIMIT {
            self.messages.pop_front();
        }
        self.messages.push_back(message);
    }

    fn discard(&mut self, class: FrameClass) {
        self.messages.retain(|queued| frame_class(queued) != class);
        self.frames.retain(|(queued, _)| *queued != class);
    }

    pub(crate) fn pop_frame(&mut self, limit: TransportLimit) -> Option<Vec<u8>> {
        loop {
            if let Some((_, frame)) = self.frames.pop_front() {
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
            let class = frame_class(&message);
            let capacity = TRANSPORT_OUTBOUND_FRAME_LIMIT.saturating_sub(self.frames.len());
            for frame in encode_outbound_frames(&message, limit, capacity) {
                self.frames.push_back((class, frame));
            }
        }
    }

    pub(crate) fn clear(&mut self) {
        self.messages.clear();
        self.frames.clear();
        self.last_history = None;
    }
}

pub(crate) fn outbound_message_kind(message: &OutboundMessage) -> MessageKind {
    match message {
        OutboundMessage::Frontier(_) => MessageKind::Frontier,
        OutboundMessage::History(_) | OutboundMessage::Transactions(_) => MessageKind::TxBatch,
    }
}

fn frame_class(message: &OutboundMessage) -> FrameClass {
    match message {
        OutboundMessage::Frontier(_) => FrameClass::Frontier,
        OutboundMessage::History(_) => FrameClass::History,
        OutboundMessage::Transactions(_) => FrameClass::Tx,
    }
}

fn encode_outbound_frames(
    message: &OutboundMessage,
    limit: TransportLimit,
    max_frames: usize,
) -> Vec<Vec<u8>> {
    if max_frames == 0 {
        return Vec::new();
    }
    let mut frames = match message {
        OutboundMessage::Frontier(entries) => encode_frontier_frames(entries, limit),
        OutboundMessage::History(transactions) => encode_transaction_batches(
            history_transactions_for_transport(transactions, limit.transport),
            limit,
            max_frames,
        ),
        OutboundMessage::Transactions(transactions) => {
            encode_transaction_batches(transactions, limit, max_frames)
        }
    };
    frames.truncate(max_frames);
    frames
}

fn history_transactions_for_transport(
    transactions: &[Vec<u8>],
    transport: TransportKind,
) -> &[Vec<u8>] {
    if transport != TransportKind::Ble {
        return transactions;
    }

    &transactions[..transactions.len().min(usize::from(BLE_HISTORY_WINDOW_TXS))]
}

fn encode_frontier_frames(entries: &[(String, u64)], limit: TransportLimit) -> Vec<Vec<u8>> {
    let ble_limit = local_ble_payload_limit();
    let payload = encode_frontier_announcement(entries.to_vec(), ble_limit);
    if payload.len() <= limit.max_payload_bytes {
        return vec![payload];
    }

    let chunks = split_frontier_entries(entries, limit.max_payload_bytes);
    if chunks.is_empty() {
        log::warn!(
            target: LOG_TARGET,
            "TX protocol skipped transport={} reason=frontier-exceeds-payload branches={} bytes={} maxBytes={}",
            limit.transport.as_str(),
            entries.len(),
            payload.len(),
            limit.max_payload_bytes
        );
        return Vec::new();
    }

    chunks
        .into_iter()
        .map(|chunk| encode_frontier_announcement(chunk, ble_limit))
        .collect()
}

fn encode_transaction_batches(
    transactions: &[Vec<u8>],
    limit: TransportLimit,
    max_batches: usize,
) -> Vec<Vec<u8>> {
    if limit.max_payload_bytes <= BATCH_HEADER_BYTES + BATCH_ENTRY_HEADER_BYTES {
        return Vec::new();
    }

    let mut batches = Vec::new();
    let mut current = Vec::new();
    current.push(SYNC_TX_BATCH);
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
            if batches.len() >= max_batches {
                return batches;
            }
            current = Vec::new();
            current.push(SYNC_TX_BATCH);
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

#[cfg(test)]
mod tests {
    use super::*;

    fn transactions(count: usize) -> Vec<Vec<u8>> {
        (0..count).map(|value| vec![value as u8; 8]).collect()
    }

    fn transaction_count(frames: &[Vec<u8>]) -> usize {
        frames
            .iter()
            .map(|frame| usize::from(u16::from_be_bytes([frame[1], frame[2]])))
            .sum()
    }

    #[test]
    fn iroh_history_is_not_limited_by_ble_window() {
        let history = OutboundMessage::History(transactions(40));
        let frames = encode_outbound_frames(
            &history,
            TransportLimit {
                transport: TransportKind::Iroh,
                max_payload_bytes: 3584,
            },
            TRANSPORT_OUTBOUND_FRAME_LIMIT,
        );

        assert_eq!(transaction_count(&frames), 40);
    }

    #[test]
    fn ble_history_uses_bounded_window() {
        let history = OutboundMessage::History(transactions(40));
        let frames = encode_outbound_frames(
            &history,
            TransportLimit {
                transport: TransportKind::Ble,
                max_payload_bytes: 160,
            },
            TRANSPORT_OUTBOUND_FRAME_LIMIT,
        );

        assert_eq!(
            transaction_count(&frames),
            usize::from(BLE_HISTORY_WINDOW_TXS)
        );
    }
}
