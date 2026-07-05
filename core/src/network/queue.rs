use std::collections::VecDeque;

use crate::constants::SYNC_TX_BATCH;
use crate::sync::MessageKind;

use super::TransportKind;

const LOG_TARGET: &str = "dole::network";
const BATCH_HEADER_BYTES: usize = 3;
const BATCH_ENTRY_HEADER_BYTES: usize = 2;
const MAX_BATCH_TRANSACTIONS: usize = u16::MAX as usize;
const TRANSPORT_OUTBOUND_MESSAGE_LIMIT: usize = 128;
const TRANSPORT_OUTBOUND_FRAME_LIMIT: usize = 128;

#[derive(Clone, Eq, PartialEq)]
pub(crate) enum OutboundMessage {
    Raw(Vec<u8>),
    Transactions(Vec<Vec<u8>>)
}

#[derive(Clone, Copy)]
pub(crate) struct TransportLimit {
    pub(crate) transport: TransportKind,
    pub(crate) max_payload_bytes: usize
}

pub(crate) struct TransportQueue {
    messages: VecDeque<OutboundMessage>,
    frames: VecDeque<Vec<u8>>
}

impl TransportQueue {
    pub(crate) fn new() -> Self {
        Self {
            messages: VecDeque::new(),
            frames: VecDeque::new()
        }
    }

    pub(crate) fn enqueue(&mut self, message: OutboundMessage) {
        let kind = outbound_message_kind(&message);
        if kind == MessageKind::Frontier {
            self.messages.retain(|queued| outbound_message_kind(queued) != MessageKind::Frontier);
            self.frames.retain(|frame| MessageKind::from_payload(frame) != MessageKind::Frontier);
        } else if self.messages.contains(&message) {
            return;
        }

        while self.messages.len() >= TRANSPORT_OUTBOUND_MESSAGE_LIMIT {
            self.messages.pop_front();
        }
        self.messages.push_back(message);
    }

    pub(crate) fn pop_frame(&mut self, limit: TransportLimit) -> Option<Vec<u8>> {
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

    pub(crate) fn clear(&mut self) {
        self.messages.clear();
        self.frames.clear();
    }
}

pub(crate) fn outbound_message_kind(message: &OutboundMessage) -> MessageKind {
    match message {
        OutboundMessage::Raw(payload) => MessageKind::from_payload(payload),
        OutboundMessage::Transactions(_) => MessageKind::TxBatch
    }
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

        if count > 0 && (count == MAX_BATCH_TRANSACTIONS || current.len() + entry_bytes > limit.max_payload_bytes)
        {
            set_batch_count(&mut current, count);
            batches.push(current);
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
