//! Native CLI utility communicating with `sideveild` over `hub.sock` (`SO_PEERCRED`).

use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::paths;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, methods};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};

const IO_TIMEOUT: Duration = Duration::from_secs(4);

#[derive(Debug, PartialEq, Eq)]
enum OutputMode {
    UnwrapResult,
    FullEnvelope,
}

#[derive(Debug)]
struct CliInvocation {
    socket_path: PathBuf,
    method: String,
    params: Option<Value>,
    output_mode: OutputMode,
}

fn main() -> ExitCode {
    let invocation = match parse_args(std::env::args().skip(1)) {
        Ok(inv) => inv,
        Err((code, msg)) => {
            print_error(&msg);
            return ExitCode::from(code);
        }
    };

    match execute(&invocation) {
        Ok(output) => {
            println!("{output}");
            ExitCode::SUCCESS
        }
        Err(msg) => {
            print_error(&msg);
            ExitCode::FAILURE
        }
    }
}

fn parse_args(args: impl IntoIterator<Item = String>) -> Result<CliInvocation, (u8, String)> {
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
        "plugins" => {
            let sub = positional.get(1).map_or("list", String::as_str);
            match sub {
                "list" => (
                    methods::PLUGINS_LIST.to_owned(),
                    None,
                    OutputMode::FullEnvelope,
                ),
                "reload" => (
                    methods::PLUGINS_RELOAD.to_owned(),
                    None,
                    OutputMode::FullEnvelope,
                ),
                "enable" | "disable" => {
                    let Some(plugin_id) = positional.get(2) else {
                        return Err((2, format!("Usage: sideveil-ctl plugins {sub} <plugin-id>")));
                    };
                    (
                        methods::PLUGINS_SET_ENABLED.to_owned(),
                        Some(json!({
                            "pluginId": plugin_id,
                            "enabled": sub == "enable",
                        })),
                        OutputMode::FullEnvelope,
                    )
                }
                "select" => {
                    let Some(plugin_id) = positional.get(2) else {
                        return Err((
                            2,
                            "Usage: sideveil-ctl plugins select <plugin-id> [package-name]"
                                .to_owned(),
                        ));
                    };
                    let pkg = positional.get(3).cloned();
                    (
                        methods::PLUGINS_SELECT.to_owned(),
                        Some(json!({
                            "pluginId": plugin_id,
                            "packageName": pkg,
                        })),
                        OutputMode::FullEnvelope,
                    )
                }
                other => return Err((2, format!("Unknown plugins subcommand: {other}"))),
            }
        }
        "rpc" => {
            let Some(method_name) = positional.get(1) else {
                return Err((
                    2,
                    "Usage: sideveil-ctl rpc <method> [json-params]".to_owned(),
                ));
            };
            let parsed_params = match positional.get(2) {
                Some(raw) => Some(
                    serde_json::from_str::<Value>(raw)
                        .map_err(|e| (2, format!("Invalid JSON params: {e}")))?,
                ),
                None => None,
            };
            (method_name.clone(), parsed_params, OutputMode::FullEnvelope)
        }
        other => return Err((2, format!("Unknown command: {other}"))),
    };

    Ok(CliInvocation {
        socket_path,
        method,
        params,
        output_mode,
    })
}

fn execute(inv: &CliInvocation) -> Result<String, String> {
    let mut stream = connect_socket(&inv.socket_path)?;
    let hello = serde_json::to_string(&UdsClientHello::Ctl)
        .map_err(|e| format!("Failed encoding handshake: {e}"))?;
    writeln!(stream, "{hello}").map_err(|e| format!("Failed writing handshake: {e}"))?;
    stream
        .flush()
        .map_err(|e| format!("Failed flushing handshake: {e}"))?;

    let mut reader = BufReader::new(
        stream
            .try_clone()
            .map_err(|e| format!("Failed cloning UDS stream: {e}"))?,
    );
    let mut ack_line = String::new();
    reader
        .read_line(&mut ack_line)
        .map_err(|e| format!("Failed reading handshake ack: {e}"))?;
    let ack: UdsHandshakeAck = serde_json::from_str(ack_line.trim())
        .map_err(|e| format!("Invalid handshake ack from sideveild: {e}"))?;
    if !ack.ok {
        return Err(ack
            .error
            .unwrap_or_else(|| "Handshake rejected by sideveild".to_owned()));
    }

    let req = RpcRequest::new("ctl", &inv.method, inv.params.clone());
    let req_line =
        serde_json::to_string(&req).map_err(|e| format!("Failed encoding RPC request: {e}"))?;
    writeln!(stream, "{req_line}").map_err(|e| format!("Failed sending RPC request: {e}"))?;
    stream
        .flush()
        .map_err(|e| format!("Failed flushing RPC request: {e}"))?;

    let mut resp_line = String::new();
    let n = reader
        .read_line(&mut resp_line)
        .map_err(|e| format!("Failed reading RPC response: {e}"))?;
    if n == 0 {
        return Err("sideveild closed connection without response".to_owned());
    }

    let resp: RpcResponse = serde_json::from_str(resp_line.trim())
        .map_err(|e| format!("Invalid RPC response JSON: {e}"))?;
    if let Some(err) = resp.error {
        return Err(format!("RPC error {}: {}", err.code, err.message));
    }

    match inv.output_mode {
        OutputMode::UnwrapResult => {
            let result = resp.result.unwrap_or(Value::Null);
            serde_json::to_string(&result).map_err(|e| format!("Failed encoding output: {e}"))
        }
        OutputMode::FullEnvelope => {
            serde_json::to_string(&resp).map_err(|e| format!("Failed encoding output: {e}"))
        }
    }
}

fn connect_socket(path: &Path) -> Result<UnixStream, String> {
    let stream = UnixStream::connect(path)
        .map_err(|e| format!("Failed to connect to sideveild ({}): {e}", path.display()))?;
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
    Ok(stream)
}

fn print_error(message: &str) {
    eprintln!("{}", json!({ "error": message }));
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
    }
}
