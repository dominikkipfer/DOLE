use gix::bstr::ByteSlice;
use std::collections::HashMap;
use std::ops::ControlFlow;
use std::sync::Arc;

use crate::crypto::signature_hex_to_der_hex;

use super::LedgerStateListener;
use super::repo::for_each_commit;

pub(super) fn notify_ui_internal(repo: &gix::Repository, my_key: &str, listener: &Arc<dyn LedgerStateListener>) {
    let is_observer = my_key.is_empty();
    let mut raw_history = Vec::new();

    if let Ok(refs) = repo.references()
        && let Ok(branches) = refs.local_branches()
    {
        for b in branches.flatten() {
            let b_name = b.name().shorten().to_string();
            for_each_commit(repo, &b_name, |id, commit| {
                if let Ok(decoded) = commit.decode()
                    && let Ok(author_sig) = decoded.author()
                    && let Ok(committer_sig) = decoded.committer()
                    && let Ok(seq) = u64::try_from(author_sig.seconds())
                {
                    let t = author_sig.name.to_str_lossy().into_owned();
                    let payload_s = author_sig.email.to_str_lossy().into_owned();
                    let target = committer_sig.name.to_str_lossy().into_owned();
                    let ts = committer_sig.seconds();
                    let sig = committer_sig.email.to_str_lossy().into_owned();

                    let cum_val = if t == "G" {
                        0
                    } else {
                        payload_s.parse::<i64>().unwrap_or(0)
                    };

                    let is_own_branch = b_name.eq_ignore_ascii_case(my_key);
                    let is_incoming_send = t == "S" && target.eq_ignore_ascii_case(my_key);
                    let is_peer_metadata = t == "G";
                    if is_observer || is_own_branch || is_incoming_send || is_peer_metadata {
                        raw_history.push((
                            id.to_hex().to_string(),
                            t,
                            b_name.clone(),
                            target,
                            seq,
                            ts,
                            sig,
                            cum_val,
                            payload_s
                        ));
                    }
                }
                ControlFlow::Continue(())
            });
        }
    }

    raw_history.sort_by(|a, b| a.2.cmp(&b.2).then_with(|| a.4.cmp(&b.4)));

    let mut tracker = HashMap::new();
    let mut history = Vec::new();
    let mut balance = 0i64;

    for (id, t, b_name, target, seq, ts, sig, cum_val, payload_s) in raw_history {
        let key = if t == "S" {
            format!("{}_{}_{}", b_name, t, target)
        } else {
            format!("{}_{}", b_name, t)
        };
        let prev_val = *tracker.get(&key).unwrap_or(&0i64);
        let delta = cum_val - prev_val;
        tracker.insert(key, cum_val);

        let is_own_branch = b_name.eq_ignore_ascii_case(my_key);
        let is_incoming_send = t == "S" && target.eq_ignore_ascii_case(my_key);

        if is_own_branch {
            if t == "M" || t == "G" {
                balance += delta;
            } else if t == "B" || t == "S" {
                balance -= delta;
            }
        } else if is_incoming_send {
            balance += delta;
        }

        let f_t = match t.as_str() {
            "G" => "GENESIS",
            "M" => "MINT",
            "B" => "BURN",
            "S" => "SEND",
            _ => "UNK"
        };

        let display_target = if t == "G" {
            signature_hex_to_der_hex(&target).unwrap_or_else(|| target.clone())
        } else {
            target.clone()
        };
        let display_sig = signature_hex_to_der_hex(&sig).unwrap_or_else(|| sig.clone());

        let safe_target = if display_target == "-" {
            "".to_string()
        } else {
            display_target.clone()
        };

        let extra = if t == "G" {
            format!(
                r#","certificate":"{}","publicKey":"{}" "#,
                display_target, payload_s
            )
        } else if t == "S" {
            format!(r#","counter":{}"#, cum_val)
        } else {
            String::new()
        };

        history.push((
            ts,
            format!(
                r#"{{"id":"{}","type":"{}","goc":{},"author":"{}","target":"{}","seq":{},"timestamp":{},"signature":"{}"{} }}"#,
                id, f_t, delta, b_name, safe_target, seq, ts, display_sig, extra
            )
        ));
    }

    history.sort_by(|a, b| b.0.cmp(&a.0));
    let sorted: Vec<String> = history.into_iter().map(|k| k.1).collect();

    listener.on_state_updated(balance, format!("[{}]", sorted.join(",")));
}
