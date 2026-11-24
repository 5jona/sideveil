//! Extended `sideveil-ctl plugins` subcommands (`install`, `inspect`, `grant`, `daemon-logs`,
//! `reload-webview`, `validate`, `pack`, `init`).

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::plugin::PluginManifest;
use sideveil_protocol::rpc::methods;
use sideveil_protocol::svp::{is_archive_path, read_svp_file};

use super::{CliAction, OutputMode};
use crate::dx::PluginTemplate;

pub(super) struct ParsedPluginAction {
    pub(super) action: CliAction,
    pub(super) io_timeout: Option<Duration>,
}

pub(super) fn parse_plugins_install(args: &[String]) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(raw_target) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins install <dir-or-svp> [--approve-all] [--approve-daemon]"
                .to_owned(),
        ));
    };
    let mut approve_all = false;
    let mut approve_daemon = false;
    for flag in &args[1..] {
        match flag.as_str() {
            "--approve-all" => approve_all = true,
            "--approve-daemon" => approve_daemon = true,
            other => return Err((2, format!("Unknown flag for plugins install: {other}"))),
        }
    }

    let path = Path::new(raw_target);
    let abs = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let abs_str = abs.to_string_lossy().into_owned();

    let mut payload = serde_json::Map::new();
    payload.insert("sourcePath".to_owned(), Value::String(abs_str.clone()));
    if !is_archive_path(&abs) {
        payload.insert("sourceDir".to_owned(), Value::String(abs_str));
    }
    if approve_daemon {
        payload.insert("approveDaemon".to_owned(), Value::Bool(true));
    }
    if approve_all {
        let caps = collect_explicit_capabilities(&abs)?;
        payload.insert(
            "grantedCapabilities".to_owned(),
            serde_json::to_value(caps).map_err(|e| (2, format!("Failed encoding caps: {e}")))?,
        );
    }

    Ok(ParsedPluginAction {
        action: CliAction::Rpc {
            method: methods::PLUGINS_INSTALL.to_owned(),
            params: Some(Value::Object(payload)),
            output_mode: OutputMode::FullEnvelope,
        },
        io_timeout: Some(Duration::from_secs(15)),
    })
}

pub(super) fn parse_plugins_inspect(args: &[String]) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(raw_target) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins inspect <dir-or-svp>".to_owned(),
        ));
    };
    let path = Path::new(raw_target);
    let abs = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    Ok(ParsedPluginAction {
        action: CliAction::Rpc {
            method: methods::PLUGINS_INSPECT_ARCHIVE.to_owned(),
            params: Some(json!({ "path": abs.to_string_lossy() })),
            output_mode: OutputMode::FullEnvelope,
        },
        io_timeout: Some(Duration::from_secs(10)),
    })
}

pub(super) fn parse_plugins_grant(args: &[String]) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(plugin_id) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins grant <plugin-id> [--all-pending] [--cap <cap>...] [--approve-daemon|--revoke-daemon] [--revoke-all]"
                .to_owned(),
        ));
    };
    let mut caps: Vec<String> = Vec::new();
    let mut all_pending = false;
    let mut revoke_all = false;
    let mut approve_daemon: Option<bool> = None;
    let mut idx = 1;
    while idx < args.len() {
        match args[idx].as_str() {
            "--all-pending" => {
                all_pending = true;
                idx += 1;
            }
            "--revoke-all" => {
                revoke_all = true;
                idx += 1;
            }
            "--approve-daemon" => {
                approve_daemon = Some(true);
                idx += 1;
            }
            "--revoke-daemon" => {
                approve_daemon = Some(false);
                idx += 1;
            }
            "--cap" => {
                let Some(val) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --cap".to_owned()));
                };
                caps.push(val.trim().to_owned());
                idx += 2;
            }
            flag => return Err((2, format!("Unknown flag for plugins grant: {flag}"))),
        }
    }
    if !all_pending && !revoke_all && approve_daemon.is_none() && caps.is_empty() {
        return Err((
            2,
            "Specify at least one of --all-pending, --cap <cap>, --approve-daemon, --revoke-daemon, or --revoke-all"
                .to_owned(),
        ));
    }

    let mut payload = serde_json::Map::new();
    payload.insert("pluginId".to_owned(), Value::String(plugin_id.clone()));
    if !caps.is_empty() {
        payload.insert("capabilities".to_owned(), json!(caps));
    }
    if all_pending {
        payload.insert("grantAllPending".to_owned(), Value::Bool(true));
    }
    if revoke_all {
        payload.insert("revokeAll".to_owned(), Value::Bool(true));
    }
    if let Some(ad) = approve_daemon {
        payload.insert("approveDaemon".to_owned(), Value::Bool(ad));
    }

    Ok(ParsedPluginAction {
        action: CliAction::Rpc {
            method: methods::PLUGINS_GRANT_CAPABILITIES.to_owned(),
            params: Some(Value::Object(payload)),
            output_mode: OutputMode::FullEnvelope,
        },
        io_timeout: None,
    })
}

pub(super) fn parse_plugins_daemon_logs(
    args: &[String],
) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(plugin_id) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins daemon-logs <plugin-id> [--limit <n>]".to_owned(),
        ));
    };
    let mut limit: Option<u32> = None;
    let mut idx = 1;
    while idx < args.len() {
        if args[idx] == "--limit" {
            let Some(raw) = args.get(idx + 1) else {
                return Err((2, "Missing value for --limit".to_owned()));
            };
            let parsed = raw
                .parse::<u32>()
                .map_err(|_| (2, format!("Invalid integer for --limit: {raw}")))?;
            limit = Some(parsed.clamp(1, 200));
            idx += 2;
        } else {
            return Err((
                2,
                format!("Unknown flag for plugins daemon-logs: {}", args[idx]),
            ));
        }
    }
    let mut payload = json!({ "pluginId": plugin_id });
    if let Some(n) = limit
        && let Some(obj) = payload.as_object_mut()
    {
        obj.insert("limit".to_owned(), json!(n));
    }
    Ok(ParsedPluginAction {
        action: CliAction::Rpc {
            method: methods::PLUGINS_DAEMON_LOGS.to_owned(),
            params: Some(payload),
            output_mode: OutputMode::UnwrapResult,
        },
        io_timeout: None,
    })
}

pub(super) fn parse_plugins_reload_webview(
    args: &[String],
) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(plugin_id) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins reload-webview <plugin-id>".to_owned(),
        ));
    };
    Ok(ParsedPluginAction {
        action: CliAction::Rpc {
            method: methods::PLUGINS_RELOAD_WEBVIEW.to_owned(),
            params: Some(json!({ "pluginId": plugin_id })),
            output_mode: OutputMode::FullEnvelope,
        },
        io_timeout: None,
    })
}

pub(super) fn parse_plugins_validate(args: &[String]) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(target) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins validate <dir-or-svp>".to_owned(),
        ));
    };
    Ok(ParsedPluginAction {
        action: CliAction::ValidatePlugin {
            target: PathBuf::from(target),
        },
        io_timeout: None,
    })
}

pub(super) fn parse_plugins_pack(args: &[String]) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(dir) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins pack <dir> [-o <out.svp>]".to_owned(),
        ));
    };
    let mut output_path = None;
    let mut idx = 1;
    while idx < args.len() {
        if args[idx] == "-o" || args[idx] == "--output" {
            let Some(val) = args.get(idx + 1) else {
                return Err((2, "Missing value for -o/--output".to_owned()));
            };
            output_path = Some(PathBuf::from(val));
            idx += 2;
        } else {
            return Err((2, format!("Unknown flag for plugins pack: {}", args[idx])));
        }
    }
    Ok(ParsedPluginAction {
        action: CliAction::PackPlugin {
            source_dir: PathBuf::from(dir),
            output_path,
        },
        io_timeout: None,
    })
}

pub(super) fn parse_plugins_init(args: &[String]) -> Result<ParsedPluginAction, (u8, String)> {
    let Some(plugin_id) = args.first() else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins init <plugin-id> [--template vanilla|react|uds-native] [-o <dir>]"
                .to_owned(),
        ));
    };
    let mut template = PluginTemplate::Vanilla;
    let mut output_dir = None;
    let mut idx = 1;
    while idx < args.len() {
        match args[idx].as_str() {
            "--template" | "-t" => {
                let Some(val) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --template".to_owned()));
                };
                template = PluginTemplate::parse(val).map_err(|e| (2, e))?;
                idx += 2;
            }
            "-o" | "--output" => {
                let Some(val) = args.get(idx + 1) else {
                    return Err((2, "Missing value for -o/--output".to_owned()));
                };
                output_dir = Some(PathBuf::from(val));
                idx += 2;
            }
            flag => return Err((2, format!("Unknown flag for plugins init: {flag}"))),
        }
    }
    Ok(ParsedPluginAction {
        action: CliAction::InitPlugin {
            plugin_id: plugin_id.clone(),
            template,
            output_dir,
        },
        io_timeout: None,
    })
}

fn collect_explicit_capabilities(
    path: &Path,
) -> Result<Vec<sideveil_protocol::plugin::PluginCapability>, (u8, String)> {
    let manifest = if is_archive_path(path) {
        read_svp_file(path)
            .map_err(|e| {
                (
                    2,
                    format!("Failed inspecting archive for --approve-all: {e}"),
                )
            })?
            .manifest
    } else {
        let raw = fs::read_to_string(path.join("manifest.json")).map_err(|e| {
            (
                2,
                format!("Failed reading manifest.json for --approve-all: {e}"),
            )
        })?;
        serde_json::from_str::<PluginManifest>(&raw)
            .map_err(|e| (2, format!("Invalid manifest.json: {e}")))?
    };
    Ok(manifest
        .effective_capabilities()
        .iter()
        .copied()
        .filter(|cap| cap.requires_explicit_grant())
        .collect())
}
