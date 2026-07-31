mod workload;

#[cfg(feature = "bench-workload")]
#[uniffi::export]
pub fn bench_generate_transactions() -> Vec<crate::transaction::LedgerTransaction> {
    workload::generate_workload()
}
