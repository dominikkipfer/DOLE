#[cfg(feature = "bench-workload")]
use p256::ecdsa::signature::Signer;
#[cfg(feature = "bench-workload")]
use p256::ecdsa::{Signature as P256Signature, SigningKey, VerifyingKey};

use crate::constants::{OP_BURN, OP_GENESIS, OP_MINT, OP_SEND};
#[cfg(feature = "bench-workload")]
use crate::crypto::get_person_id_as_hex;
#[cfg(feature = "bench-workload")]
use crate::transaction::{
    LedgerTransaction, prepare_genesis_transaction, prepare_ledger_transaction,
};

#[cfg(feature = "bench-workload")]
pub const ROOT_CA_PRIVATE_KEY_HEX: &str = "31C4CD6E29F3B703DAC57D84C2D4FFC63BBC116917E101F41E44DC612787280D";

pub const WORKLOAD_GENESIS_COUNT: usize = 5;
pub const WORKLOAD_MINT_COUNT: usize = 10;
pub const WORKLOAD_BURN_COUNT: usize = 10;
pub const WORKLOAD_SEND_COUNT: usize = 975;
pub const WORKLOAD_TOTAL: usize = WORKLOAD_GENESIS_COUNT + WORKLOAD_MINT_COUNT + WORKLOAD_BURN_COUNT + WORKLOAD_SEND_COUNT;

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
    Mint,
    Burn,
}

impl BenchTxKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::Mint => "M",
            Self::Burn => "B",
        }
    }

    pub fn op(self) -> u8 {
        match self {
            Self::Mint => OP_MINT,
            Self::Burn => OP_BURN,
        }
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

    pub fn build(mut self) -> Vec<LedgerTransaction> {
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

    fn emit_genesis(&mut self, index: usize) -> LedgerTransaction {
        let ts = self.next_timestamp();
        let account = &mut self.accounts[index];
        let sig: P256Signature = account.signing_key.sign(&[OP_GENESIS]);
        let sig_hex = hex::encode_upper(sig.to_bytes());
        prepare_genesis_transaction(
            account.id_hex.clone(),
            account.pubkey_hex.clone(),
            sig_hex,
            account.cert_hex.clone(),
            Some(i64::try_from(ts).expect("timestamp fits i64")),
        )
        .expect("genesis transaction validates")
    }

    fn emit_amount(&mut self, index: usize, kind: BenchTxKind) -> LedgerTransaction {
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
        prepare_ledger_transaction(
            account.pubkey_hex.clone(),
            kind.label().into(),
            String::new(),
            i64::try_from(goc).expect("goc fits i64"),
            i64::try_from(seq).expect("sequence fits i64"),
            sig_hex,
            Some(i64::try_from(ts).expect("timestamp fits i64")),
        )
        .expect("amount transaction validates")
    }

    fn emit_send(&mut self, author: usize, target: usize) -> LedgerTransaction {
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
        prepare_ledger_transaction(
            account.pubkey_hex.clone(),
            "S".into(),
            target_id,
            i64::try_from(goc).expect("goc fits i64"),
            i64::try_from(seq).expect("sequence fits i64"),
            sig_hex,
            Some(i64::try_from(ts).expect("timestamp fits i64")),
        )
        .expect("send transaction validates")
    }
}

#[cfg(feature = "bench-workload")]
impl Default for WorkloadBuilder {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(feature = "bench-workload")]
pub fn generate_workload() -> Vec<LedgerTransaction> {
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
