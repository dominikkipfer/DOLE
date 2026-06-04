use crate::constants::{ID_SIZE, OP_BURN, OP_GENESIS, OP_MINT, OP_SEND, ROOT_CA_BYTES};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature as P256Signature, VerifyingKey};
use sha2::{Digest, Sha256};

const RAW_SIGNATURE_SIZE: usize = 64;

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
    Some(hex::encode_upper(raw.to_vec()))
}

pub(crate) fn signature_hex_to_der_hex(sig_hex: &str) -> Option<String> {
    let bytes = hex_to_bytes(sig_hex.to_string());
    let signature = p256_signature_from_der_or_raw(&bytes)?;
    let der = signature.to_der();
    Some(hex::encode_upper(der.as_bytes()))
}

#[uniffi::export]
pub fn verify_card_certificate(pub_key: Vec<u8>, cert: Vec<u8>) -> bool {

    let root_key = match VerifyingKey::from_sec1_bytes(&ROOT_CA_BYTES) {
        Ok(key) => key,
        Err(_) => {
            println!("ROOT_CA_BYTES invalid");
            return false;
        }
    };

    let signature = match p256_signature_from_der_or_raw(&cert) {
        Some(sig) => sig,
        None => {
            println!("Invalid certificate signature format");
            return false;
        }
    };

    let result = root_key.verify(&pub_key, &signature).is_ok();
    result
}

pub fn verify_tx_signature(
    author_pubkey_hex: &str,
    tx_type: &str,
    target_pubkey_hex: &str,
    goc_str: &str,
    seq: i64,
    sig_hex: &str,
) -> bool {
    let pubkey_bytes = hex_to_bytes(author_pubkey_hex.to_string());
    if pubkey_bytes.is_empty() {
        return false;
    }

    let sig_bytes = hex_to_bytes(sig_hex.to_string());
    if sig_bytes.is_empty() {
        return false;
    }

    let verifying_key = match VerifyingKey::from_sec1_bytes(&pubkey_bytes) {
        Ok(k) => k,
        Err(_) => return false,
    };

    let p256_sig = match P256Signature::from_slice(&sig_bytes) {
        Ok(s) => s,
        Err(_) => return false,
    };

    let mut log_payload = Vec::new();

    let op_byte = match tx_type {
        "G" => OP_GENESIS,
        "M" => OP_MINT,
        "B" => OP_BURN,
        "S" => OP_SEND,
        _ => return false,
    };
    log_payload.push(op_byte);

    let mut hasher = Sha256::new();
    hasher.update(&pubkey_bytes);
    log_payload.extend_from_slice(&hasher.finalize());
    log_payload.extend_from_slice(&seq.to_be_bytes());

    match tx_type {
        "G" => {}
        "M" | "B" => {
            let goc_val = goc_str.parse::<i64>().unwrap_or(0);
            log_payload.extend_from_slice(&goc_val.to_be_bytes());
        }
        "S" => {
            let Some(target_id_bytes) = person_id_bytes_from_id_or_pubkey_hex(target_pubkey_hex)
            else {
                return false;
            };
            log_payload.extend_from_slice(&target_id_bytes);

            let goc_val = goc_str.parse::<i64>().unwrap_or(0);
            log_payload.extend_from_slice(&goc_val.to_be_bytes());
        }
        _ => {}
    }

    verifying_key.verify(&log_payload, &p256_sig).is_ok()
}
