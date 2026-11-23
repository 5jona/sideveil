//! `sideveil-ctl plugins create-embed ...` subcommand argument parser.

use serde_json::{Value, json};
use sideveil_protocol::rpc::methods;

use super::plugins_cmd::{ParsedPluginCmd, cmd};

fn parse_i32_flag(args: &[String], idx: usize, flag: &str) -> Result<i32, (u8, String)> {
    let Some(raw) = args.get(idx + 1) else {
        return Err((2, format!("Missing value for {flag}")));
    };
    raw.parse::<i32>()
        .map_err(|_| (2, format!("Invalid integer for {flag}: {raw}")))
}

pub(super) fn parse_plugins_create_embed(args: &[String]) -> Result<ParsedPluginCmd, (u8, String)> {
    let (Some(id), Some(name), Some(url)) = (args.first(), args.get(1), args.get(2)) else {
        return Err((
            2,
            "Usage: sideveil-ctl plugins create-embed <id> <name> <url> [--remote-url <url>] [--lan-ssid <ssid>]... [--text-zoom <50..200>] [--width-dp <240..720>] [--height-percent <30..100>] [--keep-alive-grace-sec <0..1800>] [--allow-cleartext] [--keep-alive] [--allow-zoom] [--no-wide-viewport] [--no-web-history-back] [--no-toolbar]"
                .to_owned(),
        ));
    };
    let mut allow_cleartext = false;
    let mut keep_alive = false;
    let mut lan_ssids: Vec<String> = Vec::new();
    let mut extra = serde_json::Map::new();
    let mut idx = 3;
    while idx < args.len() {
        let flag = args[idx].as_str();
        match flag {
            "--allow-cleartext" => {
                allow_cleartext = true;
                idx += 1;
            }
            "--keep-alive" => {
                keep_alive = true;
                idx += 1;
            }
            "--allow-zoom" => {
                extra.insert("allowZoom".to_owned(), Value::Bool(true));
                idx += 1;
            }
            "--no-wide-viewport" => {
                extra.insert("wideViewport".to_owned(), Value::Bool(false));
                idx += 1;
            }
            "--no-web-history-back" => {
                extra.insert("webHistoryBack".to_owned(), Value::Bool(false));
                idx += 1;
            }
            "--no-toolbar" => {
                extra.insert("showToolbar".to_owned(), Value::Bool(false));
                idx += 1;
            }
            "--remote-url" => {
                let Some(val) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --remote-url".to_owned()));
                };
                extra.insert("remoteUrl".to_owned(), Value::String(val.clone()));
                idx += 2;
            }
            "--lan-ssid" => {
                let Some(val) = args.get(idx + 1) else {
                    return Err((2, "Missing value for --lan-ssid".to_owned()));
                };
                lan_ssids.push(val.clone());
                idx += 2;
            }
            "--text-zoom" => {
                let val = parse_i32_flag(args, idx, flag)?;
                extra.insert("textZoom".to_owned(), Value::from(val));
                idx += 2;
            }
            "--width-dp" => {
                let val = parse_i32_flag(args, idx, flag)?;
                extra.insert("widthDp".to_owned(), Value::from(val));
                idx += 2;
            }
            "--height-percent" => {
                let val = parse_i32_flag(args, idx, flag)?;
                extra.insert("heightPercent".to_owned(), Value::from(val));
                idx += 2;
            }
            "--keep-alive-grace-sec" => {
                let val = parse_i32_flag(args, idx, flag)?;
                extra.insert("keepAliveGraceSec".to_owned(), Value::from(val));
                idx += 2;
            }
            other => {
                return Err((2, format!("Unknown flag for plugins create-embed: {other}")));
            }
        }
    }
    let mut payload = serde_json::Map::new();
    payload.insert("id".to_owned(), Value::String(id.clone()));
    payload.insert("name".to_owned(), Value::String(name.clone()));
    payload.insert("url".to_owned(), Value::String(url.clone()));
    payload.insert("allowCleartext".to_owned(), Value::Bool(allow_cleartext));
    payload.insert("keepAlive".to_owned(), Value::Bool(keep_alive));
    if !lan_ssids.is_empty() {
        payload.insert("lanSsids".to_owned(), json!(lan_ssids));
    }
    payload.extend(extra);
    Ok(cmd(
        methods::PLUGINS_CREATE_WEB_EMBED,
        Some(Value::Object(payload)),
    ))
}
