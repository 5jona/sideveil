//! CLI argument parser for `sideveil-ctl`.

mod hud_cmd;
mod plugins_cmd;

use std::path::PathBuf;
use std::time::Duration;

use serde_json::Value;
use sideveil_protocol::paths;
use sideveil_protocol::rpc::methods;

const DEFAULT_IO_TIMEOUT: Duration = Duration::from_secs(4);

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
        "plugins" => {
            let parsed = plugins_cmd::parse_plugins_subcommand(&positional[1..])?;
            if let Some(custom_timeout) = parsed.io_timeout {
                io_timeout = custom_timeout;
            }
            (parsed.method, parsed.params, parsed.output_mode)
        }
        "hud" => hud_cmd::parse_hud_subcommand(&positional[1..])?,
        "rpc" => parse_raw_rpc(&positional[1..])?,
        other => return Err((2, format!("Unknown command: {other}"))),
    };

    Ok(CliInvocation {
        socket_path,
        method,
        params,
        output_mode,
        io_timeout,
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

#[cfg(test)]
mod tests {
    use serde_json::json;

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

        let inv = parse_args([
            "plugins".to_owned(),
            "invoke".to_owned(),
            "com.example.alpha".to_owned(),
            "daemon.ping".to_owned(),
            r#"{"verbose":true}"#.to_owned(),
            "--timeout-ms".to_owned(),
            "25000".to_owned(),
        ])
        .expect("plugins invoke");
        assert_eq!(inv.method, methods::PLUGIN_INVOKE);
        assert_eq!(
            inv.params,
            Some(json!({
                "pluginId": "com.example.alpha",
                "method": "daemon.ping",
                "params": { "verbose": true },
                "timeoutMs": 25_000,
            }))
        );
        assert_eq!(inv.io_timeout, Duration::from_millis(27_000));

        let inst = parse_args([
            "plugins".to_owned(),
            "install".to_owned(),
            "/tmp/my-plugin".to_owned(),
        ])
        .expect("plugins install");
        assert_eq!(inst.method, methods::PLUGINS_INSTALL);
        assert_eq!(inst.params, Some(json!({ "sourceDir": "/tmp/my-plugin" })));
    }
}
