use gix::bstr::{BString, ByteSlice};
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

use crate::constants::{ID_SIZE, OP_BURN, OP_GENESIS, OP_MINT, OP_SEND, SIGNATURE_SIZE};
use crate::crypto::{
    find_genesis_recovery_id, find_tx_recovery_id_for_pubkey_hex, hex_to_bytes,
    person_id_hex_from_id_or_pubkey_hex, recover_genesis_pubkey_hex,
    recover_tx_pubkey_hex_with_recovery_id, signature_hex_to_der_hex, signature_hex_to_raw_hex,
    verify_genesis_tx, verify_tx_signature,
};
use crate::logging;
use crate::network::{
    self, OutboundMessage, WireHeader, WirePrefix, WireReader, group_u64_len_code, push_group_u64,
};

static ACTIVE_LISTENER: Mutex<Option<(String, Arc<dyn LedgerStateListener>)>> = Mutex::new(None);
static GLOBAL_TX_SENDER: Mutex<Option<mpsc::UnboundedSender<OutboundMessage>>> = Mutex::new(None);
static GLOBAL_SHUTDOWN_TX: Mutex<Option<mpsc::UnboundedSender<()>>> = Mutex::new(None);
static LAST_UNRESOLVED_AUTHOR_FRONTIER_ANNOUNCEMENT: Mutex<Option<std::time::Instant>> =
    Mutex::new(None);

const WIRE_ID_SIZE: usize = ID_SIZE as usize;
const WIRE_SIGNATURE_SIZE: usize = SIGNATURE_SIZE as usize;
const LEDGER_LOG_TARGET: &str = "dole::ledger";
const RECOVERY_ID_HEADER: &str = "dole-recovery-id";

struct SyncTx {
    author_id: String,
    author_pubkey: String,
    tx_type: u8,
    recovery_id: u8,
    target: String,
    payload: String,
    seq: u64,
    ts: u64,
    sig: String,
}

struct CommitInput<'a> {
    branch: &'a str,
    tx_t: &'a str,
    target: &'a str,
    goc: &'a str,
    seq: u64,
    ts: u64,
    sig: &'a str,
    recovery_id: u8,
}

struct KnownBranchTx {
    tx_t: String,
    target: String,
    payload: String,
    sig: String,
    recovery_id: Option<u8>,
}

impl KnownBranchTx {
    fn matches_sync_tx(
        &self,
        tx_t: &str,
        target: &str,
        payload: &str,
        sig: &str,
        recovery_id: u8,
    ) -> bool {
        self.tx_t == tx_t
            && self.target.eq_ignore_ascii_case(target)
            && self.payload.eq_ignore_ascii_case(payload)
            && self.sig.eq_ignore_ascii_case(sig)
            && self.recovery_id == Some(recovery_id)
    }
}

fn encode_tx_msg(
    tx_t: &str,
    target: &str,
    payload: &str,
    seq: u64,
    ts: u64,
    sig: &str,
    recovery_id: u8,
) -> Option<Vec<u8>> {
    let sig = hex_to_bytes(sig.to_string());
    if sig.is_empty() {
        return None;
    }

    let tx_type = type_from_tx_label(tx_t)?;
    let mut msg = Vec::new();
    let ts_len = group_u64_len_code(ts);
    msg.push(WireHeader::checked(tx_type, recovery_id, ts_len)?.into_bytes()[0]);

    if tx_type == OP_GENESIS {
        let cert = hex_to_bytes(target.to_string());
        if sig.len() != WIRE_SIGNATURE_SIZE || cert.len() != WIRE_SIGNATURE_SIZE {
            return None;
        }
        msg.extend_from_slice(&sig);
        msg.extend_from_slice(&cert);
        push_group_u64(&mut msg, ts, ts_len);
        return Some(msg);
    }

    if sig.len() != WIRE_SIGNATURE_SIZE {
        return None;
    }

    let goc = payload.parse::<u64>().ok()?;
    let seq_len = group_u64_len_code(seq);
    let goc_len = group_u64_len_code(goc);
    msg.push(WirePrefix::checked(seq_len, goc_len)?.into_bytes()[0]);
    push_group_u64(&mut msg, seq, seq_len);
    push_group_u64(&mut msg, goc, goc_len);
    push_group_u64(&mut msg, ts, ts_len);

    match tx_type {
        OP_MINT | OP_BURN => {}
        OP_SEND => {
            let target_id = person_id_hex_from_id_or_pubkey_hex(target)?;
            msg.extend_from_slice(&id_hex_to_array(&target_id)?);
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

    let (out_tx, out_rx) = mpsc::unbounded_channel::<OutboundMessage>();
    let (in_tx, mut in_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let (frontier_request_tx, mut frontier_request_rx) = mpsc::unbounded_channel::<()>();
    let (shutdown_tx, shutdown_rx) = mpsc::unbounded_channel::<()>();

    *sender_guard = Some(out_tx.clone());

    network::start_sync_engine(out_rx, in_tx, frontier_request_tx, shutdown_rx);

    let repo_path = PathBuf::from(&storage_path).join("dole_ledger");

    ensure_repo_initialized(&repo_path);

    if let Ok(mut guard) = GLOBAL_SHUTDOWN_TX.lock() {
        *guard = Some(shutdown_tx);
    }

    let sync_tx = out_tx.clone();
    let frontier_repo_path = repo_path.clone();
    let frontier_sync_tx = out_tx.clone();

    thread::spawn(move || {
        while frontier_request_rx.blocking_recv().is_some() {
            announce_frontier(&frontier_repo_path, &frontier_sync_tx);
        }
    });

    thread::spawn(move || {
        let repo_mutex = Arc::new(Mutex::new(()));

        while let Some(msg) = in_rx.blocking_recv() {
            if let Some(peer_sequences) = network::decode_frontier_announcement(&msg) {
                log::debug!(
                    target: LEDGER_LOG_TARGET,
                    "RX sync frontier branches={} bytes={}",
                    peer_sequences.len(),
                    msg.len()
                );
                let should_announce_back =
                    peer_is_ahead_of_local_frontier(&repo_path, &peer_sequences);
                send_history_after_frontier(&repo_path, &sync_tx, &peer_sequences);
                if should_announce_back {
                    log::debug!(
                        target: LEDGER_LOG_TARGET,
                        "TX sync frontier announce-back reason=peer-ahead"
                    );
                    announce_frontier(&repo_path, &sync_tx);
                }
                continue;
            }

            if let Some(tx_messages) = network::decode_transaction_batch(&msg) {
                let tx_count = tx_messages.len();
                log::debug!(
                    target: LEDGER_LOG_TARGET,
                    "RX sync tx-batch txs={} bytes={}",
                    tx_count,
                    msg.len()
                );
                for tx_msg in tx_messages {
                    if let Some(sync_tx) = decode_tx_message(&tx_msg) {
                        apply_sync_tx(&repo_path, &repo_mutex, sync_tx);
                    } else {
                        log::warn!(
                            target: LEDGER_LOG_TARGET,
                            "RX sync tx skipped reason=decode-failed bytes={} head={}",
                            tx_msg.len(),
                            short_payload_hex(&tx_msg)
                        );
                    }
                }
                continue;
            }

            if let Some(sync_tx) = decode_tx_message(&msg) {
                apply_sync_tx(&repo_path, &repo_mutex, sync_tx);
            } else {
                log::warn!(
                    target: LEDGER_LOG_TARGET,
                    "RX sync payload skipped reason=unknown-format bytes={} head={}",
                    msg.len(),
                    short_payload_hex(&msg)
                );
            }
        }
    });
}

#[uniffi::export]
pub fn stop_global_sync() {
    if let Ok(mut guard) = GLOBAL_SHUTDOWN_TX.lock()
        && let Some(tx) = guard.take()
    {
        let _ = tx.send(());
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

    pub fn genesis(&self, sig_hex: String, cert_hex: String) {
        if get_latest_seq_for_branch(&self.repo_path, &self.public_key_id).is_some() {
            return;
        }

        let ts = get_current_timestamp();
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

        let (recovered_pubkey_hex, recovery_id) =
            match find_genesis_recovery_id(&raw_sig_hex, &raw_cert_hex) {
                Some(recovered) => recovered,
                None => {
                    self.handle_tx_result(GitResult {
                        success: false,
                        message: "Genesis public key recovery failed".into(),
                    });
                    return;
                }
            };
        let Some(author_id) = person_id_hex_from_id_or_pubkey_hex(&recovered_pubkey_hex) else {
            self.handle_tx_result(GitResult {
                success: false,
                message: "Genesis recovered public key is invalid".into(),
            });
            return;
        };

        if !author_id.eq_ignore_ascii_case(&self.public_key_id) {
            self.handle_tx_result(GitResult {
                success: false,
                message: "Genesis recovered public key does not match the active account".into(),
            });
            return;
        }

        let res = write_commit(
            &self.repo_path,
            CommitInput {
                branch: &author_id,
                tx_t: "G",
                target: &raw_cert_hex,
                goc: &recovered_pubkey_hex,
                seq: 0,
                ts,
                sig: &raw_sig_hex,
                recovery_id,
            },
        )
        .map(|h| GitResult {
            success: true,
            message: h,
        })
        .unwrap_or_else(|e| GitResult {
            success: false,
            message: e,
        });

        if res.success
            && let Some(msg) = encode_tx_msg(
                "G",
                &raw_cert_hex,
                &recovered_pubkey_hex,
                0,
                ts,
                &raw_sig_hex,
                recovery_id,
            )
            && let Ok(guard) = GLOBAL_TX_SENDER.lock()
            && let Some(tx) = guard.as_ref()
        {
            let _ = tx.send(OutboundMessage::Transactions(vec![msg]));
        }

        self.handle_tx_result(res);
    }

    pub fn mint(&self, delta: i64, seq: i64, sig_hex: String) {
        self.mint_burn(OP_MINT, delta, seq, sig_hex);
    }

    pub fn burn(&self, delta: i64, seq: i64, sig_hex: String) {
        self.mint_burn(OP_BURN, delta, seq, sig_hex);
    }

    pub fn send(&self, target_pub_key: String, delta: i64, seq: i64, sig_hex: String) {
        let Some(seq) = card_seq_to_u64(seq) else {
            return;
        };
        let ts = get_current_timestamp();
        let target_commit = target_for_commit("S", &target_pub_key);
        let Some(amount) = positive_i64_to_u64(delta) else {
            return;
        };
        let Some(goc) = get_last_goc(
            &self.repo_path,
            &self.public_key_id,
            "S",
            Some(&target_commit),
        )
        .checked_add(amount) else {
            return;
        };
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
            &goc.to_string(),
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
            &goc.to_string(),
            seq,
            ts,
            &raw_sig_hex,
        ));
    }
}

impl Ledger {
    fn mint_burn(&self, tx_type: u8, delta: i64, seq: i64, sig_hex: String) {
        let tx_t = match tx_type {
            OP_MINT => "M",
            OP_BURN => "B",
            _ => return,
        };

        let Some(seq) = card_seq_to_u64(seq) else {
            return;
        };
        let ts = get_current_timestamp();
        let Some(amount) = positive_i64_to_u64(delta) else {
            return;
        };
        let Some(goc) =
            get_last_goc(&self.repo_path, &self.public_key_id, tx_t, None).checked_add(amount)
        else {
            return;
        };
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
                message: format!("{tx_t} failed: missing public key. Seq={seq}, GoC={goc}"),
            });
            return;
        }

        if !verify_tx_signature(
            &self.public_key_full,
            tx_t,
            "",
            &goc.to_string(),
            seq,
            &raw_sig_hex,
        ) {
            self.handle_tx_result(GitResult {
                success: false,
                message: format!("{tx_t} signature verification failed. Seq={seq}, goc={goc}"),
            });
            return;
        }

        self.handle_tx_result(self.commit_internal(
            tx_t,
            "",
            &goc.to_string(),
            seq,
            ts,
            &raw_sig_hex,
        ));
    }

    fn handle_tx_result(&self, res: GitResult) {
        if !res.success {
            logging::init_logging();
            log::error!(target: "dole::ledger", "{}", res.message);
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
        seq: u64,
        ts: u64,
        sig: &str,
    ) -> GitResult {
        let _guard = self.repo_mutex.lock().unwrap();
        let target_commit = target_for_commit(tx_t, target);
        if branch_has_seq(&self.repo_path, &self.public_key_id, seq) {
            return GitResult {
                success: false,
                message: format!("{tx_t} failed: duplicate sequence {seq}"),
            };
        }
        let Some(recovery_id) =
            find_tx_recovery_id_for_pubkey_hex(&self.public_key_full, tx_t, target, goc, seq, sig)
        else {
            return GitResult {
                success: false,
                message: format!("{tx_t} failed: recovery-id unavailable for sequence {seq}"),
            };
        };

        match write_commit(
            &self.repo_path,
            CommitInput {
                branch: &self.public_key_id,
                tx_t,
                target: &target_commit,
                goc,
                seq,
                ts,
                sig,
                recovery_id,
            },
        ) {
            Ok(h) => {
                let msg = encode_tx_msg(tx_t, target, goc, seq, ts, sig, recovery_id);
                if let Some(msg) = msg {
                    if let Ok(guard) = GLOBAL_TX_SENDER.lock()
                        && let Some(tx) = guard.as_ref()
                    {
                        let _ = tx.send(OutboundMessage::Transactions(vec![msg]));
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
    let repo = gix::open(path).ok()?;
    let branch_ref = format!("refs/heads/{}", branch);
    let r = repo.find_reference(branch_ref.as_str()).ok()?;

    let mut current_id = Some(r.id().detach());
    while let Some(id) = current_id {
        let Ok(obj) = repo.find_object(id) else {
            break;
        };
        let Ok(c) = obj.try_into_commit() else {
            break;
        };
        if let Ok(decoded) = c.decode()
            && let Ok(author_sig) = decoded.author()
            && author_sig.name.to_str_lossy() == "G"
            && let Ok(committer_sig) = decoded.committer()
        {
            let public_key = author_sig.email.to_str_lossy();
            let cert = committer_sig.name.to_str_lossy();
            let sig = committer_sig.email.to_str_lossy();
            if verify_genesis_tx(public_key.as_ref(), sig.as_ref(), cert.as_ref()) {
                return Some(public_key.into_owned());
            }
        }
        current_id = c.parent_ids().next().map(|p| p.detach());
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

    let header = WireHeader::decode(r.read_u8()?)?;
    let tx_type = header.tx_type();
    let recovery_id = header.recovery_id();
    let ts_len = header.ts_len();
    tx_label_from_type(tx_type)?;

    if tx_type == OP_GENESIS {
        let sig = hex::encode_upper(r.read_bytes(WIRE_SIGNATURE_SIZE)?);
        let cert = hex::encode_upper(r.read_bytes(WIRE_SIGNATURE_SIZE)?);
        let ts = r.read_group_u64(ts_len)?;
        if !r.is_done() {
            return None;
        }
        let public_key = recover_genesis_pubkey_hex(&sig, &cert, recovery_id)?;
        let author_id = person_id_hex_from_id_or_pubkey_hex(&public_key)?;
        return Some(SyncTx {
            author_id,
            author_pubkey: public_key.clone(),
            tx_type,
            recovery_id,
            target: cert,
            payload: public_key,
            seq: 0,
            ts,
            sig,
        });
    }

    let prefix = WirePrefix::decode(r.read_u8()?)?;
    let seq = r.read_group_u64(prefix.seq_len())?;
    let goc = r.read_group_u64(prefix.goc_len())?;
    let ts = r.read_group_u64(ts_len)?;

    let (target, payload) = match tx_type {
        OP_MINT | OP_BURN => (String::new(), goc.to_string()),
        OP_SEND => (
            hex::encode_upper(r.read_bytes(WIRE_ID_SIZE)?),
            goc.to_string(),
        ),
        _ => return None,
    };

    let sig = hex::encode_upper(r.read_bytes(WIRE_SIGNATURE_SIZE)?);
    if !r.is_done() {
        return None;
    }
    let tx_t = tx_label_from_type(tx_type)?;
    let author_pubkey =
        recover_tx_pubkey_hex_with_recovery_id(tx_t, &target, &payload, seq, &sig, recovery_id)?;
    let author_id = person_id_hex_from_id_or_pubkey_hex(&author_pubkey)?;

    Some(SyncTx {
        author_id,
        author_pubkey,
        tx_type,
        recovery_id,
        target,
        payload,
        seq,
        ts,
        sig,
    })
}

fn positive_i64_to_u64(value: i64) -> Option<u64> {
    if value > 0 {
        u64::try_from(value).ok()
    } else {
        None
    }
}

fn card_seq_to_u64(value: i64) -> Option<u64> {
    u64::try_from(value).ok()
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

fn type_from_tx_label(tx_t: &str) -> Option<u8> {
    match tx_t {
        "G" => Some(OP_GENESIS),
        "M" => Some(OP_MINT),
        "B" => Some(OP_BURN),
        "S" => Some(OP_SEND),
        _ => None,
    }
}

fn tx_label_from_type(tx_type: u8) -> Option<&'static str> {
    match tx_type {
        OP_GENESIS => Some("G"),
        OP_MINT => Some("M"),
        OP_BURN => Some("B"),
        OP_SEND => Some("S"),
        _ => None,
    }
}

fn get_current_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn ensure_repo_initialized(repo_path: &PathBuf) {
    if gix::open(repo_path).is_err()
        && let Err(e) = gix::init(repo_path)
    {
        log::error!(target: "dole::ledger", "Failed to initialize local ledger repository: {e}");
        return;
    }

    let config_path = repo_path.join(".git").join("config");
    if let Ok(current_config) = std::fs::read_to_string(&config_path)
        && !current_config.contains("DOLE System")
    {
        let extra_config = "\n[user]\n\tname = DOLE System\n\temail = system@dole.local\n";
        if let Err(e) = std::fs::write(&config_path, current_config + extra_config) {
            log::warn!(target: "dole::ledger", "Failed to update local ledger git config: {e}");
        }
    }
}

fn get_last_goc(path: &PathBuf, branch: &str, tx_type: &str, target_filter: Option<&str>) -> u64 {
    let Ok(repo) = gix::open(path) else {
        return 0;
    };

    let branch_ref = format!("refs/heads/{}", branch);
    let Ok(r) = repo.find_reference(branch_ref.as_str()) else {
        return 0;
    };

    let mut latest: Option<(u64, u64)> = None;
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

        let Some(seq) = u64::try_from(author_sig.seconds()).ok() else {
            continue;
        };
        if let Ok(val) = author_sig.email.to_str_lossy().parse::<u64>()
            && latest.is_none_or(|(best_seq, _)| seq > best_seq)
        {
            latest = Some((seq, val));
        }
    }

    latest.map(|(_, val)| val).unwrap_or(0)
}

fn write_commit(path: &PathBuf, input: CommitInput<'_>) -> Result<String, String> {
    let CommitInput {
        branch,
        tx_t,
        target,
        goc,
        seq,
        ts,
        sig,
        recovery_id,
    } = input;
    let repo = gix::open(path).map_err(|e| e.to_string())?;
    let tx_type = type_from_tx_label(tx_t).ok_or_else(|| format!("invalid type: {tx_t}"))?;
    WireHeader::checked(tx_type, recovery_id, 0)
        .ok_or_else(|| format!("invalid recovery-id for tx type {tx_t}: {recovery_id}"))?;
    let seq_seconds =
        i64::try_from(seq).map_err(|_| format!("sequence is too large for git time: {seq}"))?;
    let ts_seconds =
        i64::try_from(ts).map_err(|_| format!("timestamp is too large for git time: {ts}"))?;

    let tree_id = repo
        .write_object(Tree::empty())
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
            seconds: seq_seconds as SecondsSinceUnixEpoch,
            offset: 0 as OffsetInSeconds,
        },
    };

    let committer = Signature {
        name: target.into(),
        email: sig.into(),
        time: Time {
            seconds: ts_seconds as SecondsSinceUnixEpoch,
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
        extra_headers: vec![(
            BString::from(RECOVERY_ID_HEADER.as_bytes().to_vec()),
            BString::from(recovery_id.to_string().into_bytes()),
        )],
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

    if let Ok(refs) = repo.references()
        && let Ok(branches) = refs.local_branches()
    {
        for b in branches.flatten() {
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
                            let Some(seq) = u64::try_from(author_sig.seconds()).ok() else {
                                curr = c.parent_ids().next().map(|p| p.detach());
                                continue;
                            };
                            let target = committer_sig.name.to_str_lossy().into_owned();
                            let ts = committer_sig.seconds();
                            let sig = committer_sig.email.to_str_lossy().into_owned();

                            let cum_val = if t == "G" {
                                0
                            } else {
                                payload_s.parse::<i64>().unwrap_or(0)
                            };

                            let is_own_branch = b_name.eq_ignore_ascii_case(my_key);
                            let is_incoming_send = t == "S" && target.eq_ignore_ascii_case(my_key);
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

fn get_latest_seq_for_branch(path: &PathBuf, branch: &str) -> Option<u64> {
    let Ok(repo) = gix::open(path) else {
        return None;
    };

    branch_sequences(&repo, branch).into_iter().max()
}

fn branch_has_seq(path: &PathBuf, branch: &str, seq: u64) -> bool {
    let Ok(repo) = gix::open(path) else {
        return false;
    };

    branch_sequences(&repo, branch)
        .into_iter()
        .any(|known| known == seq)
}

fn branch_tx_at_seq(path: &PathBuf, branch: &str, seq: u64) -> Option<KnownBranchTx> {
    let Ok(repo) = gix::open(path) else {
        return None;
    };
    let branch_ref = format!("refs/heads/{branch}");
    let Ok(reference) = repo.find_reference(branch_ref.as_str()) else {
        return None;
    };

    let mut curr = Some(reference.id().detach());
    while let Some(id) = curr {
        let Ok(obj) = repo.find_object(id) else {
            break;
        };
        let Ok(commit) = obj.try_into_commit() else {
            break;
        };
        curr = commit.parent_ids().next().map(|parent| parent.detach());

        let Ok(decoded) = commit.decode() else {
            continue;
        };
        let (Ok(author), Ok(committer)) = (decoded.author(), decoded.committer()) else {
            continue;
        };
        let Some(known_seq) = u64::try_from(author.seconds()).ok() else {
            continue;
        };

        if known_seq == seq {
            let recovery_id = decoded
                .extra_headers()
                .find(RECOVERY_ID_HEADER)
                .and_then(|value| value.to_str_lossy().as_ref().parse::<u8>().ok())
                .filter(|recovery_id| *recovery_id <= 3);
            return Some(KnownBranchTx {
                tx_t: author.name.to_str_lossy().into_owned(),
                target: committer.name.to_str_lossy().into_owned(),
                payload: author.email.to_str_lossy().into_owned(),
                sig: committer.email.to_str_lossy().into_owned(),
                recovery_id,
            });
        }
    }

    None
}

fn branch_sequences(repo: &gix::Repository, branch: &str) -> Vec<u64> {
    let branch_ref = format!("refs/heads/{branch}");
    let Ok(reference) = repo.find_reference(branch_ref.as_str()) else {
        return Vec::new();
    };

    let mut sequences = Vec::new();
    let mut curr = Some(reference.id().detach());
    while let Some(id) = curr {
        let Ok(obj) = repo.find_object(id) else {
            break;
        };
        let Ok(commit) = obj.try_into_commit() else {
            break;
        };

        curr = commit.parent_ids().next().map(|parent| parent.detach());

        let Ok(decoded) = commit.decode() else {
            continue;
        };
        let Ok(author) = decoded.author() else {
            continue;
        };

        let Some(seq) = u64::try_from(author.seconds()).ok() else {
            continue;
        };
        sequences.push(seq);
    }

    sequences
}

fn branch_sequences_from_path(path: &PathBuf, branch: &str) -> Vec<u64> {
    let Ok(repo) = gix::open(path) else {
        return Vec::new();
    };

    branch_sequences(&repo, branch)
}

fn log_sequence_position(author_id: &str, seq: u64, known_sequences: &[u64]) {
    let frontier = highest_contiguous_seq(known_sequences.to_vec());
    let expected = frontier
        .and_then(|frontier| frontier.checked_add(1))
        .unwrap_or(0);

    if seq > expected {
        log::info!(
            target: LEDGER_LOG_TARGET,
            "RX sync tx seq-gap author={} seq={} frontier={} expected={}",
            short_hex_label(author_id),
            seq,
            frontier
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".to_string()),
            expected
        );
    }

    if known_sequences
        .iter()
        .max()
        .is_some_and(|max_seq| seq < *max_seq)
    {
        log::info!(
            target: LEDGER_LOG_TARGET,
            "RX sync tx out-of-order author={} seq={}",
            short_hex_label(author_id),
            seq
        );
    }
}

fn highest_contiguous_seq(mut sequences: Vec<u64>) -> Option<u64> {
    sequences.sort_unstable();

    let mut expected = 0;
    let mut frontier = None;
    for seq in sequences {
        if seq < expected {
            continue;
        }
        if seq != expected {
            break;
        }

        frontier = Some(seq);
        if expected == u64::MAX {
            break;
        }
        expected += 1;
    }

    frontier
}

fn announce_frontier(path: &PathBuf, tx_sender: &mpsc::UnboundedSender<OutboundMessage>) {
    let mut entries: Vec<(String, u64)> = contiguous_sequences(path).into_iter().collect();
    entries.sort_by(|a, b| a.0.cmp(&b.0));

    let _ = tx_sender.send(OutboundMessage::Raw(network::encode_frontier_announcement(
        entries,
    )));
}

fn contiguous_sequences(path: &PathBuf) -> HashMap<String, u64> {
    let mut sequences = HashMap::new();
    let repo = if let Ok(r) = gix::open(path) {
        r
    } else {
        return sequences;
    };

    if let Ok(refs) = repo.references()
        && let Ok(branches) = refs.local_branches()
    {
        for b in branches.flatten() {
            let branch = b.name().shorten().to_string();
            if let Some(frontier) = highest_contiguous_seq(branch_sequences(&repo, &branch)) {
                sequences.insert(branch, frontier);
            }
        }
    }

    sequences
}

fn peer_is_ahead_of_local_frontier(path: &PathBuf, peer_sequences: &HashMap<String, u64>) -> bool {
    let local_sequences = contiguous_sequences(path);
    peer_sequences
        .iter()
        .any(|(branch, peer_seq)| match local_sequences.get(branch) {
            Some(local_seq) => *local_seq < *peer_seq,
            None => true,
        })
}

fn send_history_after_frontier(
    path: &PathBuf,
    tx_sender: &mpsc::UnboundedSender<OutboundMessage>,
    peer_sequences: &HashMap<String, u64>,
) {
    let repo = if let Ok(r) = gix::open(path) {
        r
    } else {
        log::warn!(target: "dole::ledger", "Cannot resend ledger history because local repository is not available");
        return;
    };

    let mut history = Vec::new();
    if let Ok(refs) = repo.references()
        && let Ok(branches) = refs.local_branches()
    {
        for b_res in branches.flatten() {
            let b_name = b_res.name().shorten().to_string();
            collect_branch_history_after(
                &repo,
                &b_name,
                peer_sequences.get(&b_name).copied(),
                &mut history,
            );
        }
    }

    history.sort_by(|a, b| {
        a.author_id
            .cmp(&b.author_id)
            .then_with(|| a.seq.cmp(&b.seq))
            .then_with(|| a.payload.cmp(&b.payload))
    });
    history.dedup_by(|a, b| a.payload == b.payload);

    let messages = history.into_iter().map(|tx| tx.payload).collect::<Vec<_>>();
    if messages.is_empty() {
        return;
    }

    log::debug!(
        target: "dole::ledger",
        "TX sync history-after-frontier txs={}",
        messages.len()
    );
    let _ = tx_sender.send(OutboundMessage::Transactions(messages));
}

fn collect_branch_history_after(
    repo: &gix::Repository,
    branch: &str,
    peer_frontier: Option<u64>,
    history: &mut Vec<HistoryTx>,
) {
    let branch_ref = format!("refs/heads/{branch}");
    let Ok(reference) = repo.find_reference(branch_ref.as_str()) else {
        return;
    };

    let mut curr = Some(reference.id().detach());
    while let Some(id) = curr {
        let Ok(obj) = repo.find_object(id) else {
            break;
        };
        let Ok(commit) = obj.try_into_commit() else {
            break;
        };
        let Ok(decoded) = commit.decode() else {
            break;
        };
        curr = commit.parent_ids().next().map(|parent| parent.detach());

        let (Ok(author), Ok(committer)) = (decoded.author(), decoded.committer()) else {
            continue;
        };

        let Some(seq) = u64::try_from(author.seconds()).ok() else {
            continue;
        };
        if peer_frontier.is_some_and(|frontier| seq <= frontier) {
            continue;
        }

        let tx_t = author.name.to_str_lossy();
        let goc = author.email.to_str_lossy();
        let target = committer.name.to_str_lossy();
        let ts = committer.seconds();
        let sig = committer.email.to_str_lossy();
        let Some(ts) = u64::try_from(ts).ok() else {
            continue;
        };
        let Some(recovery_id) = decoded
            .extra_headers()
            .find(RECOVERY_ID_HEADER)
            .and_then(|value| value.to_str_lossy().as_ref().parse::<u8>().ok())
            .filter(|recovery_id| *recovery_id <= 3)
        else {
            log::warn!(
                target: LEDGER_LOG_TARGET,
                "TX sync tx skipped reason=recovery-id author={} seq={}",
                short_hex_label(branch),
                seq
            );
            continue;
        };

        if let Some(payload) = encode_tx_msg(
            tx_t.as_ref(),
            target.as_ref(),
            goc.as_ref(),
            seq,
            ts,
            sig.as_ref(),
            recovery_id,
        ) {
            history.push(HistoryTx {
                author_id: branch.to_string(),
                seq,
                payload,
            });
        } else {
            log::warn!(target: "dole::ledger", "Skipped transaction that could not be encoded for sync");
        }
    }
}

struct HistoryTx {
    author_id: String,
    seq: u64,
    payload: Vec<u8>,
}

fn apply_sync_tx(path: &PathBuf, repo_mutex: &Arc<Mutex<()>>, sync_tx: SyncTx) {
    let Some(tx_t) = tx_label_from_type(sync_tx.tx_type) else {
        log::warn!(
            target: LEDGER_LOG_TARGET,
            "RX sync tx skipped reason=unknown-type type={} seq={}",
            sync_tx.tx_type,
            sync_tx.seq
        );
        return;
    };

    let (author_id, author_pub_full) = if sync_tx.tx_type == OP_GENESIS {
        (sync_tx.author_id.clone(), sync_tx.author_pubkey.clone())
    } else if let Some(author) = resolve_sync_author(path, &sync_tx) {
        author
    } else {
        log::warn!(
            target: LEDGER_LOG_TARGET,
            "RX sync tx skipped reason=genesis-missing type={} author={} seq={} target={} recovery-id={}",
            tx_t,
            short_hex_label(&sync_tx.author_id),
            sync_tx.seq,
            short_hex_label(&sync_tx.target),
            sync_tx.recovery_id
        );
        announce_frontier_after_unresolved_author(path);
        return;
    };

    let valid = if sync_tx.tx_type == OP_GENESIS {
        verify_genesis_tx(&author_pub_full, &sync_tx.sig, &sync_tx.target)
    } else {
        verify_tx_signature(
            &author_pub_full,
            tx_t,
            &sync_tx.target,
            &sync_tx.payload,
            sync_tx.seq,
            &sync_tx.sig,
        )
    };

    if !valid {
        log::warn!(
            target: LEDGER_LOG_TARGET,
            "RX sync tx skipped reason=recovery-id type={} author={} seq={} recovery-id={}",
            tx_t,
            short_hex_label(&author_id),
            sync_tx.seq,
            sync_tx.recovery_id
        );
        return;
    }

    let _guard = repo_mutex.lock().unwrap();
    let target_commit = target_for_commit(tx_t, &sync_tx.target);
    let sequences_before = branch_sequences_from_path(path, &author_id);
    if let Some(existing_tx) = branch_tx_at_seq(path, &author_id, sync_tx.seq) {
        if existing_tx.matches_sync_tx(
            tx_t,
            &target_commit,
            &sync_tx.payload,
            &sync_tx.sig,
            sync_tx.recovery_id,
        ) {
            log::debug!(
                target: LEDGER_LOG_TARGET,
                "RX sync tx skipped reason=duplicate type={} author={} seq={}",
                tx_t,
                short_hex_label(&author_id),
                sync_tx.seq
            );
            return;
        }

        log::error!(
            target: LEDGER_LOG_TARGET,
            "RX sync tx skipped reason=conflict type={} author={} seq={} existingType={} target={} existingTarget={} payload={} existingPayload={} signature={} existingSignature={} recovery-id={} existing-recovery-id={}",
            tx_t,
            short_hex_label(&author_id),
            sync_tx.seq,
            existing_tx.tx_t,
            short_hex_label(&target_commit),
            short_hex_label(&existing_tx.target),
            short_hex_label(&sync_tx.payload),
            short_hex_label(&existing_tx.payload),
            short_hex_label(&sync_tx.sig),
            short_hex_label(&existing_tx.sig),
            sync_tx.recovery_id,
            existing_tx
                .recovery_id
                .map(|value| value.to_string())
                .unwrap_or_else(|| "missing".to_string())
        );
        return;
    }
    log_sequence_position(&author_id, sync_tx.seq, &sequences_before);

    let commit_id = match write_commit(
        path,
        CommitInput {
            branch: &author_id,
            tx_t,
            target: &target_commit,
            goc: &sync_tx.payload,
            seq: sync_tx.seq,
            ts: sync_tx.ts,
            sig: &sync_tx.sig,
            recovery_id: sync_tx.recovery_id,
        },
    ) {
        Ok(commit_id) => commit_id,
        Err(e) => {
            log::warn!(
                target: LEDGER_LOG_TARGET,
                "RX sync tx skipped reason=write-failed type={} author={} seq={} error={}",
                tx_t,
                short_hex_label(&author_id),
                sync_tx.seq,
                e
            );
            return;
        }
    };

    log::info!(
        target: LEDGER_LOG_TARGET,
        "RX sync tx applied type={} author={} seq={} commit={}",
        tx_t,
        short_hex_label(&author_id),
        sync_tx.seq,
        short_hex_label(&commit_id)
    );

    if let Some(msg) = encode_tx_msg(
        tx_t,
        &sync_tx.target,
        &sync_tx.payload,
        sync_tx.seq,
        sync_tx.ts,
        &sync_tx.sig,
        sync_tx.recovery_id,
    ) && let Ok(guard) = GLOBAL_TX_SENDER.lock()
        && let Some(tx) = guard.as_ref()
    {
        let _ = tx.send(OutboundMessage::Transactions(vec![msg]));
    }

    if let Ok(listener_guard) = ACTIVE_LISTENER.lock()
        && let Some((active_pub_id, listener)) = listener_guard.as_ref()
    {
        notify_ui_internal(path, active_pub_id, listener);
    }
}

fn announce_frontier_after_unresolved_author(path: &PathBuf) {
    let now = std::time::Instant::now();
    let Ok(mut last_announcement) = LAST_UNRESOLVED_AUTHOR_FRONTIER_ANNOUNCEMENT.lock() else {
        return;
    };

    if last_announcement
        .is_some_and(|last| now.duration_since(last) < std::time::Duration::from_secs(2))
    {
        return;
    }
    *last_announcement = Some(now);

    if let Ok(guard) = GLOBAL_TX_SENDER.lock()
        && let Some(tx_sender) = guard.as_ref()
    {
        announce_frontier(path, tx_sender);
    }
}

fn resolve_sync_author(path: &PathBuf, sync_tx: &SyncTx) -> Option<(String, String)> {
    let author_id = person_id_hex_from_id_or_pubkey_hex(&sync_tx.author_pubkey)?;
    if !author_id.eq_ignore_ascii_case(&sync_tx.author_id) {
        return None;
    }

    if let Some(stored_pubkey) = get_pubkey_from_genesis(path, &author_id)
        && stored_pubkey.eq_ignore_ascii_case(&sync_tx.author_pubkey)
    {
        return Some((author_id, stored_pubkey));
    }

    None
}

fn short_payload_hex(payload: &[u8]) -> String {
    hex::encode_upper(&payload[..payload.len().min(12)])
}

fn short_hex_label(value: &str) -> String {
    value.chars().take(12).collect()
}