mod history;
mod replication;
mod repo;

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::mpsc;

use crate::constants::{OP_BURN, OP_MINT};
use crate::crypto::{
    find_genesis_recovery_id, find_tx_recovery_id_for_pubkey_hex,
    person_id_hex_from_id_or_pubkey_hex, signature_hex_to_raw_hex, verify_tx_signature,
};
use crate::logging;
use crate::network::{self, OutboundMessage};

use crate::sync::{
    decode_frontier_announcement, decode_transaction_batch, decode_tx_message, encode_tx_msg,
};
use history::notify_ui_internal;
use replication::{
    announce_frontier, apply_sync_txs, peer_is_ahead_of_local_frontier,
    send_history_after_frontier, short_payload_hex,
};
use repo::{
    CommitInput, branch_has_seq, contiguous_sequences, ensure_repo_initialized,
    get_latest_seq_for_branch, get_pubkey_from_genesis, target_for_commit, write_commit,
};

pub(super) const LEDGER_LOG_TARGET: &str = "dole::ledger";

pub(super) static ACTIVE_LISTENER: Mutex<Option<(String, Arc<dyn LedgerStateListener>)>> =
    Mutex::new(None);
pub(super) static GLOBAL_TX_SENDER: Mutex<Option<mpsc::UnboundedSender<OutboundMessage>>> =
    Mutex::new(None);
pub(super) static REPO_MUTEX: Mutex<()> = Mutex::new(());
static GLOBAL_SHUTDOWN_TX: Mutex<Option<mpsc::UnboundedSender<()>>> = Mutex::new(None);
static SYNC_EPOCH: AtomicU64 = AtomicU64::new(0);

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
            if let Ok(repo) = gix::open(&frontier_repo_path) {
                announce_frontier(&repo, &frontier_sync_tx);
            }
            #[cfg(feature = "bench")]
            if let Some(payload) = crate::bench::pending_probe_payload() {
                let _ = frontier_sync_tx.send(OutboundMessage::Transactions(vec![payload]));
            }
        }
    });

    thread::spawn(move || {
        let mut epoch = SYNC_EPOCH.load(Ordering::Relaxed);
        while let Some(msg) = in_rx.blocking_recv() {
            let current_epoch = SYNC_EPOCH.load(Ordering::Relaxed);
            if current_epoch != epoch {
                epoch = current_epoch;
                let mut dropped = 1;
                while in_rx.try_recv().is_ok() {
                    dropped += 1;
                }
                log::info!(target: LEDGER_LOG_TARGET, "RX sync backlog discarded reason=ledger-reset messages={dropped}");
                continue;
            }

            let Ok(repo) = gix::open(&repo_path) else {
                log::warn!(target: LEDGER_LOG_TARGET, "RX sync skipped reason=repo-unavailable");
                continue;
            };

            if let Some(peer_sequences) = decode_frontier_announcement(&msg) {
                let local_sequences = contiguous_sequences(&repo);
                let should_announce_back =
                    peer_is_ahead_of_local_frontier(&local_sequences, &peer_sequences);
                if !bench_history_sync_paused() {
                    send_history_after_frontier(&repo, &sync_tx, &peer_sequences);
                }
                if should_announce_back {
                    announce_frontier(&repo, &sync_tx);
                }
                continue;
            }

            if let Some(tx_messages) = decode_transaction_batch(&msg) {
                let sync_txs = tx_messages
                    .iter()
                    .filter_map(|tx_msg| {
                        let decoded = decode_tx_message(tx_msg);
                        if decoded.is_none() {
                            log::warn!(
                                target: LEDGER_LOG_TARGET,
                                "RX sync tx skipped reason=decode-failed bytes={} head={}",
                                tx_msg.len(),
                                short_payload_hex(tx_msg)
                            );
                        }
                        decoded
                    })
                    .collect();
                let sync_txs = bench_take_latency_echo(sync_txs);
                apply_sync_txs(&repo, sync_txs);
                continue;
            }

            if let Some(sync_tx) = decode_tx_message(&msg) {
                let sync_txs = bench_take_latency_echo(vec![sync_tx]);
                apply_sync_txs(&repo, sync_txs);
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

fn bench_history_sync_paused() -> bool {
    #[cfg(feature = "bench")]
    {
        crate::bench::is_benchmark_mode()
    }
    #[cfg(not(feature = "bench"))]
    {
        false
    }
}

fn bench_take_latency_echo(sync_txs: Vec<crate::sync::SyncTx>) -> Vec<crate::sync::SyncTx> {
    #[cfg(feature = "bench")]
    {
        if !crate::bench::latency_pending() {
            return sync_txs;
        }
        sync_txs
            .into_iter()
            .filter(|sync_tx| !crate::bench::complete_latency(&sync_tx.sig, sync_tx.ts))
            .collect()
    }
    #[cfg(not(feature = "bench"))]
    sync_txs
}

#[uniffi::export]
pub fn reset_ledger(storage_path: String) -> bool {
    logging::init_logging();
    SYNC_EPOCH.fetch_add(1, Ordering::Relaxed);
    network::reset_sync_state();

    let repo_path = PathBuf::from(&storage_path).join("dole_ledger");
    let _guard = REPO_MUTEX.lock().unwrap();

    if repo_path.exists()
        && let Err(e) = std::fs::remove_dir_all(&repo_path)
    {
        log::error!(target: LEDGER_LOG_TARGET, "Failed to delete local ledger: {e}");
        return false;
    }

    ensure_repo_initialized(&repo_path);
    let cleared = gix::open(&repo_path).is_ok();
    cleared
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
        let repo = gix::open(&repo_path).ok();

        let resolved_pubkey = if public_key_full.is_empty() {
            repo.as_ref()
                .and_then(|repo| get_pubkey_from_genesis(repo, &public_key_id))
                .unwrap_or_default()
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
            repo_path,
        };

        if let Some(repo) = repo.as_ref() {
            notify_ui_internal(repo, &public_key_id, &listener_arc);
        }
        engine
    }

    pub fn shutdown(&self) {
        if let Ok(mut guard) = ACTIVE_LISTENER.lock() {
            *guard = None;
        }
    }

    pub fn genesis(&self, sig_hex: String, cert_hex: String) {
        let Ok(repo) = gix::open(&self.repo_path) else {
            self.fail("Genesis failed: ledger repository unavailable".into());
            return;
        };
        if get_latest_seq_for_branch(&repo, &self.public_key_id).is_some() {
            return;
        }

        let ts = get_current_timestamp();
        let Some(raw_sig_hex) = signature_hex_to_raw_hex(&sig_hex) else {
            self.fail("Genesis signature format invalid".into());
            return;
        };
        let Some(raw_cert_hex) = signature_hex_to_raw_hex(&cert_hex) else {
            self.fail("Certificate signature format invalid".into());
            return;
        };

        let Some((recovered_pubkey_hex, recovery_id)) =
            find_genesis_recovery_id(&raw_sig_hex, &raw_cert_hex)
        else {
            self.fail("Genesis public key recovery failed".into());
            return;
        };
        let Some(author_id) = person_id_hex_from_id_or_pubkey_hex(&recovered_pubkey_hex) else {
            self.fail("Genesis recovered public key is invalid".into());
            return;
        };

        if !author_id.eq_ignore_ascii_case(&self.public_key_id) {
            self.fail("Genesis recovered public key does not match the active account".into());
            return;
        }

        let res = {
            let _guard = REPO_MUTEX.lock().unwrap();
            write_commit(
                &repo,
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
            })
        };

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

        let _ = self.handle_tx_result(&repo, res);
    }

    pub fn mint(&self, goc: i64, seq: i64, sig_hex: String) -> bool {
        self.mint_burn(OP_MINT, goc, seq, sig_hex)
    }

    pub fn burn(&self, goc: i64, seq: i64, sig_hex: String) -> bool {
        self.mint_burn(OP_BURN, goc, seq, sig_hex)
    }

    pub fn send(&self, target_pub_key: String, goc: i64, seq: i64, sig_hex: String) -> bool {
        let Some(seq) = card_seq_to_u64(seq) else {
            return false;
        };
        let Some(goc) = positive_i64_to_u64(goc) else {
            return false;
        };
        let Ok(repo) = gix::open(&self.repo_path) else {
            self.fail("Send failed: ledger repository unavailable".into());
            return false;
        };
        let Some(raw_sig_hex) = signature_hex_to_raw_hex(&sig_hex) else {
            self.fail("Send signature format invalid".into());
            return false;
        };

        if !verify_tx_signature(
            &self.public_key_full,
            "S",
            &target_pub_key,
            &goc.to_string(),
            seq,
            &raw_sig_hex,
        ) {
            self.fail("Send signature verification failed".into());
            return false;
        }

        let res = self.commit_internal(
            &repo,
            "S",
            &target_pub_key,
            &goc.to_string(),
            seq,
            &raw_sig_hex,
        );
        self.handle_tx_result(&repo, res)
    }
}

impl Ledger {
    fn mint_burn(&self, tx_type: u8, goc: i64, seq: i64, sig_hex: String) -> bool {
        let tx_t = match tx_type {
            OP_MINT => "M",
            OP_BURN => "B",
            _ => return false,
        };

        let Some(seq) = card_seq_to_u64(seq) else {
            return false;
        };
        let Some(goc) = positive_i64_to_u64(goc) else {
            return false;
        };
        let Ok(repo) = gix::open(&self.repo_path) else {
            self.fail(format!("{tx_t} failed: ledger repository unavailable"));
            return false;
        };
        let Some(raw_sig_hex) = signature_hex_to_raw_hex(&sig_hex) else {
            self.fail(format!("{tx_t} signature format invalid"));
            return false;
        };

        if self.public_key_full.is_empty() {
            self.fail(format!(
                "{tx_t} failed: missing public key. Seq={seq}, GoC={goc}"
            ));
            return false;
        }

        if !verify_tx_signature(
            &self.public_key_full,
            tx_t,
            "",
            &goc.to_string(),
            seq,
            &raw_sig_hex,
        ) {
            self.fail(format!(
                "{tx_t} signature verification failed. Seq={seq}, goc={goc}"
            ));
            return false;
        }

        let res = self.commit_internal(&repo, tx_t, "", &goc.to_string(), seq, &raw_sig_hex);
        self.handle_tx_result(&repo, res)
    }

    fn fail(&self, message: String) {
        logging::init_logging();
        log::error!(target: LEDGER_LOG_TARGET, "{message}");
    }

    fn handle_tx_result(&self, repo: &gix::Repository, res: GitResult) -> bool {
        if !res.success {
            self.fail(res.message);
            return false;
        }
        if let Some(l) = &self.listener {
            notify_ui_internal(repo, &self.public_key_id, l);
        }
        true
    }

    fn commit_internal(
        &self,
        repo: &gix::Repository,
        tx_t: &str,
        target: &str,
        goc: &str,
        seq: u64,
        sig: &str,
    ) -> GitResult {
        let ts = get_current_timestamp();
        let _guard = REPO_MUTEX.lock().unwrap();
        let target_commit = target_for_commit(tx_t, target);
        if branch_has_seq(repo, &self.public_key_id, seq) {
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
            repo,
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

fn get_current_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default()
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

#[cfg(feature = "bench-workload")]
pub(crate) fn bench_apply_sync_txs(
    repo_path: &PathBuf,
    sync_txs: Vec<crate::sync::SyncTx>,
) -> bool {
    ensure_repo_initialized(repo_path);
    let Ok(repo) = gix::open(repo_path) else {
        return false;
    };
    apply_sync_txs(&repo, sync_txs);
    true
}

#[cfg(feature = "bench")]
pub(crate) fn bench_send_commit_at(
    repo_path: &PathBuf,
    index: usize,
) -> Option<(String, Vec<u8>, f64, usize)> {
    let watch = crate::bench::Stopwatch::start();
    let repo = gix::open(repo_path).ok()?;
    let (payload, total) = replication::bench_payload_at(&repo, index)?;
    let signature = decode_tx_message(&payload)?.sig;

    let sender = GLOBAL_TX_SENDER.lock().ok()?.as_ref()?.clone();
    let internal_ms = watch.elapsed_ms();
    sender
        .send(OutboundMessage::Transactions(vec![payload.clone()]))
        .ok()?;
    Some((signature, payload, internal_ms, total))
}
