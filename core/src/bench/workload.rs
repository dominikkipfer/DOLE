#[cfg(feature = "bench-workload")]
use p256::ecdsa::signature::Signer;
#[cfg(feature = "bench-workload")]
use p256::ecdsa::{Signature as P256Signature, SigningKey, VerifyingKey};

use crate::constants::{OP_BURN, OP_GENESIS, OP_MINT, OP_SEND};
#[cfg(feature = "bench-workload")]
use crate::crypto::get_person_id_as_hex;
#[cfg(feature = "bench-workload")]
use crate::sync::encode_tx_msg;

#[cfg(feature = "bench-workload")]
pub const ROOT_CA_PRIVATE_KEY_HEX: &str =
    "31C4CD6E29F3B703DAC57D84C2D4FFC63BBC116917E101F41E44DC612787280D";

pub const WORKLOAD_GENESIS_COUNT: usize = 5;
pub const WORKLOAD_MINT_COUNT: usize = 10;
pub const WORKLOAD_BURN_COUNT: usize = 10;
pub const WORKLOAD_SEND_COUNT: usize = 975;
pub const WORKLOAD_TOTAL: usize =
    WORKLOAD_GENESIS_COUNT + WORKLOAD_MINT_COUNT + WORKLOAD_BURN_COUNT + WORKLOAD_SEND_COUNT;

pub const WORKLOAD_START_TIMESTAMP_SECS: u64 = 1_782_864_000;
pub const WORKLOAD_TIMESTAMP_STEP_SECS: u64 = 86_400;

#[cfg(feature = "bench-workload")]
const GOC_MIN_STEP: u64 = 1;
#[cfg(feature = "bench-workload")]
const GOC_MAX_STEP: u64 = 100_000;
#[cfg(feature = "bench-workload")]
const GOC_JUMP_MAX: u64 = u64::MAX / 512;
#[cfg(feature = "bench-workload")]
const GOC_JUMP_EVERY: u64 = 7;
#[cfg(feature = "bench-workload")]
const GOC_CEILING: u64 = i64::MAX as u64;

#[derive(Clone, Copy, Eq, PartialEq, Debug)]
pub enum BenchTxKind {
    Genesis,
    Mint,
    Burn,
    Send,
}

impl BenchTxKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::Genesis => "G",
            Self::Mint => "M",
            Self::Burn => "B",
            Self::Send => "S",
        }
    }

    pub fn op(self) -> u8 {
        match self {
            Self::Genesis => OP_GENESIS,
            Self::Mint => OP_MINT,
            Self::Burn => OP_BURN,
            Self::Send => OP_SEND,
        }
    }
}

#[cfg(feature = "bench-workload")]
#[derive(Clone, Debug)]
pub struct BenchTx {
    pub kind: BenchTxKind,
    pub author_id: String,
    pub author_pubkey: String,
    pub target: String,
    pub payload: String,
    pub seq: u64,
    pub ts: u64,
    pub sig: String,
    pub recovery_id: u8,
    pub wire: Vec<u8>,
}

#[cfg(feature = "bench-workload")]
impl BenchTx {
    pub fn wire_len(&self) -> usize {
        self.wire.len()
    }
}

#[cfg(feature = "bench-workload")]
struct BenchAccount {
    signing_key: SigningKey,
    pubkey_hex: String,
    id_hex: String,
    cert_hex: String,
    seq: u64,
    goc: u64,
    goc_ticks: u64,
}

#[cfg(feature = "bench-workload")]
impl BenchAccount {
    fn advance_goc(&mut self) -> u64 {
        self.goc_ticks += 1;
        let step = if self.goc_ticks.is_multiple_of(GOC_JUMP_EVERY) {
            random_in_range(GOC_MAX_STEP, GOC_JUMP_MAX)
        } else {
            random_in_range(GOC_MIN_STEP, GOC_MAX_STEP)
        };
        self.goc = self.goc.saturating_add(step).min(GOC_CEILING);
        self.goc
    }
}

#[cfg(feature = "bench-workload")]
pub struct WorkloadBuilder {
    root: SigningKey,
    accounts: Vec<BenchAccount>,
    ts: u64,
}

#[cfg(feature = "bench-workload")]
impl WorkloadBuilder {
    pub fn new() -> Self {
        let root_bytes = hex::decode(ROOT_CA_PRIVATE_KEY_HEX).expect("root CA key is valid hex");
        let root =
            SigningKey::from_slice(&root_bytes).expect("root CA key is a valid P-256 scalar");
        Self {
            root,
            accounts: Vec::new(),
            ts: WORKLOAD_START_TIMESTAMP_SECS,
        }
    }

    fn next_timestamp(&mut self) -> u64 {
        let ts = self.ts;
        self.ts = self.ts.saturating_add(WORKLOAD_TIMESTAMP_STEP_SECS);
        ts
    }

    fn new_account(&mut self) -> BenchAccount {
        let signing_key = random_signing_key();
        let pubkey = VerifyingKey::from(&signing_key).to_sec1_point(false);
        let pubkey_bytes = pubkey.as_bytes().to_vec();
        let pubkey_hex = hex::encode_upper(&pubkey_bytes);
        let id_hex = get_person_id_as_hex(pubkey_bytes.clone());
        let cert: P256Signature = self.root.sign(&pubkey_bytes);
        BenchAccount {
            signing_key,
            pubkey_hex,
            id_hex,
            cert_hex: hex::encode_upper(cert.to_bytes()),
            seq: 0,
            goc: 0,
            goc_ticks: 0,
        }
    }

    pub fn build(mut self) -> Vec<BenchTx> {
        let mut txs = Vec::with_capacity(WORKLOAD_TOTAL);

        for _ in 0..WORKLOAD_GENESIS_COUNT {
            let account = self.new_account();
            self.accounts.push(account);
            let index = self.accounts.len() - 1;
            txs.push(self.emit_genesis(index));
        }

        for i in 0..WORKLOAD_MINT_COUNT {
            txs.push(self.emit_amount(i % WORKLOAD_GENESIS_COUNT, BenchTxKind::Mint));
        }
        for i in 0..WORKLOAD_BURN_COUNT {
            txs.push(self.emit_amount(i % WORKLOAD_GENESIS_COUNT, BenchTxKind::Burn));
        }
        for i in 0..WORKLOAD_SEND_COUNT {
            let author = i % WORKLOAD_GENESIS_COUNT;
            let target = (author + 1 + (i % (WORKLOAD_GENESIS_COUNT - 1))) % WORKLOAD_GENESIS_COUNT;
            txs.push(self.emit_send(author, target));
        }

        txs
    }

    fn emit_genesis(&mut self, index: usize) -> BenchTx {
        let ts = self.next_timestamp();
        let account = &mut self.accounts[index];
        let sig: P256Signature = account.signing_key.sign(&[OP_GENESIS]);
        let sig_hex = hex::encode_upper(sig.to_bytes());
        let recovery_id = crate::crypto::find_genesis_recovery_id(&sig_hex, &account.cert_hex)
            .map(|(_, id)| id)
            .expect("generated genesis signature is recoverable");

        let author_id = account.id_hex.clone();
        let author_pubkey = account.pubkey_hex.clone();
        let cert_hex = account.cert_hex.clone();

        let wire = encode_tx_msg("G", &cert_hex, &author_pubkey, 0, ts, &sig_hex, recovery_id)
            .expect("genesis transaction encodes");

        BenchTx {
            kind: BenchTxKind::Genesis,
            author_id,
            author_pubkey,
            target: cert_hex,
            payload: String::new(),
            seq: 0,
            ts,
            sig: sig_hex,
            recovery_id,
            wire,
        }
    }

    fn emit_amount(&mut self, index: usize, kind: BenchTxKind) -> BenchTx {
        let ts = self.next_timestamp();
        let account = &mut self.accounts[index];
        account.seq += 1;
        let seq = account.seq;
        let goc = account.advance_goc();

        let mut payload = Vec::new();
        payload.push(kind.op());
        payload.extend_from_slice(&seq.to_be_bytes());
        payload.extend_from_slice(&goc.to_be_bytes());

        let sig: P256Signature = account.signing_key.sign(&payload);
        let sig_hex = hex::encode_upper(sig.to_bytes());
        let author_pubkey = account.pubkey_hex.clone();
        let author_id = account.id_hex.clone();
        let goc_str = goc.to_string();

        let recovery_id = crate::crypto::find_tx_recovery_id_for_pubkey_hex(
            &author_pubkey,
            kind.label(),
            "",
            &goc_str,
            seq,
            &sig_hex,
        )
        .expect("generated signature is recoverable");

        let wire = encode_tx_msg(kind.label(), "", &goc_str, seq, ts, &sig_hex, recovery_id)
            .expect("amount transaction encodes");

        BenchTx {
            kind,
            author_id,
            author_pubkey,
            target: String::new(),
            payload: goc_str,
            seq,
            ts,
            sig: sig_hex,
            recovery_id,
            wire,
        }
    }

    fn emit_send(&mut self, author: usize, target: usize) -> BenchTx {
        let ts = self.next_timestamp();
        let target_id = self.accounts[target].id_hex.clone();

        let account = &mut self.accounts[author];
        account.seq += 1;
        let seq = account.seq;
        let goc = account.advance_goc();

        let mut payload = Vec::new();
        payload.push(OP_SEND);
        payload.extend_from_slice(&seq.to_be_bytes());
        payload.extend_from_slice(&hex::decode(&target_id).expect("target id is hex"));
        payload.extend_from_slice(&goc.to_be_bytes());

        let sig: P256Signature = account.signing_key.sign(&payload);
        let sig_hex = hex::encode_upper(sig.to_bytes());
        let author_pubkey = account.pubkey_hex.clone();
        let author_id = account.id_hex.clone();
        let goc_str = goc.to_string();

        let recovery_id = crate::crypto::find_tx_recovery_id_for_pubkey_hex(
            &author_pubkey,
            "S",
            &target_id,
            &goc_str,
            seq,
            &sig_hex,
        )
        .expect("generated signature is recoverable");

        let wire = encode_tx_msg("S", &target_id, &goc_str, seq, ts, &sig_hex, recovery_id)
            .expect("send transaction encodes");

        BenchTx {
            kind: BenchTxKind::Send,
            author_id,
            author_pubkey,
            target: target_id,
            payload: goc_str,
            seq,
            ts,
            sig: sig_hex,
            recovery_id,
            wire,
        }
    }
}

#[cfg(feature = "bench-workload")]
impl Default for WorkloadBuilder {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(feature = "bench-workload")]
pub fn generate_workload() -> Vec<BenchTx> {
    WorkloadBuilder::new().build()
}

#[cfg(feature = "bench-workload")]
fn random_in_range(min: u64, max: u64) -> u64 {
    debug_assert!(min < max);
    let mut raw = [0u8; 8];
    getrandom::fill(&mut raw).expect("system randomness is available");
    let span = max - min + 1;
    min + u64::from_le_bytes(raw) % span
}

#[cfg(feature = "bench-workload")]
fn random_signing_key() -> SigningKey {
    loop {
        let mut seed = [0u8; 32];
        getrandom::fill(&mut seed).expect("system randomness is available");
        if let Ok(key) = SigningKey::from_slice(&seed) {
            return key;
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Summary {
    pub count: usize,
    pub min: u64,
    pub max: u64,
    pub median: u64,
    pub total: u64,
}

impl Summary {
    pub fn mean(&self) -> f64 {
        if self.count == 0 {
            return 0.0;
        }
        self.total as f64 / self.count as f64
    }
}

pub fn size_stats(sizes: &[usize]) -> Option<Summary> {
    if sizes.is_empty() {
        return None;
    }

    let mut sorted = sizes.iter().map(|size| *size as u64).collect::<Vec<_>>();
    sorted.sort_unstable();

    let mid = sorted.len() / 2;
    let median = if sorted.len().is_multiple_of(2) {
        (sorted[mid - 1] + sorted[mid]) / 2
    } else {
        sorted[mid]
    };

    Some(Summary {
        count: sorted.len(),
        min: sorted[0],
        max: sorted[sorted.len() - 1],
        median,
        total: sorted.iter().sum(),
    })
}

pub fn directory_size_bytes(path: &std::path::Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(path) else {
        return 0;
    };

    let mut total = 0;
    for entry in entries.flatten() {
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if metadata.is_dir() {
            total += directory_size_bytes(&entry.path());
        } else {
            total += metadata.len();
        }
    }
    total
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct StoreSample {
    pub applied: usize,
    pub bytes: u64,
    pub delta_bytes: u64,
}

#[cfg_attr(not(feature = "bench-workload"), allow(dead_code))]
#[derive(Default)]
pub struct StoreGrowth {
    samples: Vec<StoreSample>,
}

#[cfg_attr(not(feature = "bench-workload"), allow(dead_code))]
impl StoreGrowth {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn sample(&mut self, applied: usize, path: &std::path::Path) -> StoreSample {
        let bytes = directory_size_bytes(path);
        let previous = self.samples.last().map(|sample| sample.bytes).unwrap_or(0);
        let sample = StoreSample {
            applied,
            bytes,
            delta_bytes: bytes.saturating_sub(previous),
        };
        self.samples.push(sample);
        sample
    }
}
