use super::session;
use super::workload::StoreGrowth;
use super::*;
use crate::crypto::{verify_genesis_tx, verify_tx_signature};
use crate::sync::decode_tx_message;

fn workload() -> Vec<BenchTx> {
    generate_workload()
}

#[test]
fn workload_has_the_documented_mix() {
    let txs = workload();
    assert_eq!(txs.len(), WORKLOAD_TOTAL);
    assert_eq!(txs.len(), 1000);

    let count = |kind: BenchTxKind| txs.iter().filter(|tx| tx.kind == kind).count();
    assert_eq!(count(BenchTxKind::Genesis), WORKLOAD_GENESIS_COUNT);
    assert_eq!(count(BenchTxKind::Mint), WORKLOAD_MINT_COUNT);
    assert_eq!(count(BenchTxKind::Burn), WORKLOAD_BURN_COUNT);
    assert_eq!(count(BenchTxKind::Send), WORKLOAD_SEND_COUNT);
}

#[test]
fn genesis_transactions_come_first() {
    let txs = workload();
    for tx in txs.iter().take(WORKLOAD_GENESIS_COUNT) {
        assert_eq!(tx.kind, BenchTxKind::Genesis);
    }
    for tx in txs.iter().skip(WORKLOAD_GENESIS_COUNT) {
        assert_ne!(tx.kind, BenchTxKind::Genesis);
    }
}

#[test]
fn timestamps_are_epoch_seconds_starting_on_the_first_of_july() {
    let txs = workload();
    for (index, tx) in txs.iter().enumerate() {
        let expected = WORKLOAD_START_TIMESTAMP_SECS + index as u64 * WORKLOAD_TIMESTAMP_STEP_SECS;
        assert_eq!(
            tx.ts, expected,
            "transaction {index} has an unexpected timestamp"
        );
    }
}

#[test]
fn every_round_uses_fresh_identifiers() {
    let first = workload();
    let second = workload();
    for (a, b) in first.iter().zip(second.iter()) {
        if a.kind == BenchTxKind::Genesis {
            assert_ne!(
                a.author_id, b.author_id,
                "genesis identities must differ per round"
            );
        }
    }
}

#[test]
fn every_transaction_carries_a_valid_recoverable_signature() {
    let txs = workload();
    for (index, tx) in txs.iter().enumerate() {
        let ok = match tx.kind {
            BenchTxKind::Genesis => verify_genesis_tx(&tx.author_pubkey, &tx.sig, &tx.target),
            _ => verify_tx_signature(
                &tx.author_pubkey,
                tx.kind.label(),
                &tx.target,
                &tx.payload,
                tx.seq,
                &tx.sig,
            ),
        };
        assert!(
            ok,
            "transaction {index} ({:?}) failed signature verification",
            tx.kind
        );
        assert!(
            tx.recovery_id <= 1,
            "transaction {index} recovery id out of range"
        );
    }
}

#[test]
fn wire_frames_round_trip_through_the_sync_codec() {
    let txs = workload();
    for (index, tx) in txs.iter().enumerate() {
        let decoded =
            decode_tx_message(&tx.wire).unwrap_or_else(|| panic!("tx {index} failed to decode"));
        assert_eq!(decoded.tx_type, tx.kind.op(), "tx {index} op mismatch");
        assert_eq!(decoded.seq, tx.seq, "tx {index} seq mismatch");
        assert_eq!(decoded.ts, tx.ts, "tx {index} ts mismatch");
        assert_eq!(decoded.sig, tx.sig, "tx {index} sig mismatch");
        assert_eq!(
            decoded.recovery_id, tx.recovery_id,
            "tx {index} recovery mismatch"
        );
        if tx.kind == BenchTxKind::Genesis {
            assert_eq!(decoded.author_pubkey, tx.author_pubkey);
            assert_eq!(decoded.target, tx.target);
        } else {
            assert_eq!(decoded.target, tx.target);
            assert_eq!(decoded.payload, tx.payload);
        }
    }
}

#[test]
fn wire_size_stats_cover_the_full_workload_and_each_kind() {
    let txs = workload();
    let all = wire_size_stats(&txs).expect("full workload has wire sizes");
    assert_eq!(all.count, WORKLOAD_TOTAL);
    assert!(all.min > 0);
    assert!(all.max >= all.min);
    assert!(all.median >= all.min && all.median <= all.max);
    assert!(all.mean() >= all.min as f64 && all.mean() <= all.max as f64);

    for kind in [
        BenchTxKind::Genesis,
        BenchTxKind::Mint,
        BenchTxKind::Burn,
        BenchTxKind::Send,
    ] {
        let stats = wire_size_stats_for_kind(&txs, kind).expect("kind has wire sizes");
        let expected = txs.iter().filter(|tx| tx.kind == kind).count();
        assert_eq!(stats.count, expected);
        assert!(stats.min > 0);
    }
}

#[test]
fn wire_size_stats_print_for_manual_inspection() {
    let txs = workload();
    let all = wire_size_stats(&txs).expect("full workload has wire sizes");
    println!(
        "wire ALL count={} min={} max={} median={} mean={:.1}",
        all.count,
        all.min,
        all.max,
        all.median,
        all.mean()
    );
    for kind in [
        BenchTxKind::Genesis,
        BenchTxKind::Mint,
        BenchTxKind::Burn,
        BenchTxKind::Send,
    ] {
        let stats = wire_size_stats_for_kind(&txs, kind).expect("kind has wire sizes");
        println!(
            "wire {:?} count={} min={} max={} median={} mean={:.1}",
            kind,
            stats.count,
            stats.min,
            stats.max,
            stats.median,
            stats.mean()
        );
    }
}

#[test]
fn stopwatch_measures_elapsed_time() {
    let watch = Stopwatch::start();
    assert!(watch.elapsed_ms() >= 0.0);
}

#[test]
fn benchmark_mode_is_a_global_toggle() {
    session::set_benchmark_mode(true);
    assert!(super::is_benchmark_mode());
    session::set_benchmark_mode(false);
    assert!(!super::is_benchmark_mode());
}

#[test]
fn latency_probe_only_completes_for_the_reflected_payload() {
    super::abandon_latency();
    assert!(!super::latency_pending());

    session::arm_latency("AABBCC".to_string(), vec![1, 2, 3], 4.0);
    assert!(super::latency_pending());
    assert!(
        !super::complete_latency("DDEEFF", 0),
        "a foreign signature must not complete the probe"
    );
    assert!(super::latency_pending());
    assert!(
        super::complete_latency("aabbcc", 1234),
        "signature match is case insensitive"
    );
    assert!(!super::latency_pending());
}

#[test]
fn store_growth_tracks_every_sample() {
    let dir = std::env::temp_dir().join(format!("dole-bench-store-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("temp dir is creatable");

    let mut growth = StoreGrowth::new();
    let empty = growth.sample(0, &dir);
    assert_eq!(empty.bytes, 0);
    assert_eq!(empty.delta_bytes, 0);

    std::fs::write(dir.join("a.bin"), vec![0u8; 512]).expect("write succeeds");
    let filled = growth.sample(10, &dir);
    assert_eq!(filled.bytes, 512);
    assert_eq!(filled.delta_bytes, 512);

    let nested = dir.join("nested");
    std::fs::create_dir_all(&nested).expect("nested dir is creatable");
    std::fs::write(nested.join("b.bin"), vec![0u8; 488]).expect("write succeeds");
    assert_eq!(directory_size_bytes(&dir), 1000);

    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn instrumented_ledger_path_applies_and_logs_every_transaction() {
    let dir = std::env::temp_dir().join(format!("dole-bench-ledger-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("temp dir is creatable");

    let txs = workload();
    let sync_txs = txs
        .iter()
        .map(|tx| decode_tx_message(&tx.wire).expect("wire frame decodes"))
        .collect::<Vec<_>>();

    let applied = crate::ledger::bench_apply_sync_txs(&dir, sync_txs);
    assert!(applied, "ledger accepted the workload");

    let repo_bytes = directory_size_bytes(&dir);
    assert!(
        repo_bytes > 0,
        "git repository must occupy storage after the round"
    );

    let _ = std::fs::remove_dir_all(&dir);
}
