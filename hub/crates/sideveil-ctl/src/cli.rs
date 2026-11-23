//! CLI argument parser for `sideveil-ctl`.

mod debug_cmd;
mod hud_cmd;
mod logs_cmd;
mod plugins_cmd;

use std::path::PathBuf;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::paths;
use sideveil_protocol::rpc::methods;

const DEFAULT_IO_TIMEOUT: Duration = Duration::from_secs(4);
const DEFAULT_RAW_RPC_TIMEOUT_MS: u64 = 8_000;
const MIN_RPC_TIMEOUT_MS: u64 = 1_000;
const MAX_RPC_TIMEOUT_MS: u64 = 60_000;

pub(crate) fn clamp_rpc_timeout_ms(ms: u64) -> u64 {
    ms.clamp(MIN_RPC_TIMEOUT_MS, MAX_RPC_TIMEOUT_MS)
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum OutputMode {
    UnwrapResult,
    FullEnvelope,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum CliAction {
    Rpc {
        method: String,
        params: Option<Value>,
        output_mode: OutputMode,
    },
}

#[derive(Debug)]
pub(crate) struct CliInvocation {
    pub(crate) socket_path: PathBuf,
    pub(crate) action: CliAction,
    pub(crate) io_timeout: Duration,
}

pub(crate) fn parse_args(
    args: impl IntoIterator<Item = String>,
) -> Result<CliInvocation, (u8, String)> {
    let mut socket_path = PathBuf::from(paths::HUB_SOCKET);
    let mut positional = Vec::new();
    let mut iter = args.into_iter();
    while let Some(arg) = iter.next() {
        if arg == "--socket" {
            let Some(val) = iter.next() else {
                return Err((2, "Missing value for --socket".to_owned()));
            };
            socket_path = PathBuf::from(val);
        } else {
            positional.push(arg);
        }
    }

    let cmd = positional.first().map_or("status", String::as_str);
    let mut io_timeout = DEFAULT_IO_TIMEOUT;
    let action = match cmd {
        "status" => rpc_action(methods::DAEMON_STATUS, None, OutputMode::UnwrapResult),
        "health" => rpc_action(methods::DAEMON_HEALTH, None, OutputMode::UnwrapResult),
        "open" => parse_open_subcommand(&positional[1..])?,
        "close" => rpc_action(methods::PANEL_CLOSE, None, OutputMode::FullEnvelope),
        "toggle" => rpc_action(methods::PANEL_TOGGLE, None, OutputMode::FullEnvelope),
        "reload" => rpc_action(methods::CONFIG_RELOAD, None, OutputMode::FullEnvelope),
        "restart-host" => rpc_action(methods::HOST_RESTART, None, OutputMode::FullEnvelope),
        "ping" => rpc_action(methods::DAEMON_PING, None, OutputMode::FullEnvelope),
        "logs" => logs_cmd::parse_logs_subcommand(&positional[1..])?,
        "network" => rpc_action(methods::NETWORK_CONTEXT, None, OutputMode::UnwrapResult),
        "plugins" => {
            let parsed = plugins_cmd::parse_plugins_subcommand(&positional[1..])?;
            if let Some(custom_timeout) = parsed.io_timeout {
                io_timeout = custom_timeout;
            }
            CliAction::Rpc {
                method: parsed.method,
                params: parsed.params,
                output_mode: parsed.output_mode,
            }
        }
        "hud" => {
            let (method, params, output_mode) = hud_cmd::parse_hud_subcommand(&positional[1..])?;
            CliAction::Rpc {
                method,
                params,
                output_mode,
            }
        }
        "debug" => {
            let parsed = debug_cmd::parse_debug_subcommand(&positional[1..])?;
            if let Some(custom_timeout) = parsed.io_timeout {
                io_timeout = custom_timeout;
            }
            parsed.action
        }
        "rpc" => {
            let (method, params, custom_timeout) = parse_raw_rpc(&positional[1..])?;
            io_timeout = custom_timeout;
            CliAction::Rpc {
                method,
                params,
                output_mode: OutputMode::FullEnvelope,
            }
        }
        other => return Err((2, format!("Unknown command: {other}"))),
    };

    Ok(CliInvocation {
        socket_path,
        action,
        io_timeout,
    })
}

fn rpc_action(method: &str, params: Option<Value>, output_mode: OutputMode) -> CliAction {
    CliAction::Rpc {
        method: method.to_owned(),
        params,
        output_mode,
    }
}

fn parse_open_subcommand(args: &[String]) -> Result<CliAction, (u8, String)> {
    match args {
        [] => Ok(rpc_action(
            methods::PANEL_OPEN,
            None,
            OutputMode::FullEnvelope,
        )),
        [plugin_id] => {
            let trimmed = plugin_id.trim();
            if trimmed.is_empty() {
                return Err((2, "Usage: sideveil-ctl open [plugin_id]".to_owned()));
            }
            Ok(rpc_action(
                methods::PANEL_OPEN,
                Some(json!({ "pluginId": trimmed })),
                OutputMode::FullEnvelope,
            ))
        }
        _ => Err((2, "Usage: sideveil-ctl open [plugin_id]".to_owned())),
    }
}

fn parse_raw_rpc(args: &[String]) -> Result<(String, Option<Value>, Duration), (u8, String)> {
    let Some(method_name) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl rpc <method> [json-params] [--timeout-ms <ms>]".to_owned(),
        ));
    };
    let mut parsed_params: Option<Value> = None;
    let mut timeout_ms = DEFAULT_RAW_RPC_TIMEOUT_MS;
    let mut idx = 1;
    while idx < args.len() {
        if args[idx] == "--timeout-ms" {
            let Some(raw_ms) = args.get(idx + 1) else {
                return Err((2, "Missing value for --timeout-ms".to_owned()));
            };
            let parsed = raw_ms
                .parse::<u64>()
                .map_err(|_| (2, format!("Invalid integer for --timeout-ms: {raw_ms}")))?;
            timeout_ms = clamp_rpc_timeout_ms(parsed);
            idx += 2;
        } else if parsed_params.is_none() {
            parsed_params = Some(
                serde_json::from_str::<Value>(&args[idx])
                    .map_err(|e| (2, format!("Invalid JSON params: {e}")))?,
            );
            idx += 1;
        } else {
            return Err((2, format!("Unexpected extra argument: {}", args[idx])));
        }
    }
    Ok((
        method_name.clone(),
        parsed_params,
        Duration::from_millis(timeout_ms),
    ))
}

#[cfg(test)]
mod tests;
