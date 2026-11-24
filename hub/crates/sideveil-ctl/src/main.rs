//! Native CLI utility communicating with `sideveild` over `hub.sock` (`SO_PEERCRED`),
//! plus offline plugin developer commands (`validate`, `pack`, `init`).

mod cli;
mod dx;

use std::io::{self, BufRead, BufReader, Read, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::process::ExitCode;
use std::time::Duration;

const MAX_STDIN_PARAMS_BYTES: usize = 24 * 1024 * 1024;

use serde_json::{Value, json};
use sideveil_protocol::rpc::{RpcRequest, RpcResponse};
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
            format_rpc_output(&resp, output_mode)
        }
        CliAction::RpcFromStdin {
            method,
            output_mode,
        } => {
            let params = read_json_params_from_reader(io::stdin().lock())?;
            let resp = execute_rpc(&inv.socket_path, inv.io_timeout, method, params)?;
            format_rpc_output(&resp, output_mode)
        }
        CliAction::ValidatePlugin { target } => dx::run_validate(target),
        CliAction::PackPlugin {
            source_dir,
            output_path,
        } => dx::run_pack(source_dir, output_path.as_deref()),
        CliAction::InitPlugin {
            plugin_id,
            template,
            output_dir,
        } => dx::run_init(plugin_id, *template, output_dir.as_deref()),
    }
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

fn format_rpc_output(resp: &RpcResponse, output_mode: &OutputMode) -> Result<String, String> {
    match output_mode {
        OutputMode::UnwrapResult => {
            let result = resp.result.clone().unwrap_or(Value::Null);
            serde_json::to_string(&result).map_err(|e| format!("Failed encoding output: {e}"))
        }
        OutputMode::FullEnvelope => {
            serde_json::to_string(resp).map_err(|e| format!("Failed encoding output: {e}"))
        }
    }
}

pub(crate) fn read_json_params_from_reader<R: Read>(reader: R) -> Result<Option<Value>, String> {
    let mut raw = Vec::new();
    let limit = (MAX_STDIN_PARAMS_BYTES as u64).saturating_add(1);
    reader
        .take(limit)
        .read_to_end(&mut raw)
        .map_err(|e| format!("Failed reading JSON params from stdin: {e}"))?;
    if raw.len() > MAX_STDIN_PARAMS_BYTES {
        return Err(format!(
            "Stdin JSON params exceed maximum {} MiB limit",
            MAX_STDIN_PARAMS_BYTES / (1024 * 1024)
        ));
    }
    let text = String::from_utf8(raw)
        .map_err(|e| format!("Stdin JSON params are not valid UTF-8: {e}"))?;
    if text.trim().is_empty() {
        return Ok(None);
    }
    serde_json::from_str::<Value>(&text)
        .map(Some)
        .map_err(|e| format!("Invalid JSON params from stdin: {e}"))
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
