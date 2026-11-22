//! Unit tests for `sideveil-ctl` argument parsing.

use std::path::PathBuf;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::rpc::methods;

use super::{CliAction, OutputMode, parse_args};

fn unwrap_rpc(action: CliAction) -> (String, Option<Value>, OutputMode) {
    match action {
        CliAction::Rpc {
            method,
            params,
            output_mode,
        } => (method, params, output_mode),
        CliAction::DebugCdpStatus => panic!("expected Rpc action"),
    }
}

#[test]
fn parses_default_and_explicit_cli_commands() {
    let default = parse_args(Vec::<String>::new()).expect("default args");
    let (method, _, output_mode) = unwrap_rpc(default.action);
    assert_eq!(method, methods::DAEMON_STATUS);
    assert_eq!(output_mode, OutputMode::UnwrapResult);

    let rpc = parse_args([
        "--socket".to_owned(),
        "/tmp/test.sock".to_owned(),
        "rpc".to_owned(),
        "config.patchAppearance".to_owned(),
        r#"{"colorMode":"dark"}"#.to_owned(),
        "--timeout-ms".to_owned(),
        "12000".to_owned(),
    ])
    .expect("rpc args");
    assert_eq!(rpc.socket_path, PathBuf::from("/tmp/test.sock"));
    assert_eq!(rpc.io_timeout, Duration::from_millis(12_000));
    let (method, params, output_mode) = unwrap_rpc(rpc.action);
    assert_eq!(method, "config.patchAppearance");
    assert_eq!(params, Some(json!({ "colorMode": "dark" })));
    assert_eq!(output_mode, OutputMode::FullEnvelope);

    let rpc_default_timeout =
        parse_args(["rpc".to_owned(), "daemon.ping".to_owned()]).expect("rpc");
    assert_eq!(rpc_default_timeout.io_timeout, Duration::from_millis(8_000));

    let rpc_clamped = parse_args([
        "rpc".to_owned(),
        "daemon.ping".to_owned(),
        "--timeout-ms".to_owned(),
        "999999".to_owned(),
    ])
    .expect("rpc clamped");
    assert_eq!(rpc_clamped.io_timeout, Duration::from_millis(60_000));

    assert!(parse_args(["nonexistent".to_owned()]).is_err());

    let prio = parse_args([
        "plugins".to_owned(),
        "priority".to_owned(),
        "com.example.alpha".to_owned(),
        "25".to_owned(),
    ])
    .expect("plugins priority");
    let (method, params, _) = unwrap_rpc(prio.action);
    assert_eq!(method, methods::PLUGINS_SET_PRIORITY);
    assert_eq!(
        params,
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
    let (method, params, _) = unwrap_rpc(scope.action);
    assert_eq!(method, methods::PLUGINS_SET_SCOPE_OVERRIDE);
    assert_eq!(
        params,
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
    let (_, params, _) = unwrap_rpc(clear.action);
    assert_eq!(
        params,
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
    let (method, params, _) = unwrap_rpc(inv.action);
    assert_eq!(method, methods::PLUGIN_INVOKE);
    assert_eq!(
        params,
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
    let (method, params, _) = unwrap_rpc(inst.action);
    assert_eq!(method, methods::PLUGINS_INSTALL);
    assert_eq!(params, Some(json!({ "sourceDir": "/tmp/my-plugin" })));
}

#[test]
fn parses_debug_subcommands() {
    let inspect = parse_args([
        "debug".to_owned(),
        "inspect-ui".to_owned(),
        "--timeout-ms".to_owned(),
        "15000".to_owned(),
    ])
    .expect("debug inspect-ui");
    assert_eq!(inspect.io_timeout, Duration::from_millis(15_000));
    let (method, params, output_mode) = unwrap_rpc(inspect.action);
    assert_eq!(method, methods::DEBUG_INSPECT_UI);
    assert_eq!(params, None);
    assert_eq!(output_mode, OutputMode::FullEnvelope);

    let cdp_on =
        parse_args(["debug".to_owned(), "cdp".to_owned(), "on".to_owned()]).expect("cdp on");
    let (method, params, _) = unwrap_rpc(cdp_on.action);
    assert_eq!(method, methods::DEBUG_SET_WEBVIEW_DEBUGGING);
    assert_eq!(params, Some(json!({ "enabled": true })));

    let cdp_off =
        parse_args(["debug".to_owned(), "cdp".to_owned(), "off".to_owned()]).expect("cdp off");
    let (method, params, _) = unwrap_rpc(cdp_off.action);
    assert_eq!(method, methods::DEBUG_SET_WEBVIEW_DEBUGGING);
    assert_eq!(params, Some(json!({ "enabled": false })));

    let cdp_status = parse_args(["debug".to_owned(), "cdp".to_owned(), "status".to_owned()])
        .expect("cdp status");
    assert_eq!(cdp_status.action, CliAction::DebugCdpStatus);
}

#[test]
fn parses_network_and_web_embed_plugin_commands() {
    let net = parse_args(["network".to_owned()]).expect("network");
    let (method, params, output_mode) = unwrap_rpc(net.action);
    assert_eq!(method, methods::NETWORK_CONTEXT);
    assert_eq!(params, None);
    assert_eq!(output_mode, OutputMode::UnwrapResult);

    let uninst = parse_args([
        "plugins".to_owned(),
        "uninstall".to_owned(),
        "embed.router".to_owned(),
    ])
    .expect("plugins uninstall");
    let (method, params, _) = unwrap_rpc(uninst.action);
    assert_eq!(method, methods::PLUGINS_UNINSTALL);
    assert_eq!(params, Some(json!({ "pluginId": "embed.router" })));

    let create = parse_args([
        "plugins".to_owned(),
        "create-embed".to_owned(),
        "embed.router".to_owned(),
        "Router".to_owned(),
        "http://192.168.1.1".to_owned(),
        "--allow-cleartext".to_owned(),
        "--keep-alive".to_owned(),
    ])
    .expect("plugins create-embed");
    let (method, params, _) = unwrap_rpc(create.action);
    assert_eq!(method, methods::PLUGINS_CREATE_WEB_EMBED);
    assert_eq!(
        params,
        Some(json!({
            "id": "embed.router",
            "name": "Router",
            "url": "http://192.168.1.1",
            "allowCleartext": true,
            "keepAlive": true,
        }))
    );
}
