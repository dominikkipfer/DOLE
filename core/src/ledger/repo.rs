use gix::bstr::{BString, ByteSlice};
use gix::{actor::Signature, date::{OffsetInSeconds, SecondsSinceUnixEpoch, Time}, objs::{Commit, Tree}};
use std::collections::HashMap;
use std::ops::ControlFlow;
use std::path::PathBuf;

use crate::crypto::{person_id_hex_from_id_or_pubkey_hex, verify_genesis_tx};
use crate::sync::{TxHeader, type_from_tx_label};

pub(super) const RECOVERY_ID_HEADER: &str = "dole-recovery-id";
pub(super) const GENESIS_REF_PREFIX: &str = "refs/dole-genesis/";

pub(super) struct CommitInput<'a> {
    pub(super) branch: &'a str,
    pub(super) tx_t: &'a str,
    pub(super) target: &'a str,
    pub(super) goc: &'a str,
    pub(super) seq: u64,
    pub(super) ts: u64,
    pub(super) sig: &'a str,
    pub(super) recovery_id: u8
}

pub(super) struct KnownBranchTx {
    pub(super) tx_t: String,
    pub(super) target: String,
    pub(super) payload: String,
    pub(super) sig: String,
    pub(super) recovery_id: Option<u8>
}

impl KnownBranchTx {
    pub(super) fn matches_sync_tx(
        &self,
        tx_t: &str,
        target: &str,
        payload: &str,
        sig: &str,
        recovery_id: u8
    ) -> bool {
        self.tx_t == tx_t
            && self.target.eq_ignore_ascii_case(target)
            && self.payload.eq_ignore_ascii_case(payload)
            && self.sig.eq_ignore_ascii_case(sig)
            && self.recovery_id == Some(recovery_id)
    }
}

pub(super) fn for_each_commit(
    repo: &gix::Repository,
    branch: &str,
    mut visit: impl FnMut(gix::ObjectId, &gix::Commit<'_>) -> ControlFlow<()>
) {
    let branch_ref = format!("refs/heads/{branch}");
    let Ok(reference) = repo.find_reference(branch_ref.as_str()) else {
        return;
    };

    let mut current = Some(reference.id().detach());
    while let Some(id) = current {
        let Ok(object) = repo.find_object(id) else {
            break;
        };
        let Ok(commit) = object.try_into_commit() else {
            break;
        };
        current = commit.parent_ids().next().map(|parent| parent.detach());
        if visit(id, &commit).is_break() {
            break;
        }
    }
}

pub(super) fn ensure_repo_initialized(repo_path: &PathBuf) {
    if gix::open(repo_path).is_err()
        && let Err(e) = gix::init(repo_path)
    {
        log::error!(target: "dole::ledger", "Failed to initialize local ledger repository: {e}");
        return;
    }

    let config_path = repo_path.join(".git").join("config");
    if let Ok(current_config) = std::fs::read_to_string(&config_path)
        && !current_config.contains("DOLE System")
    {
        let extra_config = "\n[user]\n\tname = DOLE System\n\temail = system@dole.local\n";
        if let Err(e) = std::fs::write(&config_path, current_config + extra_config) {
            log::warn!(target: "dole::ledger", "Failed to update local ledger git config: {e}");
        }
    }
}

pub(super) fn target_for_commit(tx_t: &str, target: &str) -> String {
    if tx_t == "S" {
        return person_id_hex_from_id_or_pubkey_hex(target).unwrap_or_else(|| target.to_string());
    }

    target.to_string()
}

pub(super) fn get_pubkey_from_genesis(repo: &gix::Repository, branch: &str) -> Option<String> {
    if let Some(public_key) = genesis_pubkey_from_ref(repo, branch) {
        return Some(public_key);
    }

    let mut found = None;
    for_each_commit(repo, branch, |_id, commit| {
        if let Some(public_key) = genesis_pubkey_from_commit(commit) {
            found = Some(public_key);
            return ControlFlow::Break(());
        }
        ControlFlow::Continue(())
    });

    found
}

fn genesis_pubkey_from_ref(repo: &gix::Repository, branch: &str) -> Option<String> {
    let ref_name = format!("{GENESIS_REF_PREFIX}{branch}");
    let reference = repo.find_reference(ref_name.as_str()).ok()?;
    let commit = repo.find_object(reference.id().detach()).ok()?.try_into_commit().ok()?;
    genesis_pubkey_from_commit(&commit)
}

fn genesis_pubkey_from_commit(commit: &gix::Commit<'_>) -> Option<String> {
    let decoded = commit.decode().ok()?;
    let author_sig = decoded.author().ok()?;
    if author_sig.name.to_str_lossy() != "G" {
        return None;
    }
    let committer_sig = decoded.committer().ok()?;
    let public_key = author_sig.email.to_str_lossy();
    let cert = committer_sig.name.to_str_lossy();
    let sig = committer_sig.email.to_str_lossy();
    verify_genesis_tx(public_key.as_ref(), sig.as_ref(), cert.as_ref())
        .then(|| public_key.into_owned())
}

pub(super) fn write_commit(repo: &gix::Repository, input: CommitInput<'_>) -> Result<String, String> {
    let CommitInput {
        branch, tx_t, target, goc, seq, ts, sig, recovery_id
    } = input;
    let tx_type = type_from_tx_label(tx_t).ok_or_else(|| format!("invalid type: {tx_t}"))?;
    TxHeader::checked(tx_type, recovery_id, 0)
        .ok_or_else(|| format!("invalid recovery-id for tx type {tx_t}: {recovery_id}"))?;
    let seq_seconds = i64::try_from(seq).map_err(|_| format!("sequence is too large for git time: {seq}"))?;
    let ts_seconds = i64::try_from(ts).map_err(|_| format!("timestamp is too large for git time: {ts}"))?;

    let tree_id = repo.write_object(Tree::empty()).map_err(|e| e.to_string())?.detach();

    let branch_ref = format!("refs/heads/{}", branch);
    let parent = repo.find_reference(&branch_ref).ok().map(|r| r.id().detach());
    let mut parents = Vec::new();
    if let Some(p) = parent {
        parents.push(p);
    }

    let author = Signature {
        name: tx_t.into(),
        email: goc.into(),
        time: Time {
            seconds: seq_seconds as SecondsSinceUnixEpoch,
            offset: 0 as OffsetInSeconds
        }
    };

    let committer = Signature {
        name: target.into(),
        email: sig.into(),
        time: Time {seconds: ts_seconds as SecondsSinceUnixEpoch, offset: 0 as OffsetInSeconds}
    };

    let commit = Commit {
        tree: tree_id,
        parents: parents.into(),
        author,
        committer,
        encoding: None,
        message: "".into(),
        extra_headers: vec![(
            BString::from(RECOVERY_ID_HEADER.as_bytes().to_vec()),
            BString::from(recovery_id.to_string().into_bytes())
        )]
    };

    let id = repo.write_object(&commit).map_err(|e| e.to_string())?.detach();

    let branch_full_name = format!("refs/heads/{}", branch);
    let edit = gix::refs::transaction::RefEdit {
        change: gix::refs::transaction::Change::Update {
            log: gix::refs::transaction::LogChange {
                mode: gix::refs::transaction::RefLog::AndReference,
                force_create_reflog: false,
                message: "tx".into()
            },
            expected: match parent {
                Some(pid) => gix::refs::transaction::PreviousValue::ExistingMustMatch(
                    gix::refs::Target::Object(pid)
                ),
                None => gix::refs::transaction::PreviousValue::MustNotExist
            },
            new: gix::refs::Target::Object(id)
        },
        name: branch_full_name.as_str().try_into().map_err(|e| format!("{:?}", e))?,
        deref: false
    };

    repo.edit_reference(edit).map_err(|e| e.to_string())?;

    if tx_t == "G" {
        write_genesis_ref(repo, branch, id);
    }

    Ok(id.to_hex().to_string())
}

fn write_genesis_ref(repo: &gix::Repository, branch: &str, genesis_id: gix::ObjectId) {
    let ref_name = format!("{GENESIS_REF_PREFIX}{branch}");
    let name: gix::refs::FullName = match ref_name.as_str().try_into() {
        Ok(name) => name,
        Err(e) => {
            log::warn!(target: "dole::ledger", "genesis ref name invalid for {branch}: {e:?}");
            return;
        }
    };

    let edit = gix::refs::transaction::RefEdit {
        change: gix::refs::transaction::Change::Update {
            log: gix::refs::transaction::LogChange {
                mode: gix::refs::transaction::RefLog::AndReference,
                force_create_reflog: false,
                message: "genesis".into()
            },
            expected: gix::refs::transaction::PreviousValue::Any,
            new: gix::refs::Target::Object(genesis_id)
        },
        name,
        deref: false
    };

    if let Err(e) = repo.edit_reference(edit) {
        log::warn!(target: "dole::ledger", "failed to write genesis ref for {branch}: {e}");
    }
}

pub(super) fn get_latest_seq_for_branch(repo: &gix::Repository, branch: &str) -> Option<u64> {
    branch_sequences(repo, branch).into_iter().max()
}

pub(super) fn branch_has_seq(repo: &gix::Repository, branch: &str, seq: u64) -> bool {
    branch_sequences(repo, branch).into_iter().any(|known| known == seq)
}

pub(super) fn branch_sequences_and_tx_at(
    repo: &gix::Repository,
    branch: &str,
    seq: u64
) -> (Vec<u64>, Option<KnownBranchTx>) {
    let mut sequences = Vec::new();
    let mut found = None;
    for_each_commit(repo, branch, |_id, commit| {
        let Ok(decoded) = commit.decode() else {
            return ControlFlow::Continue(());
        };
        let Ok(author) = decoded.author() else {
            return ControlFlow::Continue(());
        };
        let Ok(known_seq) = u64::try_from(author.seconds()) else {
            return ControlFlow::Continue(());
        };
        sequences.push(known_seq);

        let Ok(committer) = decoded.committer() else {
            return ControlFlow::Continue(());
        };
        if known_seq == seq && found.is_none() {
            let recovery_id = decoded
                .extra_headers()
                .find(RECOVERY_ID_HEADER)
                .and_then(|value| value.to_str_lossy().as_ref().parse::<u8>().ok())
                .filter(|recovery_id| *recovery_id <= 3);
            found = Some(KnownBranchTx {
                tx_t: author.name.to_str_lossy().into_owned(),
                target: committer.name.to_str_lossy().into_owned(),
                payload: author.email.to_str_lossy().into_owned(),
                sig: committer.email.to_str_lossy().into_owned(),
                recovery_id
            });
        }
        ControlFlow::Continue(())
    });

    (sequences, found)
}

pub(super) fn highest_contiguous_seq(mut sequences: Vec<u64>) -> Option<u64> {
    sequences.sort_unstable();

    let mut expected = 0;
    let mut frontier = None;
    for seq in sequences {
        if seq < expected {
            continue;
        }
        if seq != expected {
            break;
        }

        frontier = Some(seq);
        if expected == u64::MAX {
            break;
        }
        expected += 1;
    }

    frontier
}

pub(super) fn contiguous_sequences(repo: &gix::Repository) -> HashMap<String, u64> {
    let mut sequences = HashMap::new();
    if let Ok(refs) = repo.references()
        && let Ok(branches) = refs.local_branches()
    {
        for b in branches.flatten() {
            let branch = b.name().shorten().to_string();
            if let Some(frontier) = highest_contiguous_seq(branch_sequences(repo, &branch)) {
                sequences.insert(branch, frontier);
            }
        }
    }

    sequences
}

pub(super) fn branch_sequences(repo: &gix::Repository, branch: &str) -> Vec<u64> {
    let mut sequences = Vec::new();
    for_each_commit(repo, branch, |_id, commit| {
        if let Ok(decoded) = commit.decode()
            && let Ok(author) = decoded.author()
            && let Ok(seq) = u64::try_from(author.seconds())
        {
            sequences.push(seq);
        }
        ControlFlow::Continue(())
    });
    sequences
}
