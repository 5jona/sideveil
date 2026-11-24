//! Unit tests for `sideveil-ctl` plugin DX commands (`init`, `validate`, `pack`, `grant`, `daemon-logs`, `reload-webview`).

use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sideveil_protocol::rpc::methods;

use super::{CliAction, OutputMode, parse_args};
use crate::dx::{PluginTemplate, run_init, run_pack, run_validate};

fn unwrap_rpc(action: CliAction) -> (String, Option<Value>, OutputMode) {
    match action {
        CliAction::Rpc {
            method,
            params,
            output_mode,
        } => (method, params, output_mode),
        other => panic!("expected CliAction::Rpc, got {other:?}"),
    }
}

#[test]
fn rejects_removed_debug_mode_and_parses_plugin_dx_commands() {
    assert!(parse_args(["debug".to_owned(), "mode".to_owned()]).is_err());
    assert!(parse_args(["debug".to_owned(), "mode".to_owned(), "debug".to_owned()]).is_err());

    let grant = parse_args([
        "plugins".to_owned(),
        "grant".to_owned(),
        "com.example.demo".to_owned(),
        "--all-pending".to_owned(),
        "--approve-daemon".to_owned(),
    ])
    .expect("plugins grant");
    let (method, params, _) = unwrap_rpc(grant.action);
    assert_eq!(method, methods::PLUGINS_GRANT_CAPABILITIES);
    assert_eq!(
        params,
        Some(json!({
            "pluginId": "com.example.demo",
            "grantAllPending": true,
            "approveDaemon": true,
        }))
    );

    let dlogs = parse_args([
        "plugins".to_owned(),
        "daemon-logs".to_owned(),
        "com.example.demo".to_owned(),
        "--limit".to_owned(),
        "50".to_owned(),
    ])
    .expect("plugins daemon-logs");
    let (method, params, output_mode) = unwrap_rpc(dlogs.action);
    assert_eq!(method, methods::PLUGINS_DAEMON_LOGS);
    assert_eq!(
        params,
        Some(json!({ "pluginId": "com.example.demo", "limit": 50 }))
    );
    assert_eq!(output_mode, OutputMode::UnwrapResult);

    let reload = parse_args([
        "plugins".to_owned(),
        "reload-webview".to_owned(),
        "com.example.demo".to_owned(),
    ])
    .expect("plugins reload-webview");
    let (method, params, _) = unwrap_rpc(reload.action);
    assert_eq!(method, methods::PLUGINS_RELOAD_WEBVIEW);
    assert_eq!(params, Some(json!({ "pluginId": "com.example.demo" })));

    assert!(matches!(
        parse_args([
            "rpc".to_owned(),
            "plugins.install".to_owned(),
            "-".to_owned(),
            "--timeout-ms".to_owned(),
            "30000".to_owned(),
        ])
        .expect("rpc stdin")
        .action,
        CliAction::RpcFromStdin { .. }
    ));
    let parsed_stdin =
        crate::read_json_params_from_reader(&b"{\"archiveBase64\":\"AAAA\"}"[..]).expect("stdin");
    assert_eq!(parsed_stdin, Some(json!({ "archiveBase64": "AAAA" })));

    assert!(matches!(
        parse_args([
            "plugins".to_owned(),
            "validate".to_owned(),
            "demo.svp".to_owned()
        ])
        .expect("validate")
        .action,
        CliAction::ValidatePlugin { .. }
    ));
    assert!(matches!(
        parse_args([
            "plugins".to_owned(),
            "pack".to_owned(),
            "demo".to_owned(),
            "-o".to_owned(),
            "demo.svp".to_owned(),
        ])
        .expect("pack")
        .action,
        CliAction::PackPlugin { .. }
    ));
    assert!(matches!(
        parse_args([
            "plugins".to_owned(),
            "init".to_owned(),
            "com.example.starter".to_owned(),
            "--template".to_owned(),
            "uds-native".to_owned(),
        ])
        .expect("init")
        .action,
        CliAction::InitPlugin { .. }
    ));
}

#[test]
fn init_validate_pack_round_trip_succeeds_offline() {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let root = std::env::temp_dir().join(format!("sideveil-ctl-dx-{}-{nanos}", std::process::id()));
    let plugin_dir = root.join("com.example.hello");
    let svp_path = root.join("com.example.hello.svp");

    let init_out = run_init(
        "com.example.hello",
        PluginTemplate::Vanilla,
        Some(&plugin_dir),
    )
    .expect("init vanilla");
    let init_val: Value = serde_json::from_str(&init_out).expect("parse init json");
    assert_eq!(init_val["ok"], true);

    let val_dir_out = run_validate(&plugin_dir).expect("validate dir");
    let val_dir: Value = serde_json::from_str(&val_dir_out).expect("parse validate dir");
    assert_eq!(val_dir["ok"], true);
    assert_eq!(val_dir["pluginId"], "com.example.hello");

    let pack_out = run_pack(&plugin_dir, Some(&svp_path)).expect("pack svp");
    let pack_val: Value = serde_json::from_str(&pack_out).expect("parse pack json");
    assert_eq!(pack_val["ok"], true);
    assert!(svp_path.is_file());

    let val_svp_out = run_validate(&svp_path).expect("validate svp");
    let val_svp: Value = serde_json::from_str(&val_svp_out).expect("parse validate svp");
    assert_eq!(val_svp["ok"], true);
    assert_eq!(val_svp["pluginId"], "com.example.hello");

    let _ = fs::remove_dir_all(&root);
}
