use std::time::{SystemTime, UNIX_EPOCH};

use crate::crypto::{
    person_id_hex_from_id_or_pubkey_hex, signature_hex_to_der_hex, verify_card_certificate,
    verify_tx_signature,
};

#[derive(Clone, uniffi::Record)]
pub struct LedgerTransaction {
    pub id: String,
    pub tx_type: String,
    pub author: String,
    pub public_key: String,
    pub certificate: String,
    pub target_id: String,
    pub goc: i64,
    pub seq: i64,
    pub timestamp: i64,
    pub signature: String,
}

#[uniffi::export]
pub fn prepare_genesis_transaction(
    public_key_id: String,
    public_key: String,
    sig_hex: String,
    cert_hex: String,
    timestamp: Option<i64>,
) -> Option<LedgerTransaction> {
    let public_key_bytes = hex::decode(&public_key).ok()?;
    let public_key = hex::encode_upper(&public_key_bytes);
    let author = person_id_hex_from_id_or_pubkey_hex(&public_key)?;
    if !author.eq_ignore_ascii_case(&public_key_id)
        || !verify_card_certificate(public_key_bytes, hex::decode(&cert_hex).ok()?)
        || !verify_tx_signature(&public_key, "G", "", "0", 0, &sig_hex)
    {
        return None;
    }
    let timestamp = checked_timestamp(timestamp)?;
    Some(LedgerTransaction {
        id: format!("{author}:0"),
        tx_type: "G".into(),
        author,
        public_key,
        certificate: signature_hex_to_der_hex(&cert_hex)?,
        target_id: String::new(),
        goc: 0,
        seq: 0,
        timestamp,
        signature: signature_hex_to_der_hex(&sig_hex)?,
    })
}

#[uniffi::export]
pub fn prepare_ledger_transaction(
    public_key: String,
    tx_type: String,
    target_id: String,
    goc: i64,
    seq: i64,
    sig_hex: String,
    timestamp: Option<i64>,
) -> Option<LedgerTransaction> {
    if !matches!(tx_type.as_str(), "M" | "B" | "S") || goc <= 0 || seq < 0 {
        return None;
    }
    let public_key_bytes = hex::decode(&public_key).ok()?;
    let public_key = hex::encode_upper(&public_key_bytes);
    let author = person_id_hex_from_id_or_pubkey_hex(&public_key)?;
    let target_id = match tx_type.as_str() {
        "M" | "B" if target_id.is_empty() => String::new(),
        "S" => person_id_hex_from_id_or_pubkey_hex(&target_id)?,
        _ => return None,
    };
    if !verify_tx_signature(
        &public_key,
        &tx_type,
        &target_id,
        &goc.to_string(),
        u64::try_from(seq).ok()?,
        &sig_hex,
    ) {
        return None;
    }
    let timestamp = checked_timestamp(timestamp)?;
    Some(LedgerTransaction {
        id: format!("{author}:{seq}"),
        tx_type,
        author,
        public_key: String::new(),
        certificate: String::new(),
        target_id,
        goc,
        seq,
        timestamp,
        signature: signature_hex_to_der_hex(&sig_hex)?,
    })
}

fn checked_timestamp(timestamp: Option<i64>) -> Option<i64> {
    let value = timestamp.unwrap_or_else(current_timestamp);
    (value >= 0).then_some(value)
}

fn current_timestamp() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_secs()).ok())
        .unwrap_or_default()
}
