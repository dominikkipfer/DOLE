mod session;
mod workload;

use std::path::PathBuf;

use crate::logging;

pub use session::{
    Stopwatch, abandon_latency, complete_latency, is_benchmark_mode, is_injecting, latency_pending,
    pending_probe_payload,
};
#[cfg(feature = "bench-workload")]
pub use workload::{BenchTx, generate_workload};
pub use workload::{
    BenchTxKind, Summary, WORKLOAD_BURN_COUNT, WORKLOAD_GENESIS_COUNT, WORKLOAD_MINT_COUNT,
    WORKLOAD_SEND_COUNT, WORKLOAD_START_TIMESTAMP_SECS, WORKLOAD_TIMESTAMP_STEP_SECS,
    WORKLOAD_TOTAL, directory_size_bytes,
};

pub const BENCH_LOG_TARGET: &str = "dole::bench";

#[cfg(feature = "bench-workload")]
const SCRATCH_REPO: &str = "dole_bench_store";

fn note(message: &str) {
    log::info!(target: BENCH_LOG_TARGET, "{}", message);
}

#[uniffi::export]
pub fn bench_set_mode(enabled: bool) {
    logging::init_logging();
    session::set_benchmark_mode(enabled);
    session::reset_latency_index();
    if !enabled {
        session::abandon_latency();
    }
    note(if enabled {
        "benchmark mode on"
    } else {
        "benchmark mode off"
    });
}

#[uniffi::export]
pub fn bench_mode_enabled() -> bool {
    session::is_benchmark_mode()
}

#[uniffi::export]
pub fn bench_latency_report() -> Option<String> {
    session::take_latency_report()
}

#[uniffi::export]
pub fn bench_run_latency(storage_path: String) -> bool {
    logging::init_logging();

    if session::latency_pending() {
        note("probe timed out, restarting");
        session::abandon_latency();
    }

    let repo_path = PathBuf::from(&storage_path).join("dole_ledger");
    let index = session::latency_index();
    match crate::ledger::bench_send_commit_at(&repo_path, index) {
        Some((signature, payload, internal_ms, total)) => {
            note(&format!("latency commit {} of {} sent", index + 1, total));
            session::arm_latency(signature, payload, internal_ms);
            session::advance_latency_index(total);
            true
        }
        None => {
            note("latency needs a workload");
            false
        }
    }
}

#[cfg(feature = "bench-workload")]
#[uniffi::export]
pub fn bench_generate_workload(storage_path: String) -> u32 {
    use crate::sync::decode_tx_message;

    logging::init_logging();
    note("workload generating");
    let repo_path = PathBuf::from(&storage_path).join("dole_ledger");

    let txs = generate_workload();
    let sync_txs = txs
        .iter()
        .filter_map(|tx| decode_tx_message(&tx.wire))
        .collect::<Vec<_>>();
    let injected = sync_txs.len();

    session::set_injecting(true);
    crate::ledger::bench_apply_sync_txs(&repo_path, sync_txs);
    session::set_injecting(false);

    note(&format!("workload finished {injected}"));
    injected as u32
}

#[cfg(feature = "bench-workload")]
#[uniffi::export]
pub fn bench_run_store(storage_path: String) -> u32 {
    use crate::sync::decode_tx_message;
    use workload::StoreGrowth;

    logging::init_logging();
    let scratch = PathBuf::from(&storage_path).join(SCRATCH_REPO);
    let _ = std::fs::remove_dir_all(&scratch);

    let txs = generate_workload();
    let mut growth = StoreGrowth::new();

    note("tx_index,bytes");
    session::set_injecting(true);
    let mut applied = 0;
    for tx in &txs {
        let Some(sync_tx) = decode_tx_message(&tx.wire) else {
            continue;
        };
        if !crate::ledger::bench_apply_sync_txs(&scratch, vec![sync_tx]) {
            continue;
        }
        applied += 1;
        let sample = growth.sample(applied, &scratch);
        note(&format!("{},{}", applied, sample.bytes));
    }
    session::set_injecting(false);

    let _ = std::fs::remove_dir_all(&scratch);
    applied as u32
}

#[cfg(feature = "bench-workload")]
pub fn wire_size_stats(txs: &[BenchTx]) -> Option<Summary> {
    workload::size_stats(&txs.iter().map(BenchTx::wire_len).collect::<Vec<_>>())
}

#[cfg(feature = "bench-workload")]
pub fn wire_size_stats_for_kind(txs: &[BenchTx], kind: BenchTxKind) -> Option<Summary> {
    let sizes = txs
        .iter()
        .filter(|tx| tx.kind == kind)
        .map(BenchTx::wire_len)
        .collect::<Vec<_>>();
    workload::size_stats(&sizes)
}

#[cfg(all(test, feature = "bench-workload"))]
mod tests;
