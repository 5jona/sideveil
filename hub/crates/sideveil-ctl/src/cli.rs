//! CLI argument parser for `sideveil-ctl`.

mod hud_cmd;

use std::path::PathBuf;

use serde_json::{Value, json};
use sideveil_protocol::paths;
use sideveil_protocol::rpc::methods;

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum OutputMode {
    UnwrapResult,
    FullEnvelope,
}

#[derive(Debug)]
pub(crate) struct CliInvocation {
    pub(crate) socket_path: PathBuf,
    pub(crate) method: String,
    pub(crate) params: Option<Value>,
    pub(crate) output_mode: OutputMode,
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
    let (method, params, output_mode) = match cmd {
        "status" => (
            methods::DAEMON_STATUS.to_owned(),
            None,
            OutputMode::UnwrapResult,
        ),
        "health" => (
            methods::DAEMON_HEALTH.to_owned(),
            None,
            OutputMode::UnwrapResult,
        ),
        "open" => (
            methods::PANEL_OPEN.to_owned(),
            None,
            OutputMode::FullEnvelope,
        ),
        "close" => (
            methods::PANEL_CLOSE.to_owned(),
            None,
            OutputMode::FullEnvelope,
        ),
        "toggle" => (
            methods::PANEL_TOGGLE.to_owned(),
            None,
            OutputMode::FullEnvelope,
        ),
        "reload" => (
            methods::CONFIG_RELOAD.to_owned(),
            None,
            OutputMode::FullEnvelope,
        ),
        "restart-host" => (
            methods::HOST_RESTART.to_owned(),
            None,
            OutputMode::FullEnvelope,
        ),
        "ping" => (
            methods::DAEMON_PING.to_owned(),
            None,
            OutputMode::FullEnvelope,
        ),
        "logs" => (
            methods::DAEMON_LOGS.to_owned(),
            None,
            OutputMode::FullEnvelope,
        ),
        "plugins" => parse_plugins_subcommand(&positional[1..])?,
        "hud" => hud_cmd::parse_hud_subcommand(&positional[1..])?,
        "rpc" => parse_raw_rpc(&positional[1..])?,
        other => return Err((2, format!("Unknown command: {other}"))),
    };

    Ok(CliInvocation {
        socket_path,
        method,
        params,
        output_mode,
    })
}

fn parse_raw_rpc(args: &[String]) -> Result<(String, Option<Value>, OutputMode), (u8, String)> {
    let Some(method_name) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl rpc <method> [json-params]".to_owned(),
        ));
    };
    let parsed_params = match args.get(1) {
        Some(raw) => Some(
            serde_json::from_str::<Value>(raw)
                .map_err(|e| (2, format!("Invalid JSON params: {e}")))?,
        ),
        None => None,
    };
    Ok((method_name.clone(), parsed_params, OutputMode::FullEnvelope))
}

fn parse_plugins_subcommand(
    args: &[String],
) -> Result<(String, Option<Value>, OutputMode), (u8, String)> {
    let sub = args.first().map_or("list", String::as_str);
    match sub {
        "list" => Ok((
            methods::PLUGINS_LIST.to_owned(),
            None,
            OutputMode::FullEnvelope,
        )),
        "reload" => Ok((
            methods::PLUGINS_RELOAD.to_owned(),
            None,
            OutputMode::FullEnvelope,
        )),
        "enable" | "disable" => {
            let Some(plugin_id) = args.get(1) else {
                return Err((2, format!("Usage: sideveil-ctl plugins {sub} <plugin-id>")));
            };
            Ok((
                methods::PLUGINS_SET_ENABLED.to_owned(),
                Some(json!({
                    "pluginId": plugin_id,
                    "enabled": sub == "enable",
                })),
                OutputMode::FullEnvelope,
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
            Ok((
                methods::PLUGINS_SET_PRIORITY.to_owned(),
                Some(json!({
                    "pluginId": plugin_id,
                    "priority": priority,
                })),
                OutputMode::FullEnvelope,
            ))
        }
        "scope" => parse_plugins_scope(&args[1..]),
        "select" => {
            let Some(plugin_id) = args.get(1) else {
                return Err((
                    2,
                    "Usage: sideveil-ctl plugins select <plugin-id> [package-name]".to_owned(),
                ));
            };
            let pkg = args.get(2).cloned();
            Ok((
                methods::PLUGINS_SELECT.to_owned(),
                Some(json!({
                    "pluginId": plugin_id,
                    "packageName": pkg,
                })),
                OutputMode::FullEnvelope,
            ))
        }
        other => Err((2, format!("Unknown plugins subcommand: {other}"))),
    }
}

fn parse_plugins_scope(
    args: &[String],
) -> Result<(String, Option<Value>, OutputMode), (u8, String)> {
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

    Ok((
        methods::PLUGINS_SET_SCOPE_OVERRIDE.to_owned(),
        Some(json!({
            "pluginId": plugin_id,
            "packages": packages,
            "global": global,
            "clear": clear,
        })),
        OutputMode::FullEnvelope,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_default_and_explicit_cli_commands() {
        let default = parse_args(Vec::<String>::new()).expect("default args");
        assert_eq!(default.method, methods::DAEMON_STATUS);
        assert_eq!(default.output_mode, OutputMode::UnwrapResult);

        let rpc = parse_args([
            "--socket".to_owned(),
            "/tmp/test.sock".to_owned(),
            "rpc".to_owned(),
            "config.patchAppearance".to_owned(),
            r#"{"colorMode":"dark"}"#.to_owned(),
        ])
        .expect("rpc args");
        assert_eq!(rpc.socket_path, PathBuf::from("/tmp/test.sock"));
        assert_eq!(rpc.method, "config.patchAppearance");
        assert_eq!(rpc.params, Some(json!({ "colorMode": "dark" })));
        assert_eq!(rpc.output_mode, OutputMode::FullEnvelope);

        assert!(parse_args(["nonexistent".to_owned()]).is_err());

        let prio = parse_args([
            "plugins".to_owned(),
            "priority".to_owned(),
            "com.example.alpha".to_owned(),
            "25".to_owned(),
        ])
        .expect("plugins priority");
        assert_eq!(prio.method, methods::PLUGINS_SET_PRIORITY);
        assert_eq!(
            prio.params,
            Some(json!({ "pluginId": "com.example.alpha", "priority": 25 }))
        );

        let scope = parse_args([
            "plugins".to_owned(),
            "scope".to_owned(),
            "com.example.alpha".to_owned(),
            "--global".to_owned(),
            "false".to_owned(),
            "--packages".to_owned(),
            "com.a, com.b".to_owned(),
        ])
        .expect("plugins scope");
        assert_eq!(scope.method, methods::PLUGINS_SET_SCOPE_OVERRIDE);
        assert_eq!(
            scope.params,
            Some(json!({
                "pluginId": "com.example.alpha",
                "packages": ["com.a", "com.b"],
                "global": false,
                "clear": false,
            }))
        );

        let clear = parse_args([
            "plugins".to_owned(),
            "scope".to_owned(),
            "com.example.alpha".to_owned(),
            "--clear".to_owned(),
        ])
        .expect("plugins scope clear");
        assert_eq!(
            clear.params,
            Some(json!({
                "pluginId": "com.example.alpha",
                "packages": Value::Null,
                "global": Value::Null,
                "clear": true,
            }))
        );
    }
}
