use iroh::{
    Endpoint, EndpointAddr, EndpointId, Watcher,
    address_lookup::{EndpointData, EndpointInfo, memory::MemoryLookup},
    endpoint::presets,
    protocol::Router,
};
use iroh_gossip::{
    Gossip, TopicId,
    api::{Event, GossipSender},
};
use n0_future::StreamExt;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::thread;
use std::time::Duration;
use swarm_discovery::{Discoverer, IpClass, Peer};
use tokio::sync::mpsc;

use crate::constants::WIRE_INTERNAL_SYNC_REQUEST;

const LOG_TARGET: &str = "dole::network";
const MDNS_SERVICE_NAME: &str = "dole";
const MDNS_STARTUP_PROBE_CADENCE: Duration = Duration::from_millis(500);
const MDNS_STARTUP_PROBE_RUNTIME: Duration = Duration::from_secs(2);

const DOLE_TOPIC_BYTES: [u8; 32] = [
    0xDA, 0x01, 0xED, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
];

fn init_logging() {
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_filter(
                    android_logger::FilterBuilder::new()
                        .parse("off,dole=info")
                        .build(),
                )
                .with_tag("CORE")
                .format(|f, record| write!(f, "{}", record.args())),
        );
    }

    #[cfg(not(target_os = "android"))]
    {
        use std::io::Write;
        let _ = env_logger::builder()
            .filter_level(log::LevelFilter::Off)
            .filter_module("dole", log::LevelFilter::Info)
            .target(env_logger::Target::Stdout)
            .format(|buf, record| writeln!(buf, "[CORE] {} {}", record.level(), record.args()))
            .try_init();
    }
}

pub fn start_sync_engine(
    outgoing_rx: mpsc::UnboundedReceiver<Vec<u8>>,
    incoming_tx: mpsc::UnboundedSender<Vec<u8>>,
    shutdown_rx: mpsc::UnboundedReceiver<()>,
) {
    thread::spawn(move || {
        init_logging();

        let rt = match tokio::runtime::Runtime::new() {
            Ok(rt) => rt,
            Err(e) => {
                log::error!(target: LOG_TARGET, "Sync engine could not start Tokio runtime: {e}");
                return;
            }
        };

        rt.block_on(run_sync_engine(outgoing_rx, incoming_tx, shutdown_rx));
    });
}

async fn run_sync_engine(
    mut outgoing_rx: mpsc::UnboundedReceiver<Vec<u8>>,
    incoming_tx: mpsc::UnboundedSender<Vec<u8>>,
    mut shutdown_rx: mpsc::UnboundedReceiver<()>,
) {
    let builder = match Endpoint::builder(presets::Minimal)
        .clear_ip_transports()
        .bind_addr("0.0.0.0:0")
    {
        Ok(b) => b,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Sync engine could not create iroh endpoint builder: {e}");
            return;
        }
    };

    let bind_future = builder.bind();
    let endpoint = match tokio::time::timeout(Duration::from_secs(10), bind_future).await {
        Ok(Ok(ep)) => ep,
        Ok(Err(e)) => {
            log::error!(target: LOG_TARGET, "Sync engine could not bind local iroh endpoint: {e:?}");
            return;
        }
        Err(_) => {
            log::error!(target: LOG_TARGET, "Sync engine timed out while binding local iroh endpoint");
            return;
        }
    };

    let local_id = endpoint.id();

    tokio::time::sleep(Duration::from_millis(500)).await;

    let (mdns_guard, mut mdns_events, memory_lookup) = match start_mdns_discovery(&endpoint) {
        Ok(discovery) => discovery,
        Err(e) => {
            log::error!(target: LOG_TARGET, "Sync engine could not start mDNS discovery: {e}");
            return;
        }
    };
    let mut addr_stream = endpoint.watch_addr().stream();

    let gossip = Gossip::builder().spawn(endpoint.clone());
    let gossip_max_message_size = gossip.max_message_size();
    let router = Router::builder(endpoint.clone())
        .accept(iroh_gossip::ALPN, gossip.clone())
        .spawn();

    let topic_id = TopicId::from_bytes(DOLE_TOPIC_BYTES);
    let (gossip_sender, mut gossip_receiver) = match gossip.subscribe(topic_id, vec![]).await {
        Ok(t) => t.split(),
        Err(e) => {
            log::error!(target: LOG_TARGET, "Sync engine could not subscribe to DOLE gossip topic: {e:?}");
            let _ = router.shutdown().await;
            let _ = gossip.shutdown().await;
            return;
        }
    };

    let mut joined_peers: HashSet<EndpointId> = HashSet::new();
    let mut connected_peers: HashSet<EndpointId> = HashSet::new();

    loop {
        tokio::select! {
            _ = shutdown_rx.recv() => {
                let _ = gossip.shutdown().await;
                let _ = router.shutdown().await;
                break;
            }

            Some(msg) = outgoing_rx.recv() => {
                if connected_peers.is_empty() {
                    continue;
                }

                broadcast_sync_message(&gossip_sender, msg, gossip_max_message_size).await;
            }

            Some(event_res) = gossip_receiver.next() => {
                match event_res {
                    Ok(Event::Received(message)) => {
                        let len = message.content.len();
                        log::info!(
                            target: LOG_TARGET,
                            "Received sync message from peer {} ({} bytes)",
                            message.delivered_from.fmt_short(),
                            len
                        );
                        if incoming_tx.send(message.content.as_ref().to_vec()).is_err() {
                            log::warn!(target: LOG_TARGET, "Received sync message, but the ledger receiver is closed");
                        }
                    }
                    Ok(Event::NeighborUp(node)) => {
                        if connected_peers.insert(node) {
                            if incoming_tx.send(vec![WIRE_INTERNAL_SYNC_REQUEST]).is_err() {
                                log::warn!(target: LOG_TARGET, "Could not request ledger sync because the ledger receiver is closed");
                            }
                        }
                    }
                    Ok(Event::NeighborDown(node)) => {
                        joined_peers.remove(&node);
                        connected_peers.remove(&node);
                    }
                    Ok(Event::Lagged) => {
                        log::warn!(target: LOG_TARGET, "Gossip receiver lagged; one or more sync messages may have been dropped");
                    }
                    Err(e) => {
                        log::error!(target: LOG_TARGET, "Gossip event stream failed: {e:?}");
                    }
                }
            }

            Some(event) = mdns_events.recv() => {
                match event {
                    PeerDiscoveryEvent::Discovered(endpoint_info) => {
                        let node_id = endpoint_info.endpoint_id;
                        if should_join_peer(local_id, node_id, &connected_peers, &joined_peers) {
                            memory_lookup.add_endpoint_info(endpoint_info);
                            joined_peers.insert(node_id);
                            if let Err(e) = gossip_sender.join_peers(vec![node_id]).await {
                                joined_peers.remove(&node_id);
                                log::error!(
                                    target: LOG_TARGET,
                                    "Could not join mDNS peer {}: {e:?}",
                                    node_id.fmt_short()
                                );
                            }
                        }
                    }
                    PeerDiscoveryEvent::Expired(endpoint_id) => {
                        memory_lookup.remove_endpoint_info(endpoint_id);
                        joined_peers.remove(&endpoint_id);
                        connected_peers.remove(&endpoint_id);
                    }
                }
            }

            Some(endpoint_addr) = addr_stream.next() => {
                mdns_guard.remove_all();
                for (port, addrs) in endpoint_addr_mdns_addrs(&endpoint_addr) {
                    mdns_guard.add(port, addrs);
                }
            }
        }
    }
}

enum PeerDiscoveryEvent {
    Discovered(EndpointInfo),
    Expired(EndpointId),
}

fn start_mdns_discovery(
    endpoint: &Endpoint,
) -> Result<
    (
        swarm_discovery::DropGuard,
        mpsc::UnboundedReceiver<PeerDiscoveryEvent>,
        MemoryLookup,
    ),
    String,
> {
    let memory_lookup = MemoryLookup::with_provenance("dole-mdns");
    endpoint
        .address_lookup()
        .map_err(|e| format!("address lookup is not available: {e:?}"))?
        .add(memory_lookup.clone());

    let (event_tx, event_rx) = mpsc::unbounded_channel();
    let peer_id = data_encoding::BASE32_NOPAD
        .encode(endpoint.id().as_bytes())
        .to_ascii_lowercase();

    let probe = mdns_discoverer(peer_id.clone(), event_tx.clone())
        .with_cadence(MDNS_STARTUP_PROBE_CADENCE)
        .with_response_rate(2.5)
        .with_ip_class(IpClass::Auto)
        .spawn(&tokio::runtime::Handle::current())
        .map_err(|e| format!("{e}"))?;

    tokio::spawn(async move {
        tokio::time::sleep(MDNS_STARTUP_PROBE_RUNTIME).await;
        drop(probe);
    });

    let mut discoverer = mdns_discoverer(peer_id, event_tx).with_ip_class(IpClass::Auto);

    for (port, addrs) in local_mdns_addrs(endpoint) {
        discoverer = discoverer.with_addrs(port, addrs);
    }

    let guard = discoverer
        .spawn(&tokio::runtime::Handle::current())
        .map_err(|e| format!("{e}"))?;

    Ok((guard, event_rx, memory_lookup))
}

fn mdns_discoverer(
    peer_id: String,
    event_tx: mpsc::UnboundedSender<PeerDiscoveryEvent>,
) -> Discoverer {
    Discoverer::new(MDNS_SERVICE_NAME.to_string(), peer_id).with_callback(
        move |endpoint_id, peer| {
            let Ok(endpoint_id) = endpoint_id.parse::<EndpointId>() else {
                return;
            };

            let event = if peer.is_expiry() {
                PeerDiscoveryEvent::Expired(endpoint_id)
            } else {
                PeerDiscoveryEvent::Discovered(peer_to_endpoint_info(peer, endpoint_id))
            };

            let _ = event_tx.send(event);
        },
    )
}

fn local_mdns_addrs(endpoint: &Endpoint) -> HashMap<u16, Vec<IpAddr>> {
    let endpoint_addr = endpoint.addr();
    endpoint_addr_mdns_addrs(&endpoint_addr)
}

fn endpoint_addr_mdns_addrs(endpoint_addr: &EndpointAddr) -> HashMap<u16, Vec<IpAddr>> {
    let mut addrs: HashMap<u16, Vec<IpAddr>> = HashMap::new();

    for socket_addr in endpoint_addr.ip_addrs() {
        addrs
            .entry(socket_addr.port())
            .or_default()
            .push(socket_addr.ip());
    }

    addrs
}

fn peer_to_endpoint_info(peer: &Peer, endpoint_id: EndpointId) -> EndpointInfo {
    let ip_addrs: BTreeSet<SocketAddr> = peer
        .addrs()
        .iter()
        .map(|(ip, port)| SocketAddr::new(*ip, *port))
        .collect();

    EndpointInfo::from_parts(endpoint_id, EndpointData::from(ip_addrs))
}

async fn broadcast_sync_message(
    gossip_sender: &GossipSender,
    msg: Vec<u8>,
    gossip_max_message_size: usize,
) {
    let len = msg.len();
    if len > gossip_max_message_size {
        log::error!(
            target: LOG_TARGET,
            "Outgoing sync message is too large ({} bytes, default limit {} bytes); message was not sent",
            len,
            gossip_max_message_size
        );
        return;
    }

    log::info!(
        target: LOG_TARGET,
        "Broadcasting sync message ({} bytes)",
        len
    );

    if let Err(e) = gossip_sender.broadcast(msg.into()).await {
        log::error!(target: LOG_TARGET, "Could not broadcast sync message over gossip: {e:?}");
    }
}

fn should_join_peer(
    local_id: EndpointId,
    node_id: EndpointId,
    connected_peers: &HashSet<EndpointId>,
    joined_peers: &HashSet<EndpointId>,
) -> bool {
    node_id != local_id && !connected_peers.contains(&node_id) && !joined_peers.contains(&node_id)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dole_MainActivity_initNdkContext<'local>(
    mut unowned_env: jni::EnvUnowned<'local>,
    _class: jni::objects::JClass<'local>,
    context: jni::objects::JObject<'local>,
) {
    let _ = unowned_env.with_env(|env| {
        let vm = env.get_java_vm().expect("Failed to get JavaVM");
        let context_ref = env
            .new_global_ref(&context)
            .expect("Failed to create global ref");

        unsafe {
            ndk_context::initialize_android_context(
                vm.get_raw() as *mut std::ffi::c_void,
                context_ref.as_obj().as_raw() as *mut std::ffi::c_void,
            );
        }

        std::mem::forget(context_ref);
        Ok::<(), jni::errors::Error>(())
    });
}
