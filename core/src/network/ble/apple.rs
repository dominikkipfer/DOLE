use super::LOG_TARGET;

pub(super) fn start(_storage_path: &str) -> bool {
    false
}

pub(super) fn stop() {
    log::debug!(target: LOG_TARGET, "Apple BLE stop requested");
}