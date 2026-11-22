//! `sideveil-ctl debug ...` subcommand parser (`inspect-ui`, `cdp <on|off|status>`, `reset-frames`).

use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::rpc::{DEFAULT_INSPECT_UI_TIMEOUT_MS, methods};

use super::{CliAction, OutputMode, clamp_rpc_timeout_ms};

const INSPECT_UI_CLI_WIRE_MARGIN_MS: u64 = 2_000;

pub(super) struct ParsedDebugCmd {
    pub(super) action: CliAction,
    pub(super) io_timeout: Option<Duration>,
}

pub(super) fn parse_debug_subcommand(args: &[String]) -> Result<ParsedDebugCmd, (u8, String)> {
    let sub = args.first().map_or("", String::as_str);
    match sub {
        "inspect-ui" => parse_inspect_ui(&args[1..]),
        "cdp" => parse_cdp(&args[1..]),
        "reset-frames" => parse_reset_frames(&args[1..]),
        other => Err((
            2,
            format!(
                "Unknown debug subcommand '{other}'. Usage: sideveil-ctl debug <inspect-ui [--timeout-ms <ms>]|cdp <on|off|status>|reset-frames>"
            ),
        )),
    }
}

fn parse_inspect_ui(args: &[String]) -> Result<ParsedDebugCmd, (u8, String)> {
    let mut timeout_ms: u64 = DEFAULT_INSPECT_UI_TIMEOUT_MS;
    let mut idx = 0;
    while idx < args.len() {
        if args[idx] == "--timeout-ms" {
            let Some(raw) = args.get(idx + 1) else {
                return Err((2, "Missing value for --timeout-ms".to_owned()));
            };
            let parsed = raw
                .parse::<u64>()
                .map_err(|_| (2, format!("Invalid integer for --timeout-ms: {raw}")))?;
            timeout_ms = clamp_rpc_timeout_ms(parsed);
            idx += 2;
        } else {
            return Err((
                2,
                format!(
                    "Unexpected argument '{}' for debug inspect-ui. Usage: sideveil-ctl debug inspect-ui [--timeout-ms <ms>]",
                    args[idx]
                ),
            ));
        }
    }

    Ok(ParsedDebugCmd {
        action: CliAction::Rpc {
            method: methods::DEBUG_INSPECT_UI.to_owned(),
            params: Some(json!({ "timeoutMs": timeout_ms })),
            output_mode: OutputMode::FullEnvelope,
        },
        io_timeout: Some(Duration::from_millis(
            timeout_ms.saturating_add(INSPECT_UI_CLI_WIRE_MARGIN_MS),
        )),
    })
}

fn parse_cdp(args: &[String]) -> Result<ParsedDebugCmd, (u8, String)> {
    let sub = args.first().map_or("status", String::as_str);
    match sub {
        "on" | "off" => {
            let enabled = sub == "on";
            let params: Value = json!({ "enabled": enabled });
            Ok(ParsedDebugCmd {
                action: CliAction::Rpc {
                    method: methods::DEBUG_SET_WEBVIEW_DEBUGGING.to_owned(),
                    params: Some(params),
                    output_mode: OutputMode::FullEnvelope,
                },
                io_timeout: None,
            })
        }
        "status" => Ok(ParsedDebugCmd {
            action: CliAction::Rpc {
                method: methods::DEBUG_CDP_STATUS.to_owned(),
                params: None,
                output_mode: OutputMode::UnwrapResult,
            },
            io_timeout: None,
        }),
        other => Err((
            2,
            format!(
                "Unknown cdp subcommand '{other}'. Usage: sideveil-ctl debug cdp <on|off|status>"
            ),
        )),
    }
}

fn parse_reset_frames(args: &[String]) -> Result<ParsedDebugCmd, (u8, String)> {
    if let Some(extra) = args.first() {
        return Err((
            2,
            format!(
                "Unexpected argument '{extra}' for debug reset-frames. Usage: sideveil-ctl debug reset-frames"
            ),
        ));
    }
    Ok(ParsedDebugCmd {
        action: CliAction::Rpc {
            method: methods::DEBUG_RESET_FRAME_METRICS.to_owned(),
            params: None,
            output_mode: OutputMode::FullEnvelope,
        },
        io_timeout: Some(Duration::from_secs(7)),
    })
}
