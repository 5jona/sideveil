//! Reference `uds-native` plugin backend pushing zero-WebView `NativeHudScene` telemetry
//! frames to `sideveild` -> `libsideveil_hud.so` (`vello_cpu` NEON + HWUI text).

mod sampler;
mod scene;

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread;
use std::time::Duration;

use sampler::{read_cpu, sample_cpu, sample_mem_mb, sample_temp_c};
use scene::{PLUGIN_ID, build_scene};
use serde_json::{Value, json};
use sideveil_protocol::paths;
use sideveil_protocol::rpc::{HostEventEnvelope, RpcRequest, RpcResponse, methods};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};

const HISTORY_LEN: usize = 24;

fn main() {
    let socket = std::env::args()
        .nth(1)
        .unwrap_or_else(|| paths::HUB_SOCKET.to_owned());
    let enabled = Arc::new(AtomicBool::new(false));
    let click_through = Arc::new(AtomicBool::new(false));
    let dark_surface = Arc::new(AtomicBool::new(true));

    loop {
        if let Err(err) = run_session(&socket, &enabled, &click_through, &dark_surface) {
            eprintln!("[{PLUGIN_ID}] session ended: {err}");
        }
        thread::sleep(Duration::from_secs(2));
    }
}

fn run_session(
    socket_path: &str,
    enabled: &Arc<AtomicBool>,
    click_through: &Arc<AtomicBool>,
    dark_surface: &Arc<AtomicBool>,
) -> std::io::Result<()> {
    let stream = UnixStream::connect(socket_path)?;
    let read_stream = stream.try_clone()?;
    let writer = Arc::new(Mutex::new(stream));

    let hello = UdsClientHello::PluginBackend {
        plugin_id: PLUGIN_ID.to_owned(),
        package_name: None,
    };
    send_json_line(&writer, &hello)?;

    let mut reader = BufReader::new(read_stream);
    let mut ack_line = String::new();
    reader.read_line(&mut ack_line)?;
    let ack: UdsHandshakeAck = serde_json::from_str(ack_line.trim())
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
    if !ack.ok {
        return Err(std::io::Error::other(
            ack.error.unwrap_or_else(|| "handshake rejected".to_owned()),
        ));
    }

    let enabled_rx = Arc::clone(enabled);
    let click_rx = Arc::clone(click_through);
    let dark_rx = Arc::clone(dark_surface);
    let disconnected = Arc::new(AtomicBool::new(false));
    let disconnected_rx = Arc::clone(&disconnected);
    let sampler_thread = thread::current();
    let reply_writer = Arc::clone(&writer);
    thread::spawn(move || {
        let mut line = String::new();
        while reader.read_line(&mut line).unwrap_or(0) > 0 {
            if let Ok(req) = serde_json::from_str::<RpcRequest>(line.trim()) {
                let resp = handle_rpc(req, &enabled_rx, &click_rx, &dark_rx);
                if send_json_line(&reply_writer, &resp).is_err() {
                    break;
                }
                sampler_thread.unpark();
            }
            line.clear();
        }
        disconnected_rx.store(true, Ordering::Release);
        sampler_thread.unpark();
    });

    let mut cpu_prev = read_cpu().unwrap_or_default();
    let mut cpu_history: VecDeque<f32> = VecDeque::from(vec![0.0; HISTORY_LEN]);
    let mut was_enabled = false;

    loop {
        if disconnected.load(Ordering::Acquire) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "hub socket closed",
            ));
        }
        if !enabled.load(Ordering::Relaxed) {
            if was_enabled {
                was_enabled = false;
                let dismiss = HostEventEnvelope {
                    event: methods::HUD_DISMISS.to_owned(),
                    payload: None,
                };
                send_json_line(&writer, &dismiss)?;
            }
            thread::park_timeout(Duration::from_millis(300));
            continue;
        }
        was_enabled = true;

        let cpu_pct = sample_cpu(&mut cpu_prev);
        if cpu_history.len() >= HISTORY_LEN {
            cpu_history.pop_front();
        }
        cpu_history.push_back(cpu_pct);
        let (mem_used, mem_total) = sample_mem_mb();
        let temp_c = sample_temp_c().unwrap_or(36.0);
        let scene = build_scene(
            &cpu_history,
            cpu_pct,
            mem_used,
            mem_total,
            temp_c,
            click_through.load(Ordering::Relaxed),
            dark_surface.load(Ordering::Relaxed),
        );

        let frame = HostEventEnvelope {
            event: methods::HUD_UPDATE_SCENE.to_owned(),
            payload: serde_json::to_value(scene).ok(),
        };
        send_json_line(&writer, &frame)?;
        thread::park_timeout(Duration::from_millis(500));
    }
}

fn handle_rpc(
    req: RpcRequest,
    enabled: &AtomicBool,
    click_through: &AtomicBool,
    dark_surface: &AtomicBool,
) -> RpcResponse {
    match req.method.as_str() {
        "setEnabled" => {
            if let Some(flag) = req
                .params
                .as_ref()
                .and_then(|v| v.get("enabled"))
                .and_then(Value::as_bool)
            {
                enabled.store(flag, Ordering::Relaxed);
            }
        }
        "setClickThrough" => {
            if let Some(flag) = req
                .params
                .as_ref()
                .and_then(|v| v.get("clickThrough"))
                .and_then(Value::as_bool)
            {
                click_through.store(flag, Ordering::Relaxed);
            }
        }
        "setAppearance" => {
            if let Some(flag) = req
                .params
                .as_ref()
                .and_then(|v| v.get("isDark"))
                .and_then(Value::as_bool)
            {
                dark_surface.store(flag, Ordering::Relaxed);
            }
        }
        _ => {}
    }
    RpcResponse::ok(
        req.id,
        json!({
            "enabled": enabled.load(Ordering::Relaxed),
            "clickThrough": click_through.load(Ordering::Relaxed),
            "isDark": dark_surface.load(Ordering::Relaxed),
        }),
    )
}

fn send_json_line<T: serde::Serialize>(
    writer: &Mutex<UnixStream>,
    value: &T,
) -> std::io::Result<()> {
    let mut bytes = serde_json::to_vec(value)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
    bytes.push(b'\n');
    let mut stream = writer.lock().unwrap_or_else(PoisonError::into_inner);
    stream.write_all(&bytes)?;
    stream.flush()
}

#[cfg(test)]
mod tests {
    use std::os::unix::net::UnixListener;

    use super::*;

    #[test]
    fn run_session_exits_promptly_on_hub_eof_when_idle() {
        let sock_path =
            std::env::temp_dir().join(format!("sideveil-perf-hud-eof-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&sock_path);
        let listener = UnixListener::bind(&sock_path).expect("bind unix listener");

        let server = thread::spawn(move || {
            let (mut conn, _) = listener.accept().expect("accept");
            let mut reader = BufReader::new(conn.try_clone().expect("clone"));
            let mut hello = String::new();
            reader.read_line(&mut hello).expect("read hello");
            let ack = UdsHandshakeAck {
                ok: true,
                error: None,
            };
            let line = format!("{}\n", serde_json::to_string(&ack).expect("encode ack"));
            conn.write_all(line.as_bytes()).expect("write ack");
            conn.flush().expect("flush ack");
            thread::sleep(Duration::from_millis(50));
            drop(conn);
        });

        let enabled = Arc::new(AtomicBool::new(false));
        let click_through = Arc::new(AtomicBool::new(false));
        let dark_surface = Arc::new(AtomicBool::new(true));
        let started = std::time::Instant::now();
        let res = run_session(
            sock_path.to_str().expect("utf8 path"),
            &enabled,
            &click_through,
            &dark_surface,
        );
        assert!(res.is_err());
        assert!(started.elapsed() < Duration::from_secs(2));

        server.join().expect("join server");
        let _ = std::fs::remove_file(&sock_path);
    }
}
