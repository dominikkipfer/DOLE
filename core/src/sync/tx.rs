use modular_bitfield::prelude::*;

use crate::constants::{LONG_SIZE, OP_BURN, OP_GENESIS, OP_MINT, OP_SEND};
use crate::crypto::{
    decode_hex, person_id_hex_from_id_or_pubkey_hex, recover_genesis_pubkey_hex, recover_tx_pubkey_hex_with_recovery_id
};

use super::frames::{SyncReader, group_u64_len_code, push_group_u64};
use super::{ID_LEN, SIG_LEN, id_hex_to_array};

#[bitfield]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TxHeader {
    pub tx_type: B2,
    pub recovery_id: B2,
    pub ts_len: B3,
    #[skip]
    unused: B1
}

#[bitfield]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TxPrefix {
    pub seq_len: B3,
    pub goc_len: B3,
    #[skip]
    unused: B2
}

impl TxHeader {
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

    fn decode(byte: u8) -> Option<Self> {
        let header = Self::from_bytes([byte]);
        let canonical = Self::checked(header.tx_type(), header.recovery_id(), header.ts_len())?;
        (canonical.into_bytes()[0] == byte).then_some(header)
    }
}

impl TxPrefix {
    fn checked(seq_len: u8, goc_len: u8) -> Option<Self> {
        if seq_len >= LONG_SIZE || goc_len >= LONG_SIZE {
            return None;
        }

        let mut prefix = Self::new();
        prefix.set_seq_len(seq_len);
        prefix.set_goc_len(goc_len);
        Some(prefix)
    }

    fn decode(byte: u8) -> Option<Self> {
        let prefix = Self::from_bytes([byte]);
        let canonical = Self::checked(prefix.seq_len(), prefix.goc_len())?;
        (canonical.into_bytes()[0] == byte).then_some(prefix)
    }
}

pub(crate) struct SyncTx {
    pub(crate) author_id: String,
    pub(crate) author_pubkey: String,
    pub(crate) tx_type: u8,
    pub(crate) recovery_id: u8,
    pub(crate) target: String,
    pub(crate) payload: String,
    pub(crate) seq: u64,
    pub(crate) ts: u64,
    pub(crate) sig: String
}

struct RawTx<'a> {
    tx_type: u8,
    recovery_id: u8,
    seq: u64,
    goc: u64,
    ts: u64,
    target: Option<&'a [u8]>,
    cert: Option<&'a [u8]>,
    sig: &'a [u8]
}

fn parse_tx(msg: &[u8]) -> Option<RawTx<'_>> {
    let mut r = SyncReader::new(msg);

    let header = TxHeader::decode(r.read_u8()?)?;
    let tx_type = header.tx_type();
    let recovery_id = header.recovery_id();
    let ts_len = header.ts_len();

    if tx_type == OP_GENESIS {
        let sig = r.read_bytes(SIG_LEN)?;
        let cert = r.read_bytes(SIG_LEN)?;
        let ts = r.read_group_u64(ts_len)?;
        if !r.is_done() {
            return None;
        }
        return Some(RawTx {
            tx_type,
            recovery_id,
            seq: 0,
            goc: 0,
            ts,
            target: None,
            cert: Some(cert),
            sig
        });
    }

    let prefix = TxPrefix::decode(r.read_u8()?)?;
    let seq = r.read_group_u64(prefix.seq_len())?;
    let goc = r.read_group_u64(prefix.goc_len())?;
    let ts = r.read_group_u64(ts_len)?;

    let target = match tx_type {
        OP_MINT | OP_BURN => None,
        OP_SEND => Some(r.read_bytes(ID_LEN)?),
        _ => return None
    };

    let sig = r.read_bytes(SIG_LEN)?;
    if !r.is_done() {
        return None;
    }
    Some(RawTx {
        tx_type,
        recovery_id,
        seq,
        goc,
        ts,
        target,
        cert: None,
        sig
    })
}

pub(super) fn has_valid_tx_shape(msg: &[u8]) -> bool {
    parse_tx(msg).is_some()
}

pub(crate) fn decode_tx_message(msg: &[u8]) -> Option<SyncTx> {
    let raw = parse_tx(msg)?;
    let sig = hex::encode_upper(raw.sig);

    if raw.tx_type == OP_GENESIS {
        let cert = hex::encode_upper(raw.cert?);
        let public_key = recover_genesis_pubkey_hex(&sig, &cert, raw.recovery_id)?;
        let author_id = person_id_hex_from_id_or_pubkey_hex(&public_key)?;
        return Some(SyncTx {
            author_id,
            author_pubkey: public_key.clone(),
            tx_type: raw.tx_type,
            recovery_id: raw.recovery_id,
            target: cert,
            payload: public_key,
            seq: raw.seq,
            ts: raw.ts,
            sig
        });
    }

    let tx_t = tx_label_from_type(raw.tx_type)?;
    let target = raw.target.map(hex::encode_upper).unwrap_or_default();
    let payload = raw.goc.to_string();
    let author_pubkey = recover_tx_pubkey_hex_with_recovery_id(
        tx_t,
        &target,
        &payload,
        raw.seq,
        &sig,
        raw.recovery_id
    )?;
    let author_id = person_id_hex_from_id_or_pubkey_hex(&author_pubkey)?;

    Some(SyncTx {
        author_id,
        author_pubkey,
        tx_type: raw.tx_type,
        recovery_id: raw.recovery_id,
        target,
        payload,
        seq: raw.seq,
        ts: raw.ts,
        sig
    })
}

pub(crate) fn encode_tx_msg(
    tx_t: &str,
    target: &str,
    payload: &str,
    seq: u64,
    ts: u64,
    sig: &str,
    recovery_id: u8
) -> Option<Vec<u8>> {
    let sig = decode_hex(sig);
    if sig.len() != SIG_LEN {
        return None;
    }

    let tx_type = type_from_tx_label(tx_t)?;
    let mut msg = Vec::new();
    let ts_len = group_u64_len_code(ts);
    msg.push(TxHeader::checked(tx_type, recovery_id, ts_len)?.into_bytes()[0]);

    if tx_type == OP_GENESIS {
        let cert = decode_hex(target);
        if cert.len() != SIG_LEN {
            return None;
        }
        msg.extend_from_slice(&sig);
        msg.extend_from_slice(&cert);
        push_group_u64(&mut msg, ts, ts_len);
        return Some(msg);
    }

    let goc = payload.parse::<u64>().ok()?;
    let seq_len = group_u64_len_code(seq);
    let goc_len = group_u64_len_code(goc);
    msg.push(TxPrefix::checked(seq_len, goc_len)?.into_bytes()[0]);
    push_group_u64(&mut msg, seq, seq_len);
    push_group_u64(&mut msg, goc, goc_len);
    push_group_u64(&mut msg, ts, ts_len);

    match tx_type {
        OP_MINT | OP_BURN => {}
        OP_SEND => {
            let target_id = person_id_hex_from_id_or_pubkey_hex(target)?;
            msg.extend_from_slice(&id_hex_to_array(&target_id)?);
        }
        _ => return None
    }

    msg.extend_from_slice(&sig);
    Some(msg)
}

pub(crate) fn type_from_tx_label(tx_t: &str) -> Option<u8> {
    match tx_t {
        "G" => Some(OP_GENESIS),
        "M" => Some(OP_MINT),
        "B" => Some(OP_BURN),
        "S" => Some(OP_SEND),
        _ => None
    }
}

pub(crate) fn tx_label_from_type(tx_type: u8) -> Option<&'static str> {
    match tx_type {
        OP_GENESIS => Some("G"),
        OP_MINT => Some("M"),
        OP_BURN => Some("B"),
        OP_SEND => Some("S"),
        _ => None
    }
}
