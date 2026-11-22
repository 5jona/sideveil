//! `sideveil-ctl logs ...` subcommand parser (`--clear`, `--since-seq`, `--limit`, `--level`, `--source`).

use serde_json::{Map, Value, json};
use sideveil_protocol::rpc::methods;

use super::{CliAction, OutputMode};

pub(super) fn parse_logs_subcommand(args: &[String]) -> Result<CliAction, (u8, String)> {
    let mut clear = false;
    let mut since_seq: Option<u64> = None;
    let mut limit: Option<usize> = None;
    let mut level: Option<String> = None;
    let mut source: Option<String> = None;

    let mut idx = 0;
    while idx < args.len() {
        match args[idx].as_str() {
            "--clear" => {
                clear = true;
                idx += 1;
            }
            "--since-seq" => {
                let Some(raw) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --since-seq".to_owned()));
                };
                let parsed = raw
                    .parse::<u64>()
                    .map_err(|_| (2, format!("Invalid integer for --since-seq: {raw}")))?;
                since_seq = Some(parsed);
                idx += 2;
            }
            "--limit" => {
                let Some(raw) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --limit".to_owned()));
                };
                let parsed = raw
                    .parse::<usize>()
                    .map_err(|_| (2, format!("Invalid integer for --limit: {raw}")))?;
                limit = Some(parsed);
                idx += 2;
            }
            "--level" => {
                let Some(raw) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --level".to_owned()));
                };
                let normalized = raw.trim().to_ascii_lowercase();
                if !matches!(normalized.as_str(), "info" | "warn" | "error") {
                    return Err((
                        2,
                        format!("Invalid --level '{raw}'. Expected one of: info, warn, error"),
                    ));
                }
                level = Some(normalized);
                idx += 2;
            }
            "--source" => {
                let Some(raw) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --source".to_owned()));
                };
                source = Some(raw.clone());
                idx += 2;
            }
            other => {
                return Err((
                    2,
                    format!(
                        "Unexpected argument '{other}' for logs. Usage: sideveil-ctl logs [--clear] [--since-seq <n>] [--limit <n>] [--level <info|warn|error>] [--source <str>]"
                    ),
                ));
            }
        }
    }

    if clear {
        if since_seq.is_some() || limit.is_some() || level.is_some() || source.is_some() {
            return Err((
                2,
                "Cannot combine --clear with log query flags (--since-seq/--limit/--level/--source)"
                    .to_owned(),
            ));
        }
        return Ok(CliAction::Rpc {
            method: methods::DAEMON_LOGS_CLEAR.to_owned(),
            params: None,
            output_mode: OutputMode::FullEnvelope,
        });
    }

    let mut map = Map::new();
    if let Some(seq) = since_seq {
        map.insert("sinceSeq".to_owned(), json!(seq));
    }
    if let Some(lim) = limit {
        map.insert("limit".to_owned(), json!(lim));
    }
    if let Some(lvl) = level {
        map.insert("level".to_owned(), json!(lvl));
    }
    if let Some(src) = source {
        map.insert("source".to_owned(), json!(src));
    }

    let params = if map.is_empty() {
        None
    } else {
        Some(Value::Object(map))
    };

    Ok(CliAction::Rpc {
        method: methods::DAEMON_LOGS.to_owned(),
        params,
        output_mode: OutputMode::FullEnvelope,
    })
}
