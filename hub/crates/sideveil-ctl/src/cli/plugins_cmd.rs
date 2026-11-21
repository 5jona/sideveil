//! `sideveil-ctl plugins ...` subcommand parser (list, reload, install, invoke, scope, dev, audit).

use std::fs;
use std::path::Path;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::plugin::{MAX_PLUGIN_INVOKE_TIMEOUT_MS, MIN_PLUGIN_INVOKE_TIMEOUT_MS};
use sideveil_protocol::rpc::methods;

use super::OutputMode;

pub(super) struct ParsedPluginCmd {
    pub(super) method: String,
    pub(super) params: Option<Value>,
    pub(super) output_mode: OutputMode,
    pub(super) io_timeout: Option<Duration>,
}

pub(super) fn parse_plugins_subcommand(args: &[String]) -> Result<ParsedPluginCmd, (u8, String)> {
    let sub = args.first().map_or("list", String::as_str);
    match sub {
        "list" => Ok(cmd(methods::PLUGINS_LIST, None)),
        "reload" => Ok(cmd(methods::PLUGINS_RELOAD, None)),
        "install" => parse_plugins_install(&args[1..]),
        "invoke" => parse_plugins_invoke(&args[1..]),
        "enable" | "disable" => {
            let Some(plugin_id) = args.get(1) else {
                return Err((2, format!("Usage: sideveil-ctl plugins {sub} <plugin-id>")));
            };
            Ok(cmd(
                methods::PLUGINS_SET_ENABLED,
                Some(json!({
                    "pluginId": plugin_id,
                    "enabled": sub == "enable",
                })),
            ))
        }
        "priority" => {
            let (Some(plugin_id), Some(raw_priority)) = (args.get(1), args.get(2)) else {
                return Err((
                    2,
                    "Usage: sideveil-ctl plugins priority <plugin-id> <priority>".to_owned(),
                ));
            };
            let priority = raw_priority
                .parse::<i32>()
                .map_err(|_| (2, format!("Invalid priority integer: {raw_priority}")))?;
            Ok(cmd(
                methods::PLUGINS_SET_PRIORITY,
                Some(json!({
                    "pluginId": plugin_id,
                    "priority": priority,
                })),
            ))
        }
        "scope" => parse_plugins_scope(&args[1..]),
        "dev" => {
            let (Some(plugin_id), Some(second)) = (args.get(1), args.get(2)) else {
                return Err((
                    2,
                    "Usage: sideveil-ctl plugins dev <plugin-id> <http://127.0.0.1:port|--clear>"
                        .to_owned(),
                ));
            };
            let clear = second == "--clear";
            Ok(cmd(
                methods::PLUGINS_SET_DEV_URL,
                Some(json!({
                    "pluginId": plugin_id,
                    "devServerUrl": if clear { Value::Null } else { Value::String(second.clone()) },
                    "clear": clear,
                })),
            ))
        }
        "audit" => Ok(cmd(methods::PLUGINS_AUDIT, None)),
        "select" => {
            let Some(plugin_id) = args.get(1) else {
                return Err((
                    2,
                    "Usage: sideveil-ctl plugins select <plugin-id> [package-name]".to_owned(),
                ));
            };
            let pkg = args.get(2).cloned();
            Ok(cmd(
                methods::PLUGINS_SELECT,
                Some(json!({
                    "pluginId": plugin_id,
                    "packageName": pkg,
                })),
            ))
        }
        other => Err((2, format!("Unknown plugins subcommand: {other}"))),
    }
}

fn parse_plugins_install(args: &[String]) -> Result<ParsedPluginCmd, (u8, String)> {
    let Some(raw_dir) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins install <source-dir>".to_owned(),
        ));
    };
    let resolved =
        fs::canonicalize(Path::new(raw_dir)).unwrap_or_else(|_| Path::new(raw_dir).to_path_buf());
    Ok(ParsedPluginCmd {
        method: methods::PLUGINS_INSTALL.to_owned(),
        params: Some(json!({
            "sourceDir": resolved.to_string_lossy(),
        })),
        output_mode: OutputMode::FullEnvelope,
        io_timeout: Some(Duration::from_secs(15)),
    })
}

fn parse_plugins_invoke(args: &[String]) -> Result<ParsedPluginCmd, (u8, String)> {
    let (Some(plugin_id), Some(method_name)) = (args.first(), args.get(1)) else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins invoke <plugin-id> <method> [json-params] [--timeout-ms <ms>]"
                .to_owned(),
        ));
    };

    let mut params_val: Option<Value> = None;
    let mut timeout_ms: Option<u64> = None;
    let mut idx = 2;

    while idx < args.len() {
        if args[idx] == "--timeout-ms" {
            let Some(raw_ms) = args.get(idx + 1) else {
                return Err((2, "Missing value for --timeout-ms".to_owned()));
            };
            let parsed = raw_ms
                .parse::<u64>()
                .map_err(|_| (2, format!("Invalid integer for --timeout-ms: {raw_ms}")))?;
            timeout_ms =
                Some(parsed.clamp(MIN_PLUGIN_INVOKE_TIMEOUT_MS, MAX_PLUGIN_INVOKE_TIMEOUT_MS));
            idx += 2;
        } else if params_val.is_none() {
            params_val = Some(
                serde_json::from_str::<Value>(&args[idx])
                    .map_err(|e| (2, format!("Invalid JSON params: {e}")))?,
            );
            idx += 1;
        } else {
            return Err((2, format!("Unexpected extra argument: {}", args[idx])));
        }
    }

    let mut payload = serde_json::Map::new();
    payload.insert("pluginId".to_owned(), Value::String(plugin_id.clone()));
    payload.insert("method".to_owned(), Value::String(method_name.clone()));
    if let Some(p) = params_val {
        payload.insert("params".to_owned(), p);
    }
    if let Some(ms) = timeout_ms {
        payload.insert("timeoutMs".to_owned(), Value::from(ms));
    }

    let io_ms = timeout_ms.unwrap_or(8_000).saturating_add(2_000);
    Ok(ParsedPluginCmd {
        method: methods::PLUGIN_INVOKE.to_owned(),
        params: Some(Value::Object(payload)),
        output_mode: OutputMode::FullEnvelope,
        io_timeout: Some(Duration::from_millis(io_ms)),
    })
}

fn parse_plugins_scope(args: &[String]) -> Result<ParsedPluginCmd, (u8, String)> {
    let Some(plugin_id) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins scope <plugin-id> [--global true|false] [--packages a,b] [--clear]"
                .to_owned(),
        ));
    };

    let mut clear = false;
    let mut global: Option<bool> = None;
    let mut packages: Option<Vec<String>> = None;
    let mut idx = 1;

    while idx < args.len() {
        match args[idx].as_str() {
            "--clear" => {
                clear = true;
                idx += 1;
            }
            "--global" => {
                let Some(val) = args.get(idx + 1) else {
                    return Err((
                        2,
                        "Missing value for --global (expected true|false)".to_owned(),
                    ));
                };
                global = Some(match val.as_str() {
                    "true" | "1" => true,
                    "false" | "0" => false,
                    _ => return Err((2, format!("Invalid boolean for --global: {val}"))),
                });
                idx += 2;
            }
            "--packages" => {
                let Some(val) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --packages".to_owned()));
                };
                let parsed: Vec<String> = val
                    .split(',')
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
                    .collect();
                packages = Some(parsed);
                idx += 2;
            }
            flag => return Err((2, format!("Unknown flag for plugins scope: {flag}"))),
        }
    }

    if !clear && global.is_none() && packages.is_none() {
        return Err((
            2,
            "Specify at least one of --global, --packages, or --clear".to_owned(),
        ));
    }

    Ok(cmd(
        methods::PLUGINS_SET_SCOPE_OVERRIDE,
        Some(json!({
            "pluginId": plugin_id,
            "packages": packages,
            "global": global,
            "clear": clear,
        })),
    ))
}

fn cmd(method: &str, params: Option<Value>) -> ParsedPluginCmd {
    ParsedPluginCmd {
        method: method.to_owned(),
        params,
        output_mode: OutputMode::FullEnvelope,
        io_timeout: None,
    }
}
