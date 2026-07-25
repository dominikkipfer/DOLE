use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use iroh::EndpointId;

use super::InboundSource;
use super::session::SessionId;

const LOG_TARGET: &str = "dole::network";

static PEER_SNAPSHOT: Mutex<Vec<PeerConnection>> = Mutex::new(Vec::new());

#[derive(uniffi::Record, Clone, Eq, PartialEq)]
pub struct PeerConnection {
    pub session_id: String,
    pub ble: bool,
    pub mdns: bool,
    pub internet: bool
}

#[uniffi::export]
pub fn connected_peers() -> Vec<PeerConnection> {
    PEER_SNAPSHOT.lock().map(|peers| peers.clone()).unwrap_or_default()
}

pub(crate) fn clear_peer_snapshot() {
    if let Ok(mut peers) = PEER_SNAPSHOT.lock() {
        peers.clear();
    }
}

#[derive(Default)]
pub(crate) struct PeerBook {
    ble_sessions: HashMap<SessionId, BlePeerState>,
    endpoints: HashMap<EndpointId, EndpointPeerState>
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
struct BlePeerState {
    ble_seen_at: Option<Instant>
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
struct EndpointPeerState {
    mdns: bool,
    iroh_seen_at: Option<Instant>
}

impl PeerBook {
    pub(crate) fn mark_mdns(&mut self, endpoint_id: EndpointId, active: bool) -> bool {
        let old = self.summary_entries();
        let entry = self.endpoints.entry(endpoint_id).or_default();
        entry.mdns = active;
        self.cleanup_endpoint(endpoint_id);
        self.publish();
        old != self.summary_entries()
    }

    pub(crate) fn mark_seen(&mut self, source: InboundSource, seen_at: Instant) -> bool {
        match source {
            InboundSource::Ble(session_id) => self.mark_ble_seen(session_id, seen_at),
            InboundSource::Iroh(endpoint_id) => self.mark_iroh_seen(endpoint_id, seen_at)
        }
    }

    fn mark_ble_seen(&mut self, session_id: SessionId, seen_at: Instant) -> bool {
        let old = self.summary_entries();
        let entry = self.ble_sessions.entry(session_id).or_default();
        entry.ble_seen_at = Some(seen_at);
        self.publish();
        old != self.summary_entries()
    }

    fn mark_iroh_seen(&mut self, endpoint_id: EndpointId, seen_at: Instant) -> bool {
        self.mark_iroh_connected(endpoint_id, seen_at)
    }

    pub(crate) fn mark_iroh_connected(&mut self, endpoint_id: EndpointId, seen_at: Instant, ) -> bool {
        let old = self.summary_entries();
        let entry = self.endpoints.entry(endpoint_id).or_default();
        entry.iroh_seen_at = Some(seen_at);
        self.publish();
        old != self.summary_entries()
    }

    pub(crate) fn mark_iroh_disconnected(&mut self, endpoint_id: EndpointId) -> bool {
        let old = self.summary_entries();
        if let Some(entry) = self.endpoints.get_mut(&endpoint_id) {
            entry.iroh_seen_at = None;
        }
        self.cleanup_endpoint(endpoint_id);
        self.publish();
        old != self.summary_entries()
    }

    pub(crate) fn session_label(&self, endpoint_id: EndpointId) -> String {
        SessionId::from_endpoint_id(endpoint_id).short()
    }

    pub(crate) fn drop_iroh(&mut self) {
        self.endpoints.clear();
        self.publish();
    }

    pub(crate) fn iroh_count(&self) -> usize {
        self.endpoints
            .values()
            .filter(|state| endpoint_state_iroh_available(state))
            .count()
    }

    pub(crate) fn should_use_ble(&self) -> bool {
        if self.iroh_count() == 0 {
            return true;
        }

        self.ble_sessions.iter().any(|(session_id, state)| {
            state.ble_seen_at.is_some() && !self.session_has_iroh(*session_id)
        })
    }

    pub(crate) fn expire(&mut self, now: Instant, timeout: Duration) -> bool {
        let old = self.summary_entries();

        let sessions = self.ble_sessions.keys().copied().collect::<Vec<_>>();
        for session_id in sessions {
            if let Some(state) = self.ble_sessions.get_mut(&session_id)
                && state
                    .ble_seen_at
                    .is_some_and(|seen_at| now.saturating_duration_since(seen_at) >= timeout)
            {
                let age_ms = state
                    .ble_seen_at
                    .map(|seen_at| now.saturating_duration_since(seen_at).as_millis())
                    .unwrap_or_default();
                log::info!(
                    target: LOG_TARGET,
                    "PeerBook presence expired transport=ble session={} ageMs={} timeoutMs={}",
                    session_id.short(),
                    age_ms,
                    timeout.as_millis()
                );
                state.ble_seen_at = None;
            }
            self.cleanup_ble_session(session_id);
        }

        let endpoints = self.endpoints.keys().copied().collect::<Vec<_>>();
        for endpoint_id in endpoints {
            if let Some(state) = self.endpoints.get_mut(&endpoint_id)
                && state
                    .iroh_seen_at
                    .is_some_and(|seen_at| now.saturating_duration_since(seen_at) >= timeout)
            {
                let age_ms = state
                    .iroh_seen_at
                    .map(|seen_at| now.saturating_duration_since(seen_at).as_millis())
                    .unwrap_or_default();
                log::info!(
                    target: LOG_TARGET,
                    "PeerBook presence expired transport=iroh endpoint={} session={} ageMs={} timeoutMs={}",
                    endpoint_id.fmt_short(),
                    SessionId::from_endpoint_id(endpoint_id).short(),
                    age_ms,
                    timeout.as_millis()
                );
                state.iroh_seen_at = None;
            }
            self.cleanup_endpoint(endpoint_id);
        }

        self.publish();
        old != self.summary_entries()
    }

    pub(crate) fn log_summary(&self, reason: &'static str) {
        let peers = self.summary_entries();

        if peers.is_empty() {
            log::info!(target: LOG_TARGET, "PeerBook reason={reason} peers=none");
            return;
        }

        log::info!(
            target: LOG_TARGET,
            "PeerBook reason={} peers={}",
            reason,
            peers.join(", ")
        );
    }

    fn summary_entries(&self) -> Vec<String> {
        let mut peers = self
            .endpoints
            .iter()
            .filter(|(_, state)| endpoint_state_iroh_available(state))
            .map(|(endpoint_id, _)| {
                let session_id = SessionId::from_endpoint_id(*endpoint_id);
                format!(
                    "session={} endpoint={} ble={} iroh={}",
                    session_id.short(),
                    endpoint_id.fmt_short(),
                    self.ble_label_for_session(session_id),
                    connection_label(true)
                )
            })
            .collect::<Vec<_>>();
        peers.extend(
            self.ble_sessions
                .iter()
                .filter(|(session_id, state)| {
                    state.ble_seen_at.is_some() && !self.session_has_iroh(**session_id)
                })
                .map(|(session_id, _)| {
                    format!(
                        "session={} endpoint=none ble={} iroh={}",
                        session_id.short(),
                        connection_label(true),
                        connection_label(false)
                    )
                })
        );
        peers.sort();
        peers
    }

    fn publish(&self) {
        let snapshot = self.connection_entries();
        if let Ok(mut peers) = PEER_SNAPSHOT.lock()
            && *peers != snapshot
        {
            *peers = snapshot;
        }
    }

    fn connection_entries(&self) -> Vec<PeerConnection> {
        let mut peers = self
            .endpoints
            .iter()
            .filter(|(_, state)| endpoint_state_iroh_available(state))
            .map(|(endpoint_id, state)| {
                let session_id = SessionId::from_endpoint_id(*endpoint_id);
                PeerConnection {
                    session_id: session_id.short(),
                    ble: self.session_receiving_ble(session_id),
                    mdns: state.mdns,
                    internet: state.iroh_seen_at.is_some() && !state.mdns
                }
            })
            .collect::<Vec<_>>();

        peers.extend(
            self.ble_sessions
                .iter()
                .filter(|(session_id, state)| {
                    state.ble_seen_at.is_some() && !self.session_has_iroh(**session_id)
                })
                .map(|(session_id, _)| PeerConnection {
                    session_id: session_id.short(),
                    ble: true,
                    mdns: false,
                    internet: false
                })
        );

        peers.sort_by(|a, b| a.session_id.cmp(&b.session_id));
        peers
    }

    fn session_receiving_ble(&self, session_id: SessionId) -> bool {
        self.ble_sessions
            .get(&session_id)
            .is_some_and(|state| state.ble_seen_at.is_some())
    }

    fn session_has_iroh(&self, session_id: SessionId) -> bool {
        self.endpoints.iter().any(|(endpoint_id, state)| {
            SessionId::from_endpoint_id(*endpoint_id) == session_id
                && endpoint_state_iroh_available(state)
        })
    }

    fn ble_label_for_session(&self, session_id: SessionId) -> &'static str {
        if self
            .ble_sessions
            .get(&session_id)
            .is_some_and(|state| state.ble_seen_at.is_some())
        {
            "receiving"
        } else {
            "standby"
        }
    }

    fn cleanup_ble_session(&mut self, session_id: SessionId) {
        if self
            .ble_sessions
            .get(&session_id)
            .is_some_and(|state| state.ble_seen_at.is_none())
        {
            self.ble_sessions.remove(&session_id);
        }
    }

    fn cleanup_endpoint(&mut self, endpoint_id: EndpointId) {
        if self
            .endpoints
            .get(&endpoint_id)
            .is_some_and(|state| !state.mdns && state.iroh_seen_at.is_none())
        {
            self.endpoints.remove(&endpoint_id);
        }
    }
}

fn endpoint_state_iroh_available(state: &EndpointPeerState) -> bool {
    state.mdns || state.iroh_seen_at.is_some()
}

fn connection_label(connected: bool) -> &'static str {
    if connected {
        "connected"
    } else {
        "disconnected"
    }
}
