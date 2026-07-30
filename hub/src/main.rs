mod constants;

use std::error::Error;
use std::fs;
use std::path::{Path, PathBuf};

use constants::IROH_GOSSIP_TOPIC_BYTES;
use iroh::{
    Endpoint, RelayMode, SecretKey,
    address_lookup::{DnsAddressLookup, PkarrPublisher, PkarrResolver},
    endpoint::presets, protocol::Router
};
use iroh_gossip::{Gossip, TopicId};

const KEY_BYTES: usize = 32;

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let storage = std::env::var("HUB_STORAGE").unwrap_or_else(|_| "/var/lib/dole-hub".into());
    fs::create_dir_all(&storage)?;

    let secret_key = SecretKey::from_bytes(&load_or_create_key(&PathBuf::from(&storage).join("hub.key"))?);
    log::info!("hub id={}", secret_key.public());

    if std::env::args().any(|arg| arg == "--print-id") {
        return Ok(());
    }

    let endpoint = Endpoint::builder(presets::Minimal)
        .secret_key(secret_key)
        .relay_mode(RelayMode::Default)
        .address_lookup(PkarrPublisher::n0_dns())
        .address_lookup(PkarrResolver::n0_dns())
        .address_lookup(DnsAddressLookup::n0_dns())
        .bind_addr("0.0.0.0:0")?
        .bind()
        .await?;

    let gossip = Gossip::builder().spawn(endpoint.clone());
    let router = Router::builder(endpoint).accept(iroh_gossip::ALPN, gossip.clone()).spawn();
    let _topic = gossip.subscribe(TopicId::from_bytes(IROH_GOSSIP_TOPIC_BYTES), vec![]).await?;

    log::info!("hub running");
    tokio::signal::ctrl_c().await?;
    router.shutdown().await?;
    Ok(())
}

fn load_or_create_key(path: &Path) -> Result<[u8; KEY_BYTES], Box<dyn Error>> {
    if let Ok(existing) = fs::read(path) {
        return Ok(existing.as_slice().try_into()?);
    }

    let mut key = [0u8; KEY_BYTES];
    getrandom::fill(&mut key)?;
    fs::write(path, key)?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }

    Ok(key)
}
