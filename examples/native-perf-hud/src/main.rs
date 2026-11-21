//! Reference `uds-native` plugin backend pushing zero-WebView `NativeHudScene` telemetry
//! frames to `sideveild` -> `libsideveil_hud.so` (`vello_cpu` NEON + HWUI text).

use std::collections::VecDeque;
use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::hud::{
    HudBackdropKind, HudPrimitive, HudTextAlign, HudTextOverlay, NativeHudScene,
};
use sideveil_protocol::paths;
use sideveil_protocol::rpc::{HostEventEnvelope, RpcRequest, RpcResponse, methods};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};

const PLUGIN_ID: &str = "example.native-perf-hud";
const HISTORY_LEN: usize = 24;

#[derive(Default)]
struct CpuPrev {
    idle: u64,
    total: u64,
}

fn main() {
    let socket = std::env::args()
        .nth(1)
        .unwrap_or_else(|| paths::HUB_SOCKET.to_owned());
    let enabled = Arc::new(AtomicBool::new(true));
    let click_through = Arc::new(AtomicBool::new(false));

    loop {
        if let Err(err) = run_session(&socket, &enabled, &click_through) {
            eprintln!("[{PLUGIN_ID}] session ended: {err}");
        }
        thread::sleep(Duration::from_secs(2));
    }
}

fn run_session(
    socket_path: &str,
    enabled: &Arc<AtomicBool>,
    click_through: &Arc<AtomicBool>,
) -> std::io::Result<()> {
    let mut write_stream = UnixStream::connect(socket_path)?;
    let read_stream = write_stream.try_clone()?;

    let hello = UdsClientHello::PluginBackend {
        plugin_id: PLUGIN_ID.to_owned(),
        package_name: None,
    };
    send_json_line(&mut write_stream, &hello)?;

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
    let mut reply_stream = write_stream.try_clone()?;
    thread::spawn(move || {
        let mut line = String::new();
        while reader.read_line(&mut line).unwrap_or(0) > 0 {
            if let Ok(req) = serde_json::from_str::<RpcRequest>(line.trim()) {
                let resp = handle_rpc(req, &enabled_rx, &click_rx);
                let _ = send_json_line(&mut reply_stream, &resp);
            }
            line.clear();
        }
    });

    let mut cpu_prev = read_cpu().unwrap_or_default();
    let mut cpu_history: VecDeque<f32> = VecDeque::from(vec![0.0; HISTORY_LEN]);
    let mut was_enabled = false;

    loop {
        if !enabled.load(Ordering::Relaxed) {
            if was_enabled {
                was_enabled = false;
                let dismiss = HostEventEnvelope {
                    event: methods::HUD_DISMISS.to_owned(),
                    payload: None,
                };
                send_json_line(&mut write_stream, &dismiss)?;
            }
            thread::sleep(Duration::from_millis(300));
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
        );

        let frame = HostEventEnvelope {
            event: methods::HUD_UPDATE_SCENE.to_owned(),
            payload: serde_json::to_value(scene).ok(),
        };
        send_json_line(&mut write_stream, &frame)?;
        thread::sleep(Duration::from_millis(500));
    }
}

fn handle_rpc(req: RpcRequest, enabled: &AtomicBool, click_through: &AtomicBool) -> RpcResponse {
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
            RpcResponse::ok(
                req.id,
                json!({ "enabled": enabled.load(Ordering::Relaxed) }),
            )
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
            RpcResponse::ok(
                req.id,
                json!({ "clickThrough": click_through.load(Ordering::Relaxed) }),
            )
        }
        _ => RpcResponse::ok(
            req.id,
            json!({
                "enabled": enabled.load(Ordering::Relaxed),
                "clickThrough": click_through.load(Ordering::Relaxed),
            }),
        ),
    }
}

fn build_scene(
    history: &VecDeque<f32>,
    cpu_pct: f32,
    mem_used: u64,
    mem_total: u64,
    temp_c: f32,
    click_through: bool,
) -> NativeHudScene {
    let mem_ratio = if mem_total > 0 {
        (mem_used as f32 / mem_total as f32).clamp(0.0, 1.0)
    } else {
        0.0
    };
    let temp_ratio = ((temp_c - 20.0) / 65.0).clamp(0.0, 1.0);
    NativeHudScene {
        plugin_id: PLUGIN_ID.to_owned(),
        width_dp: 216.0,
        height_dp: 94.0,
        corner_radius_dp: 18.0,
        backdrop: HudBackdropKind::NativeGlass,
        click_through,
        anchor_x_percent: 4.0,
        anchor_y_percent: 12.0,
        primitives: vec![
            HudPrimitive::Sparkline {
                x: 12.0,
                y: 28.0,
                w: 122.0,
                h: 36.0,
                values: history.iter().copied().collect(),
                min: Some(0.0),
                max: Some(100.0),
                stroke_argb: 0xFF38_BDF8,
                stroke_width: 2.0,
                fill_top_argb: Some(0x5538_BDF8),
                fill_bottom_argb: Some(0x0038_BDF8),
                smooth: true,
            },
            HudPrimitive::ProgressBar {
                x: 12.0,
                y: 74.0,
                w: 122.0,
                h: 6.0,
                radius: 3.0,
                value: mem_ratio,
                track_argb: 0x33FF_FFFF,
                fill_start_argb: 0xFF34_D399,
                fill_end_argb: Some(0xFF38_BDF8),
            },
            HudPrimitive::RadialGauge {
                cx: 174.0,
                cy: 48.0,
                radius: 22.0,
                thickness: 5.0,
                value: temp_ratio,
                start_angle_deg: 135.0,
                sweep_angle_deg: 270.0,
                track_argb: 0x33FF_FFFF,
                fill_start_argb: 0xFF34_D399,
                fill_end_argb: Some(0xFFEF_4444),
            },
        ],
        texts: vec![
            HudTextOverlay {
                x: 12.0,
                y: 20.0,
                text: format!("CPU {cpu_pct:4.1}%  RAM {:.1}G", mem_used as f32 / 1024.0),
                size_sp: 11.0,
                color_argb: 0xFFF1_F5F9,
                align: HudTextAlign::Start,
                bold: true,
                tabular_nums: true,
                max_width_dp: Some(130.0),
            },
            HudTextOverlay {
                x: 174.0,
                y: 52.0,
                text: format!("{temp_c:.0}°C"),
                size_sp: 11.0,
                color_argb: 0xFFF8_FAFC,
                align: HudTextAlign::Center,
                bold: true,
                tabular_nums: true,
                max_width_dp: None,
            },
        ],
    }
}

fn send_json_line<T: serde::Serialize>(stream: &mut UnixStream, value: &T) -> std::io::Result<()> {
    let mut bytes = serde_json::to_vec(value)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
    bytes.push(b'\n');
    stream.write_all(&bytes)?;
    stream.flush()
}

fn read_cpu() -> Option<CpuPrev> {
    let text = fs::read_to_string("/proc/stat").ok()?;
    let nums: Vec<u64> = text
        .lines()
        .next()?
        .split_whitespace()
        .skip(1)
        .filter_map(|s| s.parse().ok())
        .collect();
    if nums.len() < 4 {
        return None;
    }
    let idle = nums[3] + nums.get(4).copied().unwrap_or(0);
    let total: u64 = nums.iter().copied().sum();
    Some(CpuPrev { idle, total })
}

fn sample_cpu(prev: &mut CpuPrev) -> f32 {
    let Some(cur) = read_cpu() else {
        return 0.0;
    };
    let d_tot = cur.total.saturating_sub(prev.total);
    let d_idle = cur.idle.saturating_sub(prev.idle);
    *prev = cur;
    if d_tot == 0 {
        0.0
    } else {
        ((d_tot.saturating_sub(d_idle)) as f32 / d_tot as f32 * 100.0).clamp(0.0, 100.0)
    }
}

fn sample_mem_mb() -> (u64, u64) {
    let Ok(raw) = fs::read_to_string("/proc/meminfo") else {
        return (0, 0);
    };
    let mut total = 0_u64;
    let mut avail = 0_u64;
    for line in raw.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total = rest
                .split_whitespace()
                .next()
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            avail = rest
                .split_whitespace()
                .next()
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
        }
    }
    (total.saturating_sub(avail) / 1024, total / 1024)
}

fn sample_temp_c() -> Option<f32> {
    for entry in fs::read_dir("/sys/class/thermal").ok()?.flatten().take(32) {
        if !entry
            .file_name()
            .to_string_lossy()
            .starts_with("thermal_zone")
        {
            continue;
        }
        let raw = fs::read_to_string(entry.path().join("temp"))
            .ok()
            .and_then(|s| s.trim().parse::<f32>().ok())?;
        let c = if raw > 200.0 { raw / 1000.0 } else { raw };
        if (15.0..=110.0).contains(&c) {
            return Some(c);
        }
    }
    None
}
