use std::collections::{HashMap, HashSet};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use iroh::EndpointId;

use super::session::SessionId;
use super::{InboundSource, set_internet_hub_online, set_peer_ble_payload_limit};

static PEER_SNAPSHOT: Mutex<Vec<PeerConnection>> = Mutex::new(Vec::new());
static HUB_ENDPOINTS: LazyLock<Mutex<HashSet<EndpointId>>> =
    LazyLock::new(|| Mutex::new(HashSet::new()));

#[derive(uniffi::Record, Clone, Eq, PartialEq)]
pub struct PeerConnection {
    pub session_id: String,
    pub ble: bool,
    pub mdns: bool,
    pub internet: bool,
}

#[uniffi::export]
pub fn connected_peers() -> Vec<PeerConnection> {
    PEER_SNAPSHOT
        .lock()
        .map(|peers| peers.clone())
        .unwrap_or_default()
}

pub(crate) fn set_hub_endpoints(hubs: HashSet<EndpointId>) {
    if let Ok(mut guard) = HUB_ENDPOINTS.lock() {
        *guard = hubs;
    }
}

pub(crate) fn has_hub_endpoints() -> bool {
    HUB_ENDPOINTS
        .lock()
        .map(|guard| !guard.is_empty())
        .unwrap_or(false)
}

fn is_hub_endpoint(endpoint_id: &EndpointId) -> bool {
    HUB_ENDPOINTS
        .lock()
        .map(|guard| guard.contains(endpoint_id))
        .unwrap_or(false)
}

pub(crate) fn clear_peer_snapshot() {
    if let Ok(mut peers) = PEER_SNAPSHOT.lock() {
        peers.clear();
    }
}

#[derive(Default)]
pub(crate) struct PeerBook {
    ble_sessions: HashMap<SessionId, BlePeerState>,
    endpoints: HashMap<EndpointId, EndpointPeerState>,
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
struct BlePeerState {
    ble_seen_at: Option<Instant>,
    max_payload: Option<u16>,
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
struct EndpointPeerState {
    mdns: bool,
    connected: bool,
    iroh_seen_at: Option<Instant>,
}

impl PeerBook {
    pub(crate) fn mark_mdns(&mut self, endpoint_id: EndpointId, active: bool) {
        let entry = self.endpoints.entry(endpoint_id).or_default();
        entry.mdns = active;
        self.cleanup_endpoint(endpoint_id);
        self.publish();
    }

    pub(crate) fn mark_ble_limit(&mut self, source: InboundSource, limit: u16) {
        if limit == 0 {
            return;
        }
        let session_id = match source {
            InboundSource::Ble(session_id) => session_id,
            InboundSource::Iroh(endpoint_id) => SessionId::from_endpoint_id(endpoint_id),
        };
        let entry = self.ble_sessions.entry(session_id).or_default();
        if entry.max_payload == Some(limit) {
            return;
        }
        entry.max_payload = Some(limit);
        self.publish();
    }

    fn publish_ble_payload_limit(&self) {
        let limit = self
            .ble_sessions
            .values()
            .filter_map(|state| state.max_payload)
            .min()
            .unwrap_or(0);
        set_peer_ble_payload_limit(limit);
    }

    pub(crate) fn mark_seen(&mut self, source: InboundSource, seen_at: Instant) {
        match source {
            InboundSource::Ble(session_id) => self.mark_ble_seen(session_id, seen_at),
            InboundSource::Iroh(endpoint_id) => self.mark_iroh_seen(endpoint_id, seen_at),
        }
    }

    fn mark_ble_seen(&mut self, session_id: SessionId, seen_at: Instant) {
        let entry = self.ble_sessions.entry(session_id).or_default();
        entry.ble_seen_at = Some(seen_at);
        self.publish();
    }

    fn mark_iroh_seen(&mut self, endpoint_id: EndpointId, seen_at: Instant) {
        let entry = self.endpoints.entry(endpoint_id).or_default();
        entry.connected = true;
        entry.iroh_seen_at = Some(seen_at);
        self.publish();
    }

    pub(crate) fn mark_iroh_connected(&mut self, endpoint_id: EndpointId) {
        let entry = self.endpoints.entry(endpoint_id).or_default();
        entry.connected = true;
        self.publish();
    }

    pub(crate) fn mark_iroh_disconnected(&mut self, endpoint_id: EndpointId) {
        if let Some(entry) = self.endpoints.get_mut(&endpoint_id) {
            entry.connected = false;
            entry.iroh_seen_at = None;
        }
        self.cleanup_endpoint(endpoint_id);
        self.publish();
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

    pub(crate) fn expire(&mut self, now: Instant, timeout: Duration) {
        let sessions = self.ble_sessions.keys().copied().collect::<Vec<_>>();
        for session_id in sessions {
            if let Some(state) = self.ble_sessions.get_mut(&session_id)
                && state
                    .ble_seen_at
                    .is_some_and(|seen_at| now.saturating_duration_since(seen_at) >= timeout)
            {
                state.ble_seen_at = None;
            }
            self.cleanup_ble_session(session_id);
        }

        let endpoints = self.endpoints.keys().copied().collect::<Vec<_>>();
        for endpoint_id in endpoints {
            if let Some(state) = self.endpoints.get_mut(&endpoint_id)
                && !state.connected
                && state
                    .iroh_seen_at
                    .is_some_and(|seen_at| now.saturating_duration_since(seen_at) >= timeout)
            {
                state.iroh_seen_at = None;
            }
            self.cleanup_endpoint(endpoint_id);
        }

        self.publish();
    }

    fn publish(&self) {
        self.publish_ble_payload_limit();
        let snapshot = self.connection_entries();
        if let Ok(mut peers) = PEER_SNAPSHOT.lock()
            && *peers != snapshot
        {
            *peers = snapshot;
        }
    }

    fn connection_entries(&self) -> Vec<PeerConnection> {
        let internet_enabled = super::mdns::is_internet_enabled();

        let hubs_active = self
            .endpoints
            .iter()
            .any(|(id, state)| is_hub_endpoint(id) && state.connected);

        set_internet_hub_online(hubs_active);

        let mut peers = self
            .endpoints
            .iter()
            .filter(|(endpoint_id, _)| !is_hub_endpoint(endpoint_id))
            .filter(|(_, state)| endpoint_state_iroh_available(state))
            .map(|(endpoint_id, state)| {
                let session_id = SessionId::from_endpoint_id(*endpoint_id);
                PeerConnection {
                    session_id: session_id.short(),
                    ble: self.session_receiving_ble(session_id),
                    mdns: state.mdns,
                    internet: internet_enabled && state.connected,
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
                    internet: false,
                }),
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

    fn cleanup_ble_session(&mut self, session_id: SessionId) {
        if self
            .ble_sessions
            .get(&session_id)
            .is_some_and(|state| state.ble_seen_at.is_none() && state.max_payload.is_none())
        {
            self.ble_sessions.remove(&session_id);
        }
    }

    fn cleanup_endpoint(&mut self, endpoint_id: EndpointId) {
        if self
            .endpoints
            .get(&endpoint_id)
            .is_some_and(|state| !state.mdns && !state.connected && state.iroh_seen_at.is_none())
        {
            self.endpoints.remove(&endpoint_id);
        }
    }
}

fn endpoint_state_iroh_available(state: &EndpointPeerState) -> bool {
    state.mdns || state.connected || state.iroh_seen_at.is_some()
}
