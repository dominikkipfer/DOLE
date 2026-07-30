use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use super::BENCH_LOG_TARGET;

static BENCHMARK_MODE: AtomicBool = AtomicBool::new(false);
static STATE: LazyLock<Mutex<BenchState>> = LazyLock::new(|| Mutex::new(BenchState::default()));
static LATEST_REPORT: Mutex<Option<String>> = Mutex::new(None);

pub fn set_benchmark_mode(enabled: bool) {
    BENCHMARK_MODE.store(enabled, Ordering::Relaxed);
}

pub fn is_benchmark_mode() -> bool {
    BENCHMARK_MODE.load(Ordering::Relaxed)
}

pub fn duration_to_ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}

pub struct Stopwatch {
    start: Instant,
}

impl Stopwatch {
    pub fn start() -> Self {
        Self {
            start: Instant::now(),
        }
    }

    pub fn elapsed_ms(&self) -> f64 {
        duration_to_ms(self.start.elapsed())
    }
}

#[derive(Default)]
struct BenchState {
    latency: Option<LatencyRun>,
    latency_index: usize,
    injecting: bool,
}

struct LatencyRun {
    sent_at: Instant,
    internal_ms: f64,
    signature: String,
    payload: Vec<u8>,
}

pub fn set_injecting(injecting: bool) {
    if let Ok(mut guard) = STATE.lock() {
        guard.injecting = injecting;
    }
}

pub fn is_injecting() -> bool {
    STATE.lock().map(|guard| guard.injecting).unwrap_or(false)
}

pub fn latency_index() -> usize {
    STATE.lock().map(|guard| guard.latency_index).unwrap_or(0)
}

pub fn advance_latency_index(total: usize) {
    if let Ok(mut guard) = STATE.lock() {
        guard.latency_index = if total == 0 {
            0
        } else {
            (guard.latency_index + 1) % total
        };
    }
}

pub fn reset_latency_index() {
    if let Ok(mut guard) = STATE.lock() {
        guard.latency_index = 0;
    }
}

pub fn arm_latency(signature: String, payload: Vec<u8>, internal_ms: f64) {
    let Ok(mut guard) = STATE.lock() else {
        return;
    };
    let started_at = Instant::now()
        .checked_sub(Duration::from_secs_f64(internal_ms.max(0.0) / 1000.0))
        .unwrap_or_else(Instant::now);
    guard.latency = Some(LatencyRun {
        sent_at: started_at,
        internal_ms,
        signature,
        payload,
    });
}

pub fn pending_probe_payload() -> Option<Vec<u8>> {
    STATE
        .lock()
        .ok()?
        .latency
        .as_ref()
        .map(|run| run.payload.clone())
}

pub fn latency_pending() -> bool {
    STATE
        .lock()
        .map(|guard| guard.latency.is_some())
        .unwrap_or(false)
}

pub fn abandon_latency() {
    if let Ok(mut guard) = STATE.lock() {
        guard.latency = None;
    }
}

pub fn take_latency_report() -> Option<String> {
    LATEST_REPORT.lock().ok()?.take()
}

pub fn complete_latency(signature: &str, peer_internal_us: u64) -> bool {
    let Ok(mut guard) = STATE.lock() else {
        return false;
    };
    let Some(run) = guard.latency.as_ref() else {
        return false;
    };
    if !run.signature.eq_ignore_ascii_case(signature) {
        return false;
    }

    let total = duration_to_ms(run.sent_at.elapsed());
    let internal = run.internal_ms;
    guard.latency = None;
    drop(guard);

    let report = format!(
        "internal {:.1} ms, peer {:.1} ms, total {:.1} ms",
        internal,
        peer_internal_us as f64 / 1000.0,
        total
    );
    log::info!(target: BENCH_LOG_TARGET, "{report}");
    if let Ok(mut latest) = LATEST_REPORT.lock() {
        *latest = Some(report);
    }
    true
}
