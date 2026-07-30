use gix::bstr::ByteSlice;
use std::collections::HashMap;
use std::ops::ControlFlow;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;

use crate::constants::OP_GENESIS;
use crate::crypto::{person_id_hex_from_id_or_pubkey_hex, verify_genesis_tx, verify_tx_signature};
use crate::network::OutboundMessage;
use crate::sync::{SyncTx, encode_tx_msg, tx_label_from_type};

use super::history::notify_ui_internal;
use super::repo::{
    CommitInput, branch_sequences_and_tx_at, contiguous_sequences, for_each_commit,
    get_pubkey_from_genesis, recovery_id_from_commit, target_for_commit, write_commit,
};
use super::{ACTIVE_LISTENER, GLOBAL_TX_SENDER, LEDGER_LOG_TARGET, REPO_MUTEX};

static LAST_UNRESOLVED_AUTHOR_FRONTIER_ANNOUNCEMENT: Mutex<Option<Instant>> = Mutex::new(None);

struct HistoryTx {
    seq: u64,
    payload: Vec<u8>,
}

pub(super) fn announce_frontier(
    repo: &gix::Repository,
    tx_sender: &mpsc::UnboundedSender<OutboundMessage>,
) {
    let mut entries: Vec<(String, u64)> = contiguous_sequences(repo).into_iter().collect();
    entries.sort_by(|a, b| a.0.cmp(&b.0));

    let _ = tx_sender.send(OutboundMessage::Frontier(entries));
}

pub(super) fn peer_is_ahead_of_local_frontier(
    local_sequences: &HashMap<String, u64>,
    peer_sequences: &HashMap<String, u64>,
) -> bool {
    peer_sequences
        .iter()
        .any(|(branch, peer_seq)| match local_sequences.get(branch) {
            Some(local_seq) => *local_seq < *peer_seq,
            None => true,
        })
}

pub(super) fn send_history_after_frontier(
    repo: &gix::Repository,
    tx_sender: &mpsc::UnboundedSender<OutboundMessage>,
    peer_sequences: &HashMap<String, u64>,
) {
    let branches = history_by_branch(repo, peer_sequences);
    if branches.is_empty() {
        return;
    }

    let messages = oldest_first_interleaved(branches);
    if messages.is_empty() {
        return;
    }

    let _ = tx_sender.send(OutboundMessage::History(messages));
}

fn history_by_branch(
    repo: &gix::Repository,
    peer_sequences: &HashMap<String, u64>,
) -> Vec<(String, Vec<HistoryTx>)> {
    let mut branches = Vec::new();
    if let Ok(refs) = repo.references()
        && let Ok(local_branches) = refs.local_branches()
    {
        for b_res in local_branches.flatten() {
            let b_name = b_res.name().shorten().to_string();
            let mut history = Vec::new();
            collect_branch_history_after(
                repo,
                &b_name,
                peer_sequences.get(&b_name).copied(),
                &mut history,
            );
            if history.is_empty() {
                continue;
            }

            history.sort_by(|a, b| a.seq.cmp(&b.seq).then_with(|| a.payload.cmp(&b.payload)));
            history.dedup_by(|a, b| a.payload == b.payload);
            branches.push((b_name, history));
        }
    }

    branches.sort_by(|a, b| a.0.cmp(&b.0));
    branches
}

fn oldest_first_interleaved(mut branches: Vec<(String, Vec<HistoryTx>)>) -> Vec<Vec<u8>> {
    let history_depth = branches
        .iter()
        .map(|(_, history)| history.len())
        .max()
        .unwrap_or(0);
    let mut messages = Vec::new();
    for index in 0..history_depth {
        for (_, history) in branches.iter_mut() {
            if let Some(tx) = history.get_mut(index) {
                messages.push(std::mem::take(&mut tx.payload));
            }
        }
    }

    messages
}

#[cfg(feature = "bench")]
pub(super) fn bench_payload_at(repo: &gix::Repository, index: usize) -> Option<(Vec<u8>, usize)> {
    let payloads = history_by_branch(repo, &HashMap::new())
        .into_iter()
        .flat_map(|(_, history)| history.into_iter().map(|tx| tx.payload))
        .collect::<Vec<_>>();
    if payloads.is_empty() {
        return None;
    }

    let total = payloads.len();
    payloads
        .into_iter()
        .nth(index % total)
        .map(|payload| (payload, total))
}

fn collect_branch_history_after(
    repo: &gix::Repository,
    branch: &str,
    peer_frontier: Option<u64>,
    history: &mut Vec<HistoryTx>,
) {
    for_each_commit(repo, branch, |_id, commit| {
        let Ok(decoded) = commit.decode() else {
            return ControlFlow::Break(());
        };
        let (Ok(author), Ok(committer)) = (decoded.author(), decoded.committer()) else {
            return ControlFlow::Continue(());
        };
        let Ok(seq) = u64::try_from(author.seconds()) else {
            return ControlFlow::Continue(());
        };
        if peer_frontier.is_some_and(|frontier| seq <= frontier) {
            return ControlFlow::Continue(());
        }

        let tx_t = author.name.to_str_lossy();
        let goc = author.email.to_str_lossy();
        let target = committer.name.to_str_lossy();
        let Ok(ts) = u64::try_from(committer.seconds()) else {
            return ControlFlow::Continue(());
        };
        let sig = committer.email.to_str_lossy();
        let Some(recovery_id) = recovery_id_from_commit(&decoded) else {
            log::warn!(
                target: LEDGER_LOG_TARGET,
                "TX sync tx skipped reason=recovery-id author={} seq={}",
                short_hex_label(branch),
                seq
            );
            return ControlFlow::Continue(());
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
            history.push(HistoryTx { seq, payload });
        } else {
            log::warn!(target: "dole::ledger", "Skipped transaction that could not be encoded for sync");
        }
        ControlFlow::Continue(())
    });
}

pub(super) fn apply_sync_txs(repo: &gix::Repository, sync_txs: Vec<SyncTx>) {
    let mut rebroadcast = Vec::new();
    for sync_tx in sync_txs {
        if let Some(msg) = apply_sync_tx(repo, sync_tx) {
            rebroadcast.push(msg);
        }
    }
    if rebroadcast.is_empty() {
        return;
    }

    if !bench_injecting()
        && let Ok(guard) = GLOBAL_TX_SENDER.lock()
        && let Some(tx) = guard.as_ref()
    {
        let _ = tx.send(OutboundMessage::Transactions(rebroadcast));
        if !super::bench_history_sync_paused() {
            announce_frontier(repo, tx);
        }
    }

    if let Ok(listener_guard) = ACTIVE_LISTENER.lock()
        && let Some((active_pub_id, listener)) = listener_guard.as_ref()
    {
        notify_ui_internal(repo, active_pub_id, listener);
    }
}

#[cfg(feature = "bench")]
const BENCH_REPLY_TS_MAX: u64 = 1_000_000_000;

#[cfg(feature = "bench")]
fn is_bench_reply(ts: u64) -> bool {
    ts < BENCH_REPLY_TS_MAX
}

#[cfg(feature = "bench")]
fn bench_reply_ts(watch: &crate::bench::Stopwatch) -> u64 {
    ((watch.elapsed_ms() * 1000.0).round().max(1.0) as u64).min(BENCH_REPLY_TS_MAX - 1)
}

fn bench_injecting() -> bool {
    #[cfg(feature = "bench")]
    {
        crate::bench::is_injecting()
    }
    #[cfg(not(feature = "bench"))]
    {
        false
    }
}

fn apply_sync_tx(repo: &gix::Repository, sync_tx: SyncTx) -> Option<Vec<u8>> {
    let Some(tx_t) = tx_label_from_type(sync_tx.tx_type) else {
        log::warn!(
            target: LEDGER_LOG_TARGET,
            "RX sync tx skipped reason=unknown-type type={} seq={}",
            sync_tx.tx_type,
            sync_tx.seq
        );
        return None;
    };

    let (author_id, author_pub_full) = if sync_tx.tx_type == OP_GENESIS {
        (sync_tx.author_id.clone(), sync_tx.author_pubkey.clone())
    } else if let Some(author) = resolve_sync_author(repo, &sync_tx) {
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
        announce_frontier_after_unresolved_author(repo);
        return None;
    };

    #[cfg(feature = "bench")]
    let inbound_watch = crate::bench::Stopwatch::start();

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
        return None;
    }

    #[cfg(feature = "bench")]
    if crate::bench::is_benchmark_mode() && is_bench_reply(sync_tx.ts) {
        return None;
    }

    let _guard = REPO_MUTEX.lock().unwrap();
    let target_commit = target_for_commit(tx_t, &sync_tx.target);
    let (_, existing_tx) = branch_sequences_and_tx_at(repo, &author_id, sync_tx.seq);
    if let Some(existing_tx) = existing_tx {
        if existing_tx.matches_sync_tx(
            tx_t,
            &target_commit,
            &sync_tx.payload,
            &sync_tx.sig,
            sync_tx.recovery_id,
        ) {
            #[cfg(feature = "bench")]
            if crate::bench::is_benchmark_mode()
                && !crate::bench::is_injecting()
                && !is_bench_reply(sync_tx.ts)
            {
                return encode_tx_msg(
                    tx_t,
                    &sync_tx.target,
                    &sync_tx.payload,
                    sync_tx.seq,
                    bench_reply_ts(&inbound_watch),
                    &sync_tx.sig,
                    sync_tx.recovery_id,
                );
            }

            return None;
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
            existing_tx.recovery_id.map(|value| value.to_string()).unwrap_or_else(|| "missing".to_string())
        );
        return None;
    }

    let commit_result = write_commit(
        repo,
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
    );

    match commit_result {
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
            return None;
        }
    };

    #[cfg(feature = "bench")]
    let reply_ts = if crate::bench::is_benchmark_mode() && !crate::bench::is_injecting() {
        bench_reply_ts(&inbound_watch)
    } else {
        sync_tx.ts
    };
    #[cfg(not(feature = "bench"))]
    let reply_ts = sync_tx.ts;

    encode_tx_msg(
        tx_t,
        &sync_tx.target,
        &sync_tx.payload,
        sync_tx.seq,
        reply_ts,
        &sync_tx.sig,
        sync_tx.recovery_id,
    )
}

fn announce_frontier_after_unresolved_author(repo: &gix::Repository) {
    let now = Instant::now();
    let Ok(mut last_announcement) = LAST_UNRESOLVED_AUTHOR_FRONTIER_ANNOUNCEMENT.lock() else {
        return;
    };

    if last_announcement.is_some_and(|last| now.duration_since(last) < Duration::from_secs(2)) {
        return;
    }
    *last_announcement = Some(now);

    if let Ok(guard) = GLOBAL_TX_SENDER.lock()
        && let Some(tx_sender) = guard.as_ref()
    {
        announce_frontier(repo, tx_sender);
    }
}

fn resolve_sync_author(repo: &gix::Repository, sync_tx: &SyncTx) -> Option<(String, String)> {
    let author_id = person_id_hex_from_id_or_pubkey_hex(&sync_tx.author_pubkey)?;
    if !author_id.eq_ignore_ascii_case(&sync_tx.author_id) {
        return None;
    }

    if let Some(stored_pubkey) = get_pubkey_from_genesis(repo, &author_id)
        && stored_pubkey.eq_ignore_ascii_case(&sync_tx.author_pubkey)
    {
        return Some((author_id, stored_pubkey));
    }

    None
}

pub(super) fn short_payload_hex(payload: &[u8]) -> String {
    hex::encode_upper(&payload[..payload.len().min(12)])
}

fn short_hex_label(value: &str) -> String {
    value.chars().take(12).collect()
}
