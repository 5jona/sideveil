//! Native CLI utility communicating with `sideveild` over `hub.sock` (`SO_PEERCRED`).

mod cli;

use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::process::ExitCode;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, methods};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};

use crate::cli::{CliAction, CliInvocation, OutputMode, parse_args};

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

fn execute(inv: &CliInvocation) -> Result<String, String> {
    match &inv.action {
        CliAction::Rpc {
            method,
            params,
            output_mode,
        } => {
            let resp = execute_rpc(&inv.socket_path, inv.io_timeout, method, params.clone())?;
            match output_mode {
                OutputMode::UnwrapResult => {
                    let result = resp.result.unwrap_or(Value::Null);
                    serde_json::to_string(&result)
                        .map_err(|e| format!("Failed encoding output: {e}"))
                }
                OutputMode::FullEnvelope => {
                    serde_json::to_string(&resp).map_err(|e| format!("Failed encoding output: {e}"))
                }
            }
        }
        CliAction::DebugCdpStatus => execute_debug_cdp_status(&inv.socket_path, inv.io_timeout),
    }
}

fn execute_debug_cdp_status(socket_path: &Path, timeout: Duration) -> Result<String, String> {
    let cfg_resp = execute_rpc(socket_path, timeout, methods::CONFIG_GET, None)?;
    let status_resp = execute_rpc(socket_path, timeout, methods::DAEMON_STATUS, None)?;

    let enabled = cfg_resp
        .result
        .as_ref()
        .and_then(|v| v.pointer("/debug/webviewDebuggingEnabled"))
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let host_pid = status_resp
        .result
        .as_ref()
        .and_then(|v| v.get("hostPid"))
        .and_then(Value::as_u64)
        .and_then(|v| u32::try_from(v).ok());
    let cdp_socket = if enabled {
        host_pid.map(|pid| format!("webview_devtools_remote_{pid}"))
    } else {
        None
    };

    let out = json!({
        "webviewDebuggingEnabled": enabled,
        "hostPid": host_pid,
        "cdpSocket": cdp_socket,
    });
    serde_json::to_string(&out).map_err(|e| format!("Failed encoding output: {e}"))
}

fn execute_rpc(
    socket_path: &Path,
    timeout: Duration,
    method: &str,
    params: Option<Value>,
) -> Result<RpcResponse, String> {
    let mut stream = connect_socket(socket_path, timeout)?;
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

    let req = RpcRequest::new("ctl", method, params);
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

    Ok(resp)
}

fn connect_socket(path: &Path, timeout: Duration) -> Result<UnixStream, String> {
    let stream = UnixStream::connect(path)
        .map_err(|e| format!("Failed to connect to sideveild ({}): {e}", path.display()))?;
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));
    Ok(stream)
}

fn print_error(message: &str) {
    eprintln!("{}", json!({ "error": message }));
}
