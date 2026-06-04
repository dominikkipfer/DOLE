use gix::bstr::ByteSlice;
use gix::{
    self,
    actor::Signature,
    date::{OffsetInSeconds, SecondsSinceUnixEpoch, Time},
    objs::{Commit, Tree},
};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::mpsc;

use crate::constants::{
    ID_SIZE, OP_BURN, OP_GENESIS, OP_MINT, OP_SEND, WIRE_INTERNAL_SYNC_REQUEST, WIRE_SYNC_REQUEST,
};
use crate::crypto::{
    hex_to_bytes, person_id_hex_from_id_or_pubkey_hex, signature_hex_to_der_hex,
    signature_hex_to_raw_hex, verify_card_certificate, verify_tx_signature,
};
use crate::network;

static ACTIVE_LISTENER: Mutex<Option<(String, Arc<dyn LedgerStateListener>)>> = Mutex::new(None);
static GLOBAL_TX_SENDER: Mutex<Option<mpsc::UnboundedSender<Vec<u8>>>> = Mutex::new(None);
static GLOBAL_SHUTDOWN_TX: Mutex<Option<mpsc::UnboundedSender<()>>> = Mutex::new(None);

const WIRE_ID_SIZE: usize = ID_SIZE as usize;

struct SyncTx {
    author_id: String,
    op: u8,
    target: String,
    payload: String,
    seq: i64,
    ts: i64,
    sig: String,
}

fn encode_tx_msg(
    author_id: &str,
    tx_t: &str,
    target: &str,
    payload: &str,
    seq: i64,
    ts: i64,
    sig: &str,
) -> Option<Vec<u8>> {
    let author = id_hex_to_array(author_id)?;
    let sig = hex_to_bytes(sig.to_string());
    if sig.is_empty() {
        return None;
    }

    let op = tx_type_to_op(tx_t)?;
    let mut msg = Vec::new();

    msg.push(op);
    msg.extend_from_slice(&author);
    msg.extend_from_slice(&seq.to_be_bytes());
    msg.extend_from_slice(&ts.to_be_bytes());

    match op {
        OP_GENESIS => {
            push_len_bytes(&mut msg, &hex_to_bytes(target.to_string()))?;
            push_len_bytes(&mut msg, &hex_to_bytes(payload.to_string()))?;
        }
        OP_MINT | OP_BURN => {
            msg.extend_from_slice(&payload.parse::<i64>().ok()?.to_be_bytes());
        }
        OP_SEND => {
            let target_id = person_id_hex_from_id_or_pubkey_hex(target)?;
            msg.extend_from_slice(&id_hex_to_array(&target_id)?);
            msg.extend_from_slice(&payload.parse::<i64>().ok()?.to_be_bytes());
        }
        _ => return None,
    }

    msg.extend_from_slice(&sig);
    Some(msg)
}

#[derive(uniffi::Record)]
pub struct GitResult {
    pub success: bool,
    pub message: String,
}

#[uniffi::export(callback_interface)]
pub trait LedgerStateListener: Send + Sync {
    fn on_state_updated(&self, balance: i64, transaction_history_json: String);
    fn on_error(&self, message: String);
}

#[derive(uniffi::Object)]
pub struct Ledger {
    listener: Option<Arc<dyn LedgerStateListener>>,
    public_key_id: String,
    public_key_full: String,
    repo_path: PathBuf,
    repo_mutex: Arc<Mutex<()>>,
}

#[uniffi::export]
pub fn start_global_sync(storage_path: String) {
    let mut sender_guard = GLOBAL_TX_SENDER.lock().unwrap();
    if sender_guard.is_some() {
        return;
    }

    let (out_tx, out_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let (in_tx, mut in_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let (shutdown_tx, shutdown_rx) = mpsc::unbounded_channel::<()>();

    *sender_guard = Some(out_tx.clone());

    network::start_sync_engine(out_rx, in_tx, shutdown_rx);

    let repo_path = PathBuf::from(&storage_path).join("dole_ledger");

    ensure_repo_initialized(&repo_path);

    if let Ok(mut guard) = GLOBAL_SHUTDOWN_TX.lock() {
        *guard = Some(shutdown_tx);
    }

    let sync_tx = out_tx.clone();

    thread::spawn(move || {
        let repo_mutex = Arc::new(Mutex::new(()));

        while let Some(msg) = in_rx.blocking_recv() {
            if msg.as_slice() == &[WIRE_INTERNAL_SYNC_REQUEST] {
                send_sync_request(&repo_path, &sync_tx);
                continue;
            }

            if let Some(peer_sequences) = decode_sync_request(&msg) {
                resend_missing_history(&repo_path, &sync_tx, &peer_sequences);
                continue;
            }

            if let Some(sync_tx) = decode_tx_message(&msg) {
                let Some(tx_t) = op_to_tx_type(sync_tx.op) else {
                    continue;
                };

                let author_pub_full = if sync_tx.op == OP_GENESIS {
                    sync_tx.payload.clone()
                } else if let Some(stored_pubkey) =
                    get_pubkey_from_genesis(&repo_path, &sync_tx.author_id)
                {
                    stored_pubkey
                } else {
                    continue;
                };

                let local_seq = get_latest_seq_for_branch(&repo_path, &sync_tx.author_id);
                if sync_tx.seq <= local_seq {
                    continue;
                }

                let mut valid = verify_tx_signature(
                    &author_pub_full,
                    tx_t,
                    &sync_tx.target,
                    &sync_tx.payload,
                    sync_tx.seq,
                    &sync_tx.sig,
                );

                if sync_tx.op == OP_GENESIS {
                    let pub_bytes = hex_to_bytes(author_pub_full.clone());
                    let cert_bytes = hex_to_bytes(sync_tx.target.clone());
                    if !verify_card_certificate(pub_bytes, cert_bytes) {
                        valid = false;
                    }
                }

                if valid {
                    let _guard = repo_mutex.lock().unwrap();
                    let target_commit = target_for_commit(tx_t, &sync_tx.target);
                    let _ = write_commit(
                        &repo_path,
                        &sync_tx.author_id,
                        tx_t,
                        &target_commit,
                        &sync_tx.payload,
                        sync_tx.seq,
                        sync_tx.ts,
                        &sync_tx.sig,
                    );

                    if let Ok(listener_guard) = ACTIVE_LISTENER.lock() {
                        if let Some((active_pub_id, listener)) = listener_guard.as_ref() {
                            notify_ui_internal(&repo_path, active_pub_id, listener);
                        }
                    }
                }
            }
        }
    });
}

#[uniffi::export]
pub fn stop_global_sync() {
    if let Ok(mut guard) = GLOBAL_SHUTDOWN_TX.lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.send(());
        }
    }
    if let Ok(mut guard) = GLOBAL_TX_SENDER.lock() {
        *guard = None;
    }
}

#[uniffi::export]
impl Ledger {
    #[uniffi::constructor]
    pub fn init_ledger(
        listener: Box<dyn LedgerStateListener>,
        storage_path: String,
        public_key_id: String,
        public_key_full: String,
    ) -> Self {
        let listener_arc: Arc<dyn LedgerStateListener> = listener.into();
        let repo_path = PathBuf::from(storage_path).join("dole_ledger");

        ensure_repo_initialized(&repo_path);

        let resolved_pubkey = if public_key_full.is_empty() {
            get_pubkey_from_genesis(&repo_path, &public_key_id).unwrap_or_default()
        } else {
            public_key_full
        };

        if let Ok(mut guard) = ACTIVE_LISTENER.lock() {
            *guard = Some((public_key_id.clone(), listener_arc.clone()));
        }

        let engine = Ledger {
            listener: Some(listener_arc.clone()),
            public_key_id: public_key_id.clone(),
            public_key_full: resolved_pubkey,
            repo_path: repo_path.clone(),
            repo_mutex: Arc::new(Mutex::new(())),
        };

        notify_ui_internal(&repo_path, &public_key_id, &listener_arc);
        engine
    }

    pub fn shutdown(&self) {
        if let Ok(mut guard) = ACTIVE_LISTENER.lock() {
            *guard = None;
        }
    }

    pub fn genesis(&self, full_pubkey_hex: String, sig_hex: String, cert_hex: String) {
        let seq = self.get_next_seq();
        let ts = get_current_timestamp();
        let pubkey_payload = &full_pubkey_hex;
        let pub_bytes = hex_to_bytes(full_pubkey_hex.clone());
        let raw_sig_hex = match signature_hex_to_raw_hex(&sig_hex) {
            Some(sig) => sig,
            None => {
                self.handle_tx_result(GitResult {
                    success: false,
                    message: "Genesis signature format invalid".into(),
                });
                return;
            }
        };
        let raw_cert_hex = match signature_hex_to_raw_hex(&cert_hex) {
            Some(cert) => cert,
            None => {
                self.handle_tx_result(GitResult {
                    success: false,
                    message: "Certificate signature format invalid".into(),
                });
                return;
            }
        };
        let cert_bytes = hex_to_bytes(raw_cert_hex.clone());

        if !verify_card_certificate(pub_bytes, cert_bytes) {
            self.handle_tx_result(GitResult {
                success: false,
                message: "Certificate verification failed".into(),
            });
            return;
        }

        if !verify_tx_signature(
            &full_pubkey_hex,
            "G",
            &raw_cert_hex,
            pubkey_payload,
            seq,
            &raw_sig_hex,
        ) {
            self.handle_tx_result(GitResult {
                success: false,
                message: "Genesis signature verification failed".into(),
            });
            return;
        }

        self.handle_tx_result(self.commit_internal(
            "G",
            &raw_cert_hex,
            pubkey_payload,
            seq,
            ts,
            &raw_sig_hex,
        ));
    }

    pub fn mint(&self, delta: i64, sig_hex: String) {
        self.mint_burn(OP_MINT, delta, sig_hex);
    }

    pub fn burn(&self, delta: i64, sig_hex: String) {
        self.mint_burn(OP_BURN, delta, sig_hex);
    }

    pub fn send(&self, target_pub_key: String, delta: i64, sig_hex: String) {
        let seq = self.get_next_seq();
        let ts = get_current_timestamp();
        let target_commit = target_for_commit("S", &target_pub_key);
        let cumulative = get_last_goc(
            &self.repo_path,
            &self.public_key_id,
            "S",
            Some(&target_commit),
        ) + delta;
        let raw_sig_hex = match signature_hex_to_raw_hex(&sig_hex) {
            Some(sig) => sig,
            None => {
                self.handle_tx_result(GitResult {
                    success: false,
                    message: "Send signature format invalid".into(),
                });
                return;
            }
        };

        if !verify_tx_signature(
            &self.public_key_full,
            "S",
            &target_pub_key,
            &cumulative.to_string(),
            seq,
            &raw_sig_hex,
        ) {
            self.handle_tx_result(GitResult {
                success: false,
                message: "Send signature verification failed".into(),
            });
            return;
        }

        self.handle_tx_result(self.commit_internal(
            "S",
            &target_pub_key,
            &cumulative.to_string(),
            seq,
            ts,
            &raw_sig_hex,
        ));
    }
}

impl Ledger {
    fn get_next_seq(&self) -> i64 {
        get_latest_seq_for_branch(&self.repo_path, &self.public_key_id) + 1
    }

    fn mint_burn(&self, op: u8, delta: i64, sig_hex: String) {
        let tx_t = match op {
            OP_MINT => "M",
            OP_BURN => "B",
            _ => {
                self.handle_tx_result(GitResult {
                    success: false,
                    message: "Unsupported mint/burn operation".into(),
                });
                return;
            }
        };

        let seq = self.get_next_seq();
        let ts = get_current_timestamp();
        let cumulative = get_last_goc(&self.repo_path, &self.public_key_id, tx_t, None) + delta;
        let raw_sig_hex = match signature_hex_to_raw_hex(&sig_hex) {
            Some(sig) => sig,
            None => {
                self.handle_tx_result(GitResult {
                    success: false,
                    message: format!("{tx_t} signature format invalid"),
                });
                return;
            }
        };

        if self.public_key_full.is_empty() {
            self.handle_tx_result(GitResult {
                success: false,
                message: format!("{tx_t} failed: missing public key. Seq={seq}, GoC={cumulative}"),
            });
            return;
        }

        if !verify_tx_signature(
            &self.public_key_full,
            tx_t,
            "",
            &cumulative.to_string(),
            seq,
            &raw_sig_hex,
        ) {
            self.handle_tx_result(GitResult {
                success: false,
                message: format!(
                    "{tx_t} signature verification failed. Seq={seq}, cumulative_goc={cumulative}"
                ),
            });
            return;
        }

        self.handle_tx_result(self.commit_internal(
            tx_t,
            "",
            &cumulative.to_string(),
            seq,
            ts,
            &raw_sig_hex,
        ));
    }

    fn handle_tx_result(&self, res: GitResult) {
        if !res.success {
            println!("CRITICAL RUST ERROR: {}", res.message);
            if let Some(l) = &self.listener {
                l.on_error(res.message.clone());
            }
            return;
        }
        if let Some(l) = &self.listener {
            notify_ui_internal(&self.repo_path, &self.public_key_id, l);
        }
    }

    fn commit_internal(
        &self,
        tx_t: &str,
        target: &str,
        goc: &str,
        seq: i64,
        ts: i64,
        sig: &str,
    ) -> GitResult {
        let _guard = self.repo_mutex.lock().unwrap();
        let target_commit = target_for_commit(tx_t, target);

        match write_commit(
            &self.repo_path,
            &self.public_key_id,
            tx_t,
            &target_commit,
            goc,
            seq,
            ts,
            sig,
        ) {
            Ok(h) => {
                let msg = encode_tx_msg(&self.public_key_id, tx_t, target, goc, seq, ts, sig);
                if let Some(msg) = msg {
                    if let Ok(guard) = GLOBAL_TX_SENDER.lock() {
                        if let Some(tx) = guard.as_ref() {
                            let _ = tx.send(msg);
                        }
                    }
                } else {
                    log::error!(target: "dole::ledger", "Could not encode local transaction for sync broadcast");
                }
                GitResult {
                    success: true,
                    message: h,
                }
            }
            Err(e) => GitResult {
                success: false,
                message: e,
            },
        }
    }
}

fn get_pubkey_from_genesis(path: &PathBuf, branch: &str) -> Option<String> {
    if let Ok(repo) = gix::open(path) {
        let branch_ref = format!("refs/heads/{}", branch);
        if let Ok(r) = repo.find_reference(branch_ref.as_str()) {
            if let Ok(_obj) = repo.find_object(r.id().detach()) {
                let mut current_id = Some(r.id().detach());
                while let Some(id) = current_id {
                    if let Ok(obj) = repo.find_object(id) {
                        if let Ok(c) = obj.try_into_commit() {
                            if let Ok(decoded) = c.decode() {
                                if let Ok(author_sig) = decoded.author() {
                                    if author_sig.name.to_str_lossy() == "G" {
                                        return Some(author_sig.email.to_str_lossy().into_owned());
                                    }
                                }
                            }
                            current_id = c.parent_ids().next().map(|p| p.detach());
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
    }
    None
}

fn target_for_commit(tx_t: &str, target: &str) -> String {
    if tx_t == "S" {
        return person_id_hex_from_id_or_pubkey_hex(target).unwrap_or_else(|| target.to_string());
    }

    target.to_string()
}

fn decode_tx_message(msg: &[u8]) -> Option<SyncTx> {
    let mut r = WireReader::new(msg);

    let op = r.read_u8()?;
    op_to_tx_type(op)?;

    let author_id = hex::encode_upper(r.read_bytes(WIRE_ID_SIZE)?);
    let seq = r.read_i64()?;
    let ts = r.read_i64()?;

    let (target, payload) = match op {
        OP_GENESIS => (r.read_len_hex()?, r.read_len_hex()?),
        OP_MINT | OP_BURN => (String::new(), r.read_i64()?.to_string()),
        OP_SEND => (
            hex::encode_upper(r.read_bytes(WIRE_ID_SIZE)?),
            r.read_i64()?.to_string(),
        ),
        _ => return None,
    };

    let sig = r.read_remaining_hex()?;

    Some(SyncTx {
        author_id,
        op,
        target,
        payload,
        seq,
        ts,
        sig,
    })
}

struct WireReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> WireReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    fn read_u8(&mut self) -> Option<u8> {
        let byte = *self.data.get(self.pos)?;
        self.pos += 1;
        Some(byte)
    }

    fn read_u16(&mut self) -> Option<u16> {
        let bytes = self.read_bytes(2)?;
        Some(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    fn read_i64(&mut self) -> Option<i64> {
        let bytes = self.read_bytes(8)?;
        Some(i64::from_be_bytes([
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        ]))
    }

    fn read_bytes(&mut self, len: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(len)?;
        let bytes = self.data.get(self.pos..end)?;
        self.pos = end;
        Some(bytes)
    }

    fn read_len_bytes(&mut self) -> Option<&'a [u8]> {
        let len = usize::from(self.read_u16()?);
        self.read_bytes(len)
    }

    fn read_len_hex(&mut self) -> Option<String> {
        Some(hex::encode_upper(self.read_len_bytes()?))
    }

    fn read_remaining_bytes(&mut self) -> Option<&'a [u8]> {
        let bytes = self.data.get(self.pos..)?;
        if bytes.is_empty() {
            return None;
        }
        self.pos = self.data.len();
        Some(bytes)
    }

    fn read_remaining_hex(&mut self) -> Option<String> {
        Some(hex::encode_upper(self.read_remaining_bytes()?))
    }

    fn is_done(&self) -> bool {
        self.pos == self.data.len()
    }
}

fn push_len_bytes(msg: &mut Vec<u8>, bytes: &[u8]) -> Option<()> {
    let len = u16::try_from(bytes.len()).ok()?;
    msg.extend_from_slice(&len.to_be_bytes());
    msg.extend_from_slice(bytes);
    Some(())
}

fn id_hex_to_array(value: &str) -> Option<[u8; WIRE_ID_SIZE]> {
    let bytes = hex_to_bytes(value.to_string());
    if bytes.len() != WIRE_ID_SIZE {
        return None;
    }

    let mut out = [0; WIRE_ID_SIZE];
    out.copy_from_slice(&bytes);
    Some(out)
}

fn tx_type_to_op(tx_t: &str) -> Option<u8> {
    match tx_t {
        "G" => Some(OP_GENESIS),
        "M" => Some(OP_MINT),
        "B" => Some(OP_BURN),
        "S" => Some(OP_SEND),
        _ => None,
    }
}

fn op_to_tx_type(op: u8) -> Option<&'static str> {
    match op {
        OP_GENESIS => Some("G"),
        OP_MINT => Some("M"),
        OP_BURN => Some("B"),
        OP_SEND => Some("S"),
        _ => None,
    }
}

fn get_current_timestamp() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
}

fn ensure_repo_initialized(repo_path: &PathBuf) {
    if gix::open(repo_path).is_err() {
        if let Err(e) = gix::init(repo_path) {
            log::error!(target: "dole::ledger", "Failed to initialize local ledger repository: {e}");
            return;
        }
    }

    let config_path = repo_path.join(".git").join("config");
    if let Ok(current_config) = std::fs::read_to_string(&config_path) {
        if !current_config.contains("DOLE System") {
            let extra_config = "\n[user]\n\tname = DOLE System\n\temail = system@dole.local\n";
            if let Err(e) = std::fs::write(&config_path, current_config + extra_config) {
                log::warn!(target: "dole::ledger", "Failed to update local ledger git config: {e}");
            }
        }
    }
}

fn get_last_goc(path: &PathBuf, branch: &str, tx_type: &str, target_filter: Option<&str>) -> i64 {
    let Ok(repo) = gix::open(path) else {
        return 0;
    };

    let branch_ref = format!("refs/heads/{}", branch);
    let Ok(r) = repo.find_reference(branch_ref.as_str()) else {
        return 0;
    };

    let mut current_id = Some(r.id().detach());
    while let Some(id) = current_id {
        let Ok(obj) = repo.find_object(id) else {
            break;
        };
        let Ok(c) = obj.try_into_commit() else {
            break;
        };

        current_id = c.parent_ids().next().map(|p| p.detach());

        let Ok(decoded) = c.decode() else {
            continue;
        };
        let Ok(author_sig) = decoded.author() else {
            continue;
        };

        if author_sig.name.to_str_lossy() != tx_type {
            continue;
        }

        let target_matches = match target_filter {
            Some(target_filter) => decoded
                .committer()
                .map(|committer_sig| {
                    committer_sig
                        .name
                        .to_str_lossy()
                        .eq_ignore_ascii_case(target_filter)
                })
                .unwrap_or(false),
            None => true,
        };

        if !target_matches {
            continue;
        }

        if let Ok(val) = author_sig.email.to_str_lossy().parse::<i64>() {
            return val;
        }
    }

    0
}

fn write_commit(
    path: &PathBuf,
    branch: &str,
    tx_t: &str,
    target: &str,
    goc: &str,
    seq: i64,
    ts: i64,
    sig: &str,
) -> Result<String, String> {
    let repo = gix::open(path).map_err(|e| e.to_string())?;

    let tree_id = repo
        .write_object(&Tree::empty())
        .map_err(|e| e.to_string())?
        .detach();

    let branch_ref = format!("refs/heads/{}", branch);
    let parent = repo
        .find_reference(&branch_ref)
        .ok()
        .map(|r| r.id().detach());
    let mut parents = Vec::new();
    if let Some(p) = parent {
        parents.push(p);
    }

    let author = Signature {
        name: tx_t.into(),
        email: goc.into(),
        time: Time {
            seconds: seq as SecondsSinceUnixEpoch,
            offset: 0 as OffsetInSeconds,
        },
    };

    let committer = Signature {
        name: target.into(),
        email: sig.into(),
        time: Time {
            seconds: ts as SecondsSinceUnixEpoch,
            offset: 0 as OffsetInSeconds,
        },
    };

    let commit = Commit {
        tree: tree_id,
        parents: parents.into(),
        author,
        committer,
        encoding: None,
        message: "".into(),
        extra_headers: vec![],
    };

    let id = repo
        .write_object(&commit)
        .map_err(|e| e.to_string())?
        .detach();

    let branch_full_name = format!("refs/heads/{}", branch);
    let edit = gix::refs::transaction::RefEdit {
        change: gix::refs::transaction::Change::Update {
            log: gix::refs::transaction::LogChange {
                mode: gix::refs::transaction::RefLog::AndReference,
                force_create_reflog: false,
                message: "tx".into(),
            },
            expected: match parent {
                Some(pid) => gix::refs::transaction::PreviousValue::ExistingMustMatch(
                    gix::refs::Target::Object(pid),
                ),
                None => gix::refs::transaction::PreviousValue::MustNotExist,
            },
            new: gix::refs::Target::Object(id),
        },
        name: branch_full_name
            .as_str()
            .try_into()
            .map_err(|e| format!("{:?}", e))?,
        deref: false,
    };

    repo.edit_reference(edit).map_err(|e| e.to_string())?;
    Ok(id.to_hex().to_string())
}

fn notify_ui_internal(path: &PathBuf, my_key: &str, listener: &Arc<dyn LedgerStateListener>) {
    let repo = if let Ok(r) = gix::open(path) {
        r
    } else {
        return;
    };

    let mut raw_history = Vec::new();

    if let Ok(refs) = repo.references() {
        if let Ok(branches) = refs.local_branches() {
            for b_res in branches {
                if let Ok(b) = b_res {
                    let b_name = b.name().shorten().to_string();
                    let mut curr = Some(b.id().detach());

                    while let Some(id) = curr {
                        if let Ok(obj) = repo.find_object(id) {
                            if let Ok(c) = obj.try_into_commit() {
                                if let Ok(decoded) = c.decode() {
                                    let author_sig = match decoded.author() {
                                        Ok(s) => s,
                                        Err(_) => {
                                            curr = c.parent_ids().next().map(|p| p.detach());
                                            continue;
                                        }
                                    };
                                    let committer_sig = match decoded.committer() {
                                        Ok(s) => s,
                                        Err(_) => {
                                            curr = c.parent_ids().next().map(|p| p.detach());
                                            continue;
                                        }
                                    };

                                    let t = author_sig.name.to_str_lossy().into_owned();
                                    let payload_s = author_sig.email.to_str_lossy().into_owned();
                                    let seq = author_sig.seconds() as i64;
                                    let target = committer_sig.name.to_str_lossy().into_owned();
                                    let ts = committer_sig.seconds() as i64;
                                    let sig = committer_sig.email.to_str_lossy().into_owned();

                                    let cum_val = if t == "G" {
                                        0
                                    } else {
                                        payload_s.parse::<i64>().unwrap_or(0)
                                    };

                                    let is_own_branch = b_name.eq_ignore_ascii_case(my_key);
                                    let is_incoming_send =
                                        t == "S" && target.eq_ignore_ascii_case(my_key);
                                    let is_peer_metadata = t == "G";
                                    if is_own_branch || is_incoming_send || is_peer_metadata {
                                        raw_history.push((
                                            id.to_hex().to_string(),
                                            t,
                                            b_name.clone(),
                                            target,
                                            seq,
                                            ts,
                                            sig,
                                            cum_val,
                                            payload_s,
                                        ));
                                    }
                                }
                                curr = c.parent_ids().next().map(|p| p.detach());
                            } else {
                                break;
                            }
                        } else {
                            break;
                        }
                    }
                }
            }
        }
    }

    raw_history.sort_by(|a, b| a.2.cmp(&b.2).then_with(|| a.4.cmp(&b.4)));

    let mut tracker = HashMap::new();
    let mut history = Vec::new();
    let mut balance = 0i64;

    for (id, t, b_name, target, seq, ts, sig, cum_val, payload_s) in raw_history {
        let key = if t == "S" {
            format!("{}_{}_{}", b_name, t, target)
        } else {
            format!("{}_{}", b_name, t)
        };
        let prev_val = *tracker.get(&key).unwrap_or(&0i64);
        let delta = cum_val - prev_val;
        tracker.insert(key, cum_val);

        let is_own_branch = b_name.eq_ignore_ascii_case(my_key);
        let is_incoming_send = t == "S" && target.eq_ignore_ascii_case(my_key);

        if is_own_branch {
            if t == "M" || t == "G" {
                balance += delta;
            } else if t == "B" || t == "S" {
                balance -= delta;
            }
        } else if is_incoming_send {
            balance += delta;
        }

        let f_t = match t.as_str() {
            "G" => "GENESIS",
            "M" => "MINT",
            "B" => "BURN",
            "S" => "SEND",
            _ => "UNK",
        };

        let display_target = if t == "G" {
            signature_hex_to_der_hex(&target).unwrap_or_else(|| target.clone())
        } else {
            target.clone()
        };
        let display_sig = signature_hex_to_der_hex(&sig).unwrap_or_else(|| sig.clone());

        let safe_target = if display_target == "-" {
            "".to_string()
        } else {
            display_target.clone()
        };

        let extra = if t == "G" {
            format!(
                r#","certificate":"{}","publicKey":"{}" "#,
                display_target, payload_s
            )
        } else {
            String::new()
        };

        history.push((
            ts,
            format!(
                r#"{{"id":"{}","type":"{}","goc":{},"author":"{}","target":"{}","seq":{},"timestamp":{},"signature":"{}"{} }}"#,
                id, f_t, delta, b_name, safe_target, seq, ts, display_sig, extra
            ),
        ));
    }

    history.sort_by(|a, b| b.0.cmp(&a.0));
    let sorted: Vec<String> = history.into_iter().map(|k| k.1).collect();

    listener.on_state_updated(balance, format!("[{}]", sorted.join(",")));
}

fn get_latest_seq_for_branch(path: &PathBuf, branch: &str) -> i64 {
    if let Ok(repo) = gix::open(path) {
        let branch_ref = format!("refs/heads/{}", branch);
        if let Ok(r) = repo.find_reference(branch_ref.as_str()) {
            if let Ok(obj) = repo.find_object(r.id().detach()) {
                if let Ok(c) = obj.try_into_commit() {
                    if let Ok(decoded) = c.decode() {
                        if let Ok(author_sig) = decoded.author() {
                            return author_sig.seconds() as i64;
                        }
                    }
                }
            }
        }
    }
    -1
}

fn send_sync_request(path: &PathBuf, tx_sender: &mpsc::UnboundedSender<Vec<u8>>) {
    let mut entries: Vec<(String, i64)> = latest_sequences(path).into_iter().collect();
    entries.sort_by(|a, b| a.0.cmp(&b.0));

    let _ = tx_sender.send(encode_sync_request(entries));
}

fn encode_sync_request(entries: Vec<(String, i64)>) -> Vec<u8> {
    let entries = entries
        .into_iter()
        .filter_map(|(branch, seq)| id_hex_to_array(&branch).map(|id| (id, seq)))
        .collect::<Vec<_>>();

    if entries.is_empty() {
        return vec![WIRE_SYNC_REQUEST];
    }

    let count = u16::try_from(entries.len()).unwrap_or(u16::MAX);
    let mut msg = Vec::with_capacity(3 + usize::from(count) * (WIRE_ID_SIZE + 8));

    msg.push(WIRE_SYNC_REQUEST);
    msg.extend_from_slice(&count.to_be_bytes());

    for (id, seq) in entries.into_iter().take(usize::from(count)) {
        msg.extend_from_slice(&id);
        msg.extend_from_slice(&seq.to_be_bytes());
    }

    msg
}

fn latest_sequences(path: &PathBuf) -> HashMap<String, i64> {
    let mut sequences = HashMap::new();
    let repo = if let Ok(r) = gix::open(path) {
        r
    } else {
        return sequences;
    };

    if let Ok(refs) = repo.references() {
        if let Ok(branches) = refs.local_branches() {
            for b_res in branches {
                if let Ok(b) = b_res {
                    let branch = b.name().shorten().to_string();
                    if let Ok(obj) = repo.find_object(b.id().detach()) {
                        if let Ok(commit) = obj.try_into_commit() {
                            if let Ok(decoded) = commit.decode() {
                                if let Ok(author) = decoded.author() {
                                    sequences.insert(branch, author.seconds() as i64);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sequences
}

fn decode_sync_request(msg: &[u8]) -> Option<HashMap<String, i64>> {
    let mut r = WireReader::new(msg);

    if r.read_u8()? != WIRE_SYNC_REQUEST {
        return None;
    }

    if r.is_done() {
        return Some(HashMap::new());
    }

    let count = r.read_u16()?;
    let mut sequences = HashMap::new();

    for _ in 0..count {
        let branch = hex::encode_upper(r.read_bytes(WIRE_ID_SIZE)?);
        let seq = r.read_i64()?;
        sequences.insert(branch, seq);
    }

    if !r.is_done() {
        return None;
    }

    Some(sequences)
}

fn resend_missing_history(
    path: &PathBuf,
    tx_sender: &mpsc::UnboundedSender<Vec<u8>>,
    peer_sequences: &HashMap<String, i64>,
) {
    let repo = if let Ok(r) = gix::open(path) {
        r
    } else {
        log::warn!(target: "dole::ledger", "Cannot resend ledger history because the local repository is not available");
        return;
    };

    if let Ok(refs) = repo.references() {
        if let Ok(branches) = refs.local_branches() {
            for b_res in branches {
                if let Ok(b) = b_res {
                    let b_name = b.name().shorten().to_string();
                    let peer_seq = peer_sequences.get(&b_name).copied().unwrap_or(-1);
                    let mut curr = Some(b.id().detach());
                    let mut to_send = Vec::new();

                    while let Some(id) = curr {
                        if let Ok(obj) = repo.find_object(id) {
                            if let Ok(c) = obj.try_into_commit() {
                                if let Ok(decoded) = c.decode() {
                                    if let (Ok(author), Ok(committer)) =
                                        (decoded.author(), decoded.committer())
                                    {
                                        let tx_t = author.name.to_str_lossy();
                                        let goc = author.email.to_str_lossy();
                                        let seq = author.seconds() as i64;
                                        let target = committer.name.to_str_lossy();
                                        let ts = committer.seconds();
                                        let sig = committer.email.to_str_lossy();

                                        if seq > peer_seq {
                                            if let Some(msg) = encode_tx_msg(
                                                &b_name,
                                                tx_t.as_ref(),
                                                target.as_ref(),
                                                goc.as_ref(),
                                                seq,
                                                ts as i64,
                                                sig.as_ref(),
                                            ) {
                                                to_send.push(msg);
                                            } else {
                                                log::warn!(target: "dole::ledger", "Skipped transaction that could not be encoded for sync");
                                            }
                                        }
                                    }
                                    curr = c.parent_ids().next().map(|p| p.detach());
                                } else {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    }

                    to_send.reverse();
                    for msg in to_send {
                        let _ = tx_sender.send(msg);
                        thread::sleep(std::time::Duration::from_millis(5));
                    }
                }
            }
        }
    }
}
