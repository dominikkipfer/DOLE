mod workload;

#[cfg(feature = "bench-workload")]
#[uniffi::export]
pub fn bench_generate_transactions() -> Vec<crate::transaction::LedgerTransaction> {
    std::panic::catch_unwind(workload::generate_workload).unwrap_or_default()
}

#[cfg(all(test, feature = "bench-workload"))]
mod tests {
    #[test]
    fn bench_generate_transactions_returns_workload() {
        let transactions = super::bench_generate_transactions();

        assert_eq!(transactions.len(), super::workload::WORKLOAD_TOTAL);
    }
}
