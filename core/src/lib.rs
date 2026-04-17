uniffi::setup_scaffolding!();

use gix::{self, objs::{Commit, Tree}, date::Time, actor::Signature};
use std::path::PathBuf;
use std::sync::Arc;
use std::net::UdpSocket;
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};
use std::sync::atomic::{AtomicBool, Ordering};

pub mod constants;

#[derive(uniffi::Record)]
pub struct GitResult {
    pub success: bool,
    pub message: String,
}

#[uniffi::export(callback_interface)]
pub trait LedgerStateListener: Send + Sync {
    fn on_state_updated(&self, balance: i32, transaction_history_json: String);
}

#[derive(uniffi::Object)]
pub struct PrototypeEngine {
    listener: Option<Arc<dyn LedgerStateListener>>,
    public_key: String,
    repo_path: PathBuf,
    instance_id: String,
}

#[uniffi::export]
impl PrototypeEngine {
    #[uniffi::constructor]
    pub fn init_prototype(listener: Box<dyn LedgerStateListener>, storage_path: String) -> Self {
        let listener_arc: Arc<dyn LedgerStateListener> = listener.into();

        let instance_id = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos().to_string();
        let udp_instance_id = instance_id.clone();

        let public_key = "PubKey_12345ABC".to_string();
        let repo_path = PathBuf::from(storage_path).join("dole_ledger_proto");

        let mut error_msg = String::new();

        if gix::open(&repo_path).is_err() {
            if let Err(e) = gix::init(&repo_path) {
                error_msg = format!("INIT ERROR: {}", e);
            }
        }

        let config_path = repo_path.join(".git").join("config");
        if let Ok(current_config) = std::fs::read_to_string(&config_path) {
            if !current_config.contains("DOLE System") {
                let extra_config = "\n[user]\n\tname = DOLE System\n\temail = system@dole.local\n";
                let _ = std::fs::write(&config_path, current_config + extra_config);
            }
        }

        let ui_needs_update = Arc::new(AtomicBool::new(false));

        let udp_repo_path = repo_path.clone();
        let udp_listener = listener_arc.clone();
        let udp_ui_needs_update = ui_needs_update.clone();

        thread::spawn(move || {
            match UdpSocket::bind("0.0.0.0:8888") {
                Ok(socket) => {
                    let mut buf = [0; 2048];
                    loop {
                        if let Ok((amt, _src)) = socket.recv_from(&mut buf) {
                            if let Ok(msg) = std::str::from_utf8(&buf[..amt]) {
                                let parts: Vec<&str> = msg.splitn(3, '|').collect();
                                if parts.len() == 3 {
                                    let sender_instance = parts[0];
                                    let branch = parts[1];
                                    let tx_data = parts[2];

                                    if sender_instance != udp_instance_id {
                                        if branch == "SYSTEM" && tx_data == "SYNC_REQ" {
                                            broadcast_all_history(&udp_repo_path, &udp_instance_id);
                                        }
                                        else {
                                            let tx_parts: Vec<&str> = tx_data.split(',').collect();
                                            let incoming_seq = tx_parts.get(3)
                                                .unwrap_or(&"0")
                                                .trim()
                                                .parse::<i32>()
                                                .unwrap_or(0);

                                            let local_seq = get_latest_seq_for_branch(&udp_repo_path, branch);

                                            if incoming_seq > local_seq {
                                                let _ = write_commit(&udp_repo_path, branch, tx_data);
                                                udp_ui_needs_update.store(true, Ordering::Relaxed);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    let err_json = format!("[{{\"type\":\"ERROR\",\"goc\":0,\"target\":\"UDP Port blockiert! ({})\",\"seq\":0,\"timestamp\":0}}]", e);
                    udp_listener.on_state_updated(-999, err_json);
                }
            }
        });

        let debouncer_repo_path = repo_path.clone();
        let debouncer_pub_key = public_key.clone();
        let debouncer_listener = listener_arc.clone();
        let debouncer_ui_needs_update = ui_needs_update.clone();

        thread::spawn(move || {
            loop {
                thread::sleep(std::time::Duration::from_millis(150));

                if debouncer_ui_needs_update.swap(false, Ordering::Relaxed) {
                    notify_ui_internal(&debouncer_repo_path, &debouncer_pub_key, &debouncer_listener);
                }
            }
        });

        let sync_instance_id = instance_id.clone();

        let engine = PrototypeEngine {
            listener: Some(listener_arc.clone()),
            public_key: public_key.clone(),
            repo_path: repo_path.clone(),
            instance_id,
        };

        if !error_msg.is_empty() {
            listener_arc.on_state_updated(-999, error_msg);
        } else {
            notify_ui_internal(&repo_path, &public_key, &listener_arc);
        }

        thread::spawn(move || {
            for _ in 0..3 {
                thread::sleep(std::time::Duration::from_millis(1500));
                broadcast_tx(&sync_instance_id, "SYSTEM", "SYNC_REQ");
            }
            loop {
                thread::sleep(std::time::Duration::from_secs(10));
                broadcast_tx(&sync_instance_id, "SYSTEM", "SYNC_REQ");
            }
        });

        engine
    }

    pub fn genesis(&self, amount: i32) {
        let seq = self.get_latest_seq() + 1;
        self.handle_tx_result(self.commit_internal(format!("GENESIS,{},,{}", amount, seq)));
    }

    pub fn mint(&self, amount: i32) {
        let seq = self.get_latest_seq() + 1;
        self.handle_tx_result(self.commit_internal(format!("MINT,{},,{}", amount, seq)));
    }

    pub fn burn(&self, amount: i32) {
        let seq = self.get_latest_seq() + 1;
        self.handle_tx_result(self.commit_internal(format!("BURN,{},,{}", amount, seq)));
    }

    pub fn send(&self, target_pub_key: String, amount: i32) {
        let seq = self.get_latest_seq() + 1;
        self.handle_tx_result(self.commit_internal(format!("SEND,{},{},{}", amount, target_pub_key, seq)));
    }
}

impl PrototypeEngine {
    fn get_latest_seq(&self) -> i32 {
        get_latest_seq_for_branch(&self.repo_path, &self.public_key)
    }

    fn handle_tx_result(&self, res: GitResult) {
        if let Some(l) = &self.listener {
            if !res.success {
                l.on_state_updated(-999, format!("TX ERROR: {}", res.message));
            } else {
                notify_ui_internal(&self.repo_path, &self.public_key, l);
            }
        }
    }

    fn commit_internal(&self, tx_payload: String) -> GitResult {
        match write_commit(&self.repo_path, &self.public_key, &tx_payload) {
            Ok(hash) => {
                broadcast_tx(&self.instance_id, &self.public_key, &tx_payload);
                GitResult { success: true, message: hash }
            },
            Err(e) => GitResult { success: false, message: e }
        }
    }
}

fn broadcast_tx(instance_id: &str, branch: &str, tx_data: &str) {
    if let Ok(socket) = UdpSocket::bind("0.0.0.0:0") {
        let _ = socket.set_broadcast(true);
        let msg = format!("{}|{}|{}", instance_id, branch, tx_data);
        let _ = socket.send_to(msg.as_bytes(), "255.255.255.255:8888");
    }
}

fn write_commit(repo_path: &PathBuf, branch_name: &str, tx_payload: &str) -> Result<String, String> {
    let repo = gix::open(repo_path).map_err(|e| e.to_string())?;

    let empty_tree = Tree::empty();
    let tree_id = repo.write_object(&empty_tree).map_err(|e| e.to_string())?.detach();

    let branch_ref_path = format!("refs/heads/{}", branch_name);
    let parent_opt = repo.find_reference(&branch_ref_path).ok().map(|r| r.id().detach());

    let mut parents_vec = Vec::new();
    if let Some(pid) = parent_opt { parents_vec.push(pid); }

    let sig = Signature {
        name: "DOLE System".into(),
        email: "system@dole.local".into(),
        time: Time::now_local_or_utc(),
    };

    let commit = Commit {
        tree: tree_id,
        parents: parents_vec.into(),
        author: sig.clone(),
        committer: sig,
        encoding: None,
        message: tx_payload.into(),
        extra_headers: vec![],
    };

    let commit_id = repo.write_object(&commit).map_err(|e| e.to_string())?.detach();

    let branch_edit = gix::refs::transaction::RefEdit {
        change: gix::refs::transaction::Change::Update {
            log: gix::refs::transaction::LogChange {
                mode: gix::refs::transaction::RefLog::AndReference,
                force_create_reflog: false,
                message: "tx".into(),
            },
            expected: match parent_opt {
                Some(pid) => gix::refs::transaction::PreviousValue::ExistingMustMatch(gix::refs::Target::Object(pid)),
                None => gix::refs::transaction::PreviousValue::MustNotExist,
            },
            new: gix::refs::Target::Object(commit_id),
        },
        name: branch_ref_path.try_into().unwrap(),
        deref: false,
    };

    repo.edit_reference(branch_edit).map_err(|e| e.to_string())?;
    Ok(commit_id.to_hex().to_string())
}

fn notify_ui_internal(repo_path: &PathBuf, my_pub_key: &str, listener: &Arc<dyn LedgerStateListener>) {
    let repo = match gix::open(repo_path) {
        Ok(r) => r,
        Err(_) => return,
    };

    let mut balance = 0;
    let mut history_json_array = Vec::new();

    if let Ok(refs) = repo.references() {
        if let Ok(local_branches) = refs.local_branches() {
            for branch_res in local_branches {
                if let Ok(branch_ref) = branch_res {
                    let branch_name = branch_ref.name().shorten().to_string();
                    let mut curr_id_opt = Some(branch_ref.id().detach());

                    while let Some(curr_id) = curr_id_opt {
                        if let Ok(obj) = repo.find_object(curr_id) {
                            if let Ok(commit) = obj.try_into_commit() {
                                let timestamp = commit.time().map(|t| t.seconds).unwrap_or(0);
                                if let Ok(raw_commit) = commit.decode() {
                                    let msg = String::from_utf8_lossy(&raw_commit.message).to_string();
                                    let parts: Vec<&str> = msg.split(',').collect();

                                    let tx_type = *parts.get(0).unwrap_or(&"");
                                    let goc_str = *parts.get(1).unwrap_or(&"0");
                                    let target = *parts.get(2).unwrap_or(&"");
                                    let seq_str = *parts.get(3).unwrap_or(&"0");

                                    let seq = seq_str.trim().parse::<i32>().unwrap_or(0);

                                    if let Ok(amount) = goc_str.trim().parse::<i32>() {
                                        if branch_name == my_pub_key {
                                            if tx_type.trim() == "MINT" || tx_type.trim() == "GENESIS" { balance += amount; }
                                            else if tx_type.trim() == "BURN" || tx_type.trim() == "SEND" { balance -= amount; }
                                        } else {
                                            if tx_type.trim() == "SEND" && target.trim() == my_pub_key {
                                                balance += amount;
                                            }
                                        }
                                    }

                                    let json_str = format!("{{\"type\":\"{}\",\"goc\":{},\"target\":\"{}\",\"seq\":{},\"timestamp\":{}}}",
                                                           tx_type.trim(),
                                                           goc_str.trim(),
                                                           target.trim(),
                                                           seq,
                                                           timestamp
                                    );

                                    history_json_array.push((seq, json_str));
                                }
                                curr_id_opt = commit.parent_ids().next().map(|id| id.detach());
                            } else { break; }
                        } else { break; }
                    }
                }
            }
        }
    }

    history_json_array.sort_by_key(|k| k.0);
    let mut sorted_json: Vec<String> = history_json_array.into_iter().map(|k| k.1).collect();
    sorted_json.reverse();

    listener.on_state_updated(balance, format!("[{}]", sorted_json.join(",")));
}

fn get_latest_seq_for_branch(repo_path: &PathBuf, branch_name: &str) -> i32 {
    if let Ok(repo) = gix::open(repo_path) {
        let branch_ref_path = format!("refs/heads/{}", branch_name);
        if let Ok(reference) = repo.find_reference(&branch_ref_path) {
            if let Ok(obj) = repo.find_object(reference.id().detach()) {
                if let Ok(commit) = obj.try_into_commit() {
                    if let Ok(raw_commit) = commit.decode() {
                        let msg = String::from_utf8_lossy(&raw_commit.message).to_string();
                        let parts: Vec<&str> = msg.split(',').collect();
                        if let Some(seq_str) = parts.get(3) {
                            return seq_str.trim().parse::<i32>().unwrap_or(0);
                        }
                    }
                }
            }
        }
    }
    0
}

fn broadcast_all_history(repo_path: &PathBuf, instance_id: &str) {
    let repo = match gix::open(repo_path) {
        Ok(r) => r,
        Err(_) => return,
    };

    if let Ok(refs) = repo.references() {
        if let Ok(local_branches) = refs.local_branches() {
            for branch_res in local_branches {
                if let Ok(branch_ref) = branch_res {
                    let branch_name = branch_ref.name().shorten().to_string();
                    let mut curr_id_opt = Some(branch_ref.id().detach());
                    let mut history_to_send = Vec::new();

                    while let Some(curr_id) = curr_id_opt {
                        if let Ok(obj) = repo.find_object(curr_id) {
                            if let Ok(commit) = obj.try_into_commit() {
                                if let Ok(raw_commit) = commit.decode() {
                                    let msg = String::from_utf8_lossy(&raw_commit.message).to_string();
                                    history_to_send.push(msg);
                                }
                                curr_id_opt = commit.parent_ids().next().map(|id| id.detach());
                            } else { break; }
                        } else { break; }
                    }

                    history_to_send.reverse();
                    for tx_payload in history_to_send {
                        broadcast_tx(instance_id, &branch_name, &tx_payload);
                        thread::sleep(std::time::Duration::from_millis(5));
                    }
                }
            }
        }
    }
}