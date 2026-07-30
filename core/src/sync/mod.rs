mod frames;
mod tx;

pub(crate) use frames::{
    MessageKind, decode_frontier_announcement, decode_transaction_batch,
    encode_frontier_announcement, frontier_ble_limit, split_frontier_entries,
};
pub(crate) use tx::{
    SyncTx, TxHeader, decode_tx_message, encode_tx_msg, tx_label_from_type, type_from_tx_label,
};

use crate::constants::{ID_SIZE, SIGNATURE_SIZE};

const ID_LEN: usize = ID_SIZE as usize;
const SIG_LEN: usize = SIGNATURE_SIZE as usize;

pub(crate) fn is_valid_protocol_payload(payload: &[u8], kind: MessageKind) -> bool {
    match kind {
        MessageKind::Frontier => decode_frontier_announcement(payload).is_some(),
        MessageKind::TxBatch => decode_transaction_batch(payload).is_some(),
        MessageKind::Tx => tx::has_valid_tx_shape(payload),
    }
}

fn id_hex_to_array(value: &str) -> Option<[u8; ID_LEN]> {
    let bytes = crate::crypto::decode_hex(value);
    if bytes.len() != ID_LEN {
        return None;
    }

    let mut out = [0; ID_LEN];
    out.copy_from_slice(&bytes);
    Some(out)
}
