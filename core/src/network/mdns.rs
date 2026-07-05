use iroh::{
    Endpoint, EndpointAddr, EndpointId, Watcher,
    address_lookup::{EndpointData, EndpointInfo, memory::MemoryLookup},
    endpoint::presets,
    protocol::Router
};
use iroh_gossip::{Gossip, TopicId, api::{Event as GossipEvent, GossipSender}};
use n0_future::StreamExt;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;
use swarm_discovery::{Discoverer, IpClass, Peer};
use tokio::sync::mpsc;

use crate::constants::IROH_ENDPOINT_BIND_TIMEOUT_MS;

use super::session::SessionId;

const SERVICE_NAME: &str = "dole";
const BIND_TIMEOUT: Duration = Duration::from_millis(IROH_ENDPOINT_BIND_TIMEOUT_MS as u64);

const TOPIC_BYTES: [u8; 32] = [
    0xDA, 0x01, 0xED, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
];

pub enum Command {
    Broadcast(Vec<u8>)
}

pub enum Event {
    Ready {
        endpoint_id: EndpointId,
        max_message_size: usize
    },
    PeerDiscovered {
        endpoint_id: EndpointId
    },
    PeerExpired {
        endpoint_id: EndpointId
    },
    PeerConnected {
        endpoint_id: EndpointId
    },
    PeerDisconnected {
        endpoint_id: EndpointId
    },
    Message {
        endpoint_id: EndpointId,
        payload: Vec<u8>
    },
    TransportError(String)
}

pub async fn run(
    mut command_rx: mpsc::UnboundedReceiver<Command>,
    event_tx: mpsc::UnboundedSender<Event>,
    mut shutdown_rx: mpsc::UnboundedReceiver<()>
) {
    let endpoint = match bind_endpoint().await {
        Ok(endpoint) => endpoint,
        Err(e) => {
            let _ = event_tx.send(Event::TransportError(e));
            return;
        }
    };

    let local_endpoint_id = endpoint.id();
    let Discovery {
        guard: discovery_guard,
        events: mut discovery_events,
        memory_lookup,
        advertised_addrs
    } = match start_discovery(&endpoint) {
        Ok(discovery) => discovery,
        Err(e) => {
            let _ = event_tx.send(Event::TransportError(format!(
                "could not start mDNS discovery: {e}"
            )));
            return;
        }
    };
    let mut advertised_addrs = advertised_addrs;
    let mut addr_stream = endpoint.watch_addr().stream();

    let gossip = Gossip::builder().spawn(endpoint.clone());
    let max_message_size = gossip.max_message_size();
    let router = Router::builder(endpoint.clone()).accept(iroh_gossip::ALPN, gossip.clone()).spawn();

    let topic_id = TopicId::from_bytes(TOPIC_BYTES);
    let (gossip_sender, mut gossip_receiver) = match gossip.subscribe(topic_id, vec![]).await {
        Ok(subscription) => subscription.split(),
        Err(e) => {
            let _ = event_tx.send(Event::TransportError(format!("could not subscribe to gossip topic: {e:?}")));
            let _ = router.shutdown().await;
            let _ = gossip.shutdown().await;
            return;
        }
    };

    let _ = event_tx.send(Event::Ready {
        endpoint_id: local_endpoint_id,
        max_message_size
    });

    let mut joined_peers: HashSet<EndpointId> = HashSet::new();
    let mut connected_peers: HashSet<EndpointId> = HashSet::new();
    let mut discovered_peers: HashSet<EndpointId> = HashSet::new();

    loop {
        tokio::select! {
            _ = shutdown_rx.recv() => {
                let _ = gossip.shutdown().await;
                let _ = router.shutdown().await;
                break;
            }

            Some(command) = command_rx.recv() => {
                match command {
                    Command::Broadcast(payload) => {
                        broadcast(&gossip_sender, payload, &event_tx).await;
                    }
                }
            }

            Some(event_res) = gossip_receiver.next() => {
                match event_res {
                    Ok(GossipEvent::Received(message)) => {
                        let _ = event_tx.send(Event::Message {
                            endpoint_id: message.delivered_from,
                            payload: message.content.as_ref().to_vec()
                        });
                    }
                    Ok(GossipEvent::NeighborUp(endpoint_id)) => {
                        if connected_peers.insert(endpoint_id) {
                            let _ = event_tx.send(Event::PeerConnected { endpoint_id });
                        }
                    }
                    Ok(GossipEvent::NeighborDown(endpoint_id)) => {
                        joined_peers.remove(&endpoint_id);
                        connected_peers.remove(&endpoint_id);
                        let _ = event_tx.send(Event::PeerDisconnected { endpoint_id });
                    }
                    Ok(GossipEvent::Lagged) => {
                        let _ = event_tx.send(Event::TransportError(
                            "gossip receiver lagged; one or more sync messages may have been dropped".to_string()
                        ));
                    }
                    Err(e) => {
                        let _ = event_tx.send(Event::TransportError(format!(
                            "gossip event stream failed: {e:?}"
                        )));
                    }
                }
            }

            Some(event) = discovery_events.recv() => {
                match event {
                    DiscoveryEvent::Discovered(peer) => {
                        let endpoint_id = peer.endpoint_info.endpoint_id;
                        if endpoint_id != local_endpoint_id {
                            if discovered_peers.insert(endpoint_id) {
                                log::info!(
                                    target: "dole::mdns",
                                    "mDNS discovery found endpoint={} session={}",
                                    endpoint_id.fmt_short(),
                                    SessionId::from_endpoint_id(endpoint_id).short()
                                );
                            }
                            let _ = event_tx.send(Event::PeerDiscovered { endpoint_id });
                        }

                        if should_join_peer(local_endpoint_id, endpoint_id, &connected_peers, &joined_peers) {
                            memory_lookup.add_endpoint_info(peer.endpoint_info);
                            joined_peers.insert(endpoint_id);
                            log::info!(
                                target: "dole::mdns",
                                "Iroh discovery joining endpoint={} session={}",
                                endpoint_id.fmt_short(),
                                SessionId::from_endpoint_id(endpoint_id).short()
                            );
                            if let Err(e) = gossip_sender.join_peers(vec![endpoint_id]).await {
                                joined_peers.remove(&endpoint_id);
                                let _ = event_tx.send(Event::TransportError(format!(
                                    "could not join mDNS peer {}: {e:?}",
                                    endpoint_id.fmt_short()
                                )));
                            }
                        }
                    }
                    DiscoveryEvent::Expired(endpoint_id) => {
                        if endpoint_id == local_endpoint_id {
                            continue;
                        }
                        log::info!(
                            target: "dole::mdns",
                            "mDNS discovery expired endpoint={}",
                            endpoint_id.fmt_short()
                        );
                        memory_lookup.remove_endpoint_info(endpoint_id);
                        joined_peers.remove(&endpoint_id);
                        discovered_peers.remove(&endpoint_id);
                        let _ = event_tx.send(Event::PeerExpired { endpoint_id });
                    }
                }
            }

            Some(endpoint_addr) = addr_stream.next() => {
                replace_advertised_mdns_addrs(
                    &discovery_guard,
                    &mut advertised_addrs,
                    endpoint_addr_mdns_addrs(&endpoint_addr)
                );
            }
        }
    }
}

async fn bind_endpoint() -> Result<Endpoint, String> {
    let builder = Endpoint::builder(presets::Minimal)
        .secret_key(super::session::session_secret_key())
        .bind_addr("0.0.0.0:0")
        .map_err(|e| format!("could not create iroh endpoint builder: {e}"))?;

    match tokio::time::timeout(BIND_TIMEOUT, builder.bind()).await {
        Ok(Ok(endpoint)) => Ok(endpoint),
        Ok(Err(e)) => Err(format!("could not bind local iroh endpoint: {e:?}")),
        Err(_) => Err("timed out while binding local iroh endpoint".to_string())
    }
}

async fn broadcast(gossip_sender: &GossipSender, payload: Vec<u8>, event_tx: &mpsc::UnboundedSender<Event>) {
    if let Err(e) = gossip_sender.broadcast(payload.into()).await {
        let _ = event_tx.send(Event::TransportError(format!(
            "could not broadcast sync message over gossip: {e:?}"
        )));
    }
}

struct Discovery {
    guard: swarm_discovery::DropGuard,
    events: mpsc::UnboundedReceiver<DiscoveryEvent>,
    memory_lookup: MemoryLookup,
    advertised_addrs: MdnsAddrs
}

enum DiscoveryEvent {
    Discovered(DiscoveredPeer),
    Expired(EndpointId)
}

struct DiscoveredPeer {
    endpoint_info: EndpointInfo
}

type MdnsAddrs = HashMap<u16, BTreeSet<IpAddr>>;

fn start_discovery(endpoint: &Endpoint) -> Result<Discovery, String> {
    let memory_lookup = MemoryLookup::with_provenance("dole-mdns");
    endpoint
        .address_lookup()
        .map_err(|e| format!("address lookup is not available: {e:?}"))?
        .add(memory_lookup.clone());

    let (event_tx, event_rx) = mpsc::unbounded_channel();
    let peer_id = peer_name(endpoint.id());
    log::info!(
        target: "dole::mdns",
        "mDNS discovery publishing peer={} endpoint={} session={}",
        peer_id,
        endpoint.id().fmt_short(),
        SessionId::from_endpoint_id(endpoint.id()).short()
    );

    let mut discoverer = discoverer(peer_id, event_tx).with_ip_class(IpClass::Auto);

    let advertised_addrs = endpoint_addr_mdns_addrs(&endpoint.addr());
    log::info!(
        target: "dole::mdns",
        "mDNS advertising addrs={advertised_addrs:?}"
    );
    for (port, addrs) in &advertised_addrs {
        discoverer = discoverer.with_addrs(*port, addrs.iter().copied());
    }

    let guard = discoverer.spawn(&tokio::runtime::Handle::current()).map_err(|e| format!("{e}"))?;

    Ok(Discovery {
        guard,
        events: event_rx,
        memory_lookup,
        advertised_addrs
    })
}

fn endpoint_addr_mdns_addrs(endpoint_addr: &EndpointAddr) -> MdnsAddrs {
    let mut addrs = MdnsAddrs::new();

    for socket_addr in endpoint_addr.ip_addrs() {
        addrs
            .entry(socket_addr.port())
            .or_default()
            .insert(socket_addr.ip());
    }

    addrs
}

fn replace_advertised_mdns_addrs(guard: &swarm_discovery::DropGuard, current: &mut MdnsAddrs, next: MdnsAddrs) {
    let current_ports = current.keys().copied().collect::<Vec<_>>();
    for port in current_ports {
        if next.get(&port) != current.get(&port) {
            guard.remove_port(port);
        }
    }

    for (port, addrs) in &next {
        if current.get(port) != Some(addrs) {
            guard.add(*port, addrs.iter().copied().collect());
        }
    }

    if *current != next {
        log::info!(target: "dole::mdns", "mDNS advertising addrs updated addrs={next:?}");
    }
    *current = next;
}

fn discoverer(peer_id: String, event_tx: mpsc::UnboundedSender<DiscoveryEvent>) -> Discoverer {
    Discoverer::new_interactive(SERVICE_NAME.to_string(), peer_id).with_callback(move |peer_name, peer| {
        let Some(endpoint_id) = parse_peer_name(peer_name) else {
            log::warn!(target: "dole::mdns", "Ignored mDNS peer with unparsable name={peer_name}");
            return;
        };

        let event = if peer.is_expiry() {
            DiscoveryEvent::Expired(endpoint_id)
        } else {
            DiscoveryEvent::Discovered(DiscoveredPeer {
                endpoint_info: peer_to_endpoint_info(peer, endpoint_id)
            })
        };

        let _ = event_tx.send(event);
    })
}

fn peer_name(endpoint_id: EndpointId) -> String {
    data_encoding::BASE32_NOPAD.encode(endpoint_id.as_bytes()).to_ascii_lowercase()
}

fn parse_peer_name(value: &str) -> Option<EndpointId> {
    let input = value.to_ascii_uppercase();
    let decoded = data_encoding::BASE32_NOPAD.decode(input.as_bytes()).ok()?;
    let bytes: [u8; 32] = decoded.as_slice().try_into().ok()?;
    EndpointId::from_bytes(&bytes).ok()
}

fn peer_to_endpoint_info(peer: &Peer, endpoint_id: EndpointId) -> EndpointInfo {
    let ip_addrs: BTreeSet<SocketAddr> = peer
        .addrs()
        .iter()
        .map(|(ip, port)| SocketAddr::new(*ip, *port))
        .collect();

    EndpointInfo::from_parts(endpoint_id, EndpointData::from(ip_addrs))
}

fn should_join_peer(
    local_endpoint_id: EndpointId,
    remote_endpoint_id: EndpointId,
    connected_peers: &HashSet<EndpointId>,
    joined_peers: &HashSet<EndpointId>
) -> bool {
    remote_endpoint_id != local_endpoint_id
        && !connected_peers.contains(&remote_endpoint_id)
        && !joined_peers.contains(&remote_endpoint_id)
}
