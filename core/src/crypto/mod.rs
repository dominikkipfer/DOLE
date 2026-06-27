use crate::constants::{
    ID_SIZE, OP_BURN, OP_GENESIS, OP_MINT, OP_SEND, ROOT_CA_BYTES, SIGNATURE_SIZE,
};
use crate::logging;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{RecoveryId, Signature as P256Signature, VerifyingKey};
use sha2::{Digest, Sha256};

const LOG_TARGET: &str = "dole::crypto";
pub(crate) const RAW_SIGNATURE_SIZE: usize = SIGNATURE_SIZE as usize;

fn person_id_bytes_from_id_or_pubkey_hex(value_hex: &str) -> Option<Vec<u8>> {
    let bytes = hex_to_bytes(value_hex.to_string());
    if bytes.is_empty() {
        return None;
    }

    if bytes.len() == usize::from(ID_SIZE) {
        return Some(bytes);
    }

    let mut hasher = Sha256::new();
    hasher.update(&bytes);
    Some(hasher.finalize().to_vec())
}

pub(crate) fn person_id_hex_from_id_or_pubkey_hex(value_hex: &str) -> Option<String> {
    person_id_bytes_from_id_or_pubkey_hex(value_hex).map(bytes_to_hex)
}

#[uniffi::export]
pub fn bytes_to_hex(bytes: Vec<u8>) -> String {
    hex::encode_upper(bytes)
}

#[uniffi::export]
pub fn hex_to_bytes(s: String) -> Vec<u8> {
    hex::decode(s).unwrap_or_default()
}

#[uniffi::export]
pub fn get_person_id_as_hex(pub_key: Vec<u8>) -> String {
    let mut hasher = Sha256::new();
    hasher.update(&pub_key);
    bytes_to_hex(hasher.finalize().to_vec())
}

fn p256_signature_from_der_or_raw(bytes: &[u8]) -> Option<P256Signature> {
    if bytes.len() == RAW_SIGNATURE_SIZE {
        P256Signature::from_slice(bytes).ok()
    } else {
        P256Signature::from_der(bytes).ok()
    }
}

pub(crate) fn signature_hex_to_raw_hex(sig_hex: &str) -> Option<String> {
    let bytes = hex_to_bytes(sig_hex.to_string());
    let signature = p256_signature_from_der_or_raw(&bytes)?;
    let raw = signature.to_bytes();
    Some(hex::encode_upper(raw))
}

pub(crate) fn signature_hex_to_der_hex(sig_hex: &str) -> Option<String> {
    let bytes = hex_to_bytes(sig_hex.to_string());
    let signature = p256_signature_from_der_or_raw(&bytes)?;
    let der = signature.to_der();
    Some(hex::encode_upper(der.as_bytes()))
}

fn tx_type_byte(op: u8) -> u8 {
    op
}

fn op_from_tx_type(tx_type: &str) -> Option<u8> {
    match tx_type {
        "G" => Some(OP_GENESIS),
        "M" => Some(OP_MINT),
        "B" => Some(OP_BURN),
        "S" => Some(OP_SEND),
        _ => None,
    }
}

pub(crate) fn genesis_signing_payload() -> [u8; 1] {
    [tx_type_byte(OP_GENESIS)]
}

pub(crate) fn recover_genesis_pubkey_hex(
    sig_hex: &str,
    cert_hex: &str,
    recovery_id: u8,
) -> Option<String> {
    let sig_bytes = hex_to_bytes(sig_hex.to_string());
    let cert_bytes = hex_to_bytes(cert_hex.to_string());
    if sig_bytes.len() != RAW_SIGNATURE_SIZE || cert_bytes.len() != RAW_SIGNATURE_SIZE {
        return None;
    }

    let signature = P256Signature::from_slice(&sig_bytes).ok()?;
    let payload = genesis_signing_payload();
    let recovery_id = RecoveryId::try_from(recovery_id).ok()?;

    let Ok(candidate) = VerifyingKey::recover_from_msg(&payload, &signature, recovery_id) else {
        return None;
    };
    let encoded = candidate.to_sec1_point(false);
    let pubkey = encoded.as_bytes();
    if verify_card_certificate_internal(pubkey, &cert_bytes) {
        return Some(hex::encode_upper(pubkey));
    }

    None
}

pub(crate) fn find_genesis_recovery_id(sig_hex: &str, cert_hex: &str) -> Option<(String, u8)> {
    for recovery_id in 0..4u8 {
        if let Some(pubkey) = recover_genesis_pubkey_hex(sig_hex, cert_hex, recovery_id) {
            return Some((pubkey, recovery_id));
        }
    }

    None
}

pub(crate) fn verify_genesis_tx(pubkey_hex: &str, sig_hex: &str, cert_hex: &str) -> bool {
    let pubkey_bytes = hex_to_bytes(pubkey_hex.to_string());
    let sig_bytes = hex_to_bytes(sig_hex.to_string());
    let cert_bytes = hex_to_bytes(cert_hex.to_string());
    if pubkey_bytes.is_empty() || sig_bytes.len() != RAW_SIGNATURE_SIZE {
        return false;
    }
    if !verify_card_certificate_internal(&pubkey_bytes, &cert_bytes) {
        return false;
    }

    let verifying_key = match VerifyingKey::from_sec1_bytes(&pubkey_bytes) {
        Ok(key) => key,
        Err(_) => return false,
    };
    let signature = match P256Signature::from_slice(&sig_bytes) {
        Ok(signature) => signature,
        Err(_) => return false,
    };

    verifying_key
        .verify(&genesis_signing_payload(), &signature)
        .is_ok()
}

#[uniffi::export]
pub fn verify_card_certificate(pub_key: Vec<u8>, cert: Vec<u8>) -> bool {
    logging::init_logging();
    verify_card_certificate_internal(&pub_key, &cert)
}

fn verify_card_certificate_internal(pub_key: &[u8], cert: &[u8]) -> bool {
    let root_key = match VerifyingKey::from_sec1_bytes(&ROOT_CA_BYTES) {
        Ok(key) => key,
        Err(_) => {
            log::error!(target: LOG_TARGET, "ROOT_CA_BYTES is invalid");
            return false;
        }
    };

    let signature = match p256_signature_from_der_or_raw(cert) {
        Some(sig) => sig,
        None => {
            log::warn!(target: LOG_TARGET, "Invalid certificate signature format");
            return false;
        }
    };

    root_key.verify(pub_key, &signature).is_ok()
}

fn tx_signing_payload(
    tx_type: &str,
    target_pubkey_hex: &str,
    goc_str: &str,
    seq: u64,
) -> Option<Vec<u8>> {
    let op = op_from_tx_type(tx_type)?;
    let mut log_payload = Vec::new();
    log_payload.push(tx_type_byte(op));

    match tx_type {
        "G" => {}
        "M" | "B" => {
            log_payload.extend_from_slice(&seq.to_be_bytes());
            let goc_val = goc_str.parse::<u64>().ok()?;
            log_payload.extend_from_slice(&goc_val.to_be_bytes());
        }
        "S" => {
            log_payload.extend_from_slice(&seq.to_be_bytes());
            let target_id_bytes = person_id_bytes_from_id_or_pubkey_hex(target_pubkey_hex)?;
            log_payload.extend_from_slice(&target_id_bytes);
            let goc_val = goc_str.parse::<u64>().ok()?;
            log_payload.extend_from_slice(&goc_val.to_be_bytes());
        }
        _ => return None,
    }

    Some(log_payload)
}

pub(crate) fn recover_tx_pubkey_hex_with_recovery_id(
    tx_type: &str,
    target_pubkey_hex: &str,
    goc_str: &str,
    seq: u64,
    sig_hex: &str,
    recovery_id: u8,
) -> Option<String> {
    let payload = tx_signing_payload(tx_type, target_pubkey_hex, goc_str, seq)?;

    let sig_bytes = hex_to_bytes(sig_hex.to_string());
    let signature = p256_signature_from_der_or_raw(&sig_bytes)?;
    let recovery_id = RecoveryId::try_from(recovery_id).ok()?;

    let candidate = VerifyingKey::recover_from_msg(&payload, &signature, recovery_id).ok()?;
    let encoded = candidate.to_sec1_point(false);
    Some(hex::encode_upper(encoded.as_bytes()))
}

pub(crate) fn find_tx_recovery_id_for_pubkey_hex(
    author_pubkey_hex: &str,
    tx_type: &str,
    target_pubkey_hex: &str,
    goc_str: &str,
    seq: u64,
    sig_hex: &str,
) -> Option<u8> {
    for recovery_id in 0..4u8 {
        let Some(pubkey) = recover_tx_pubkey_hex_with_recovery_id(
            tx_type,
            target_pubkey_hex,
            goc_str,
            seq,
            sig_hex,
            recovery_id,
        ) else {
            continue;
        };
        if pubkey.eq_ignore_ascii_case(author_pubkey_hex) {
            return Some(recovery_id);
        }
    }

    None
}

pub fn verify_tx_signature(
    author_pubkey_hex: &str,
    tx_type: &str,
    target_pubkey_hex: &str,
    goc_str: &str,
    seq: u64,
    sig_hex: &str,
) -> bool {
    let pubkey_bytes = hex_to_bytes(author_pubkey_hex.to_string());
    if pubkey_bytes.is_empty() {
        return false;
    }

    let sig_bytes = hex_to_bytes(sig_hex.to_string());
    let Some(p256_sig) = p256_signature_from_der_or_raw(&sig_bytes) else {
        return false;
    };

    let verifying_key = match VerifyingKey::from_sec1_bytes(&pubkey_bytes) {
        Ok(k) => k,
        Err(_) => return false,
    };

    let Some(log_payload) = tx_signing_payload(tx_type, target_pubkey_hex, goc_str, seq) else {
        return false;
    };

    verifying_key.verify(&log_payload, &p256_sig).is_ok()
}