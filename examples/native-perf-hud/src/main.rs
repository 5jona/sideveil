//! Reference `uds-native` plugin backend pushing zero-WebView `NativeHudScene` telemetry
//! frames to `sideveild` -> `libsideveil_hud.so` (`vello_cpu` NEON + HWUI text).

mod sampler;

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use sampler::{read_cpu, sample_cpu, sample_mem_mb, sample_temp_c};
use serde_json::{Value, json};
use sideveil_protocol::hud::{
    HudBackdropKind, HudPrimitive, HudTextAlign, HudTextOverlay, NativeHudScene,
};
use sideveil_protocol::paths;
use sideveil_protocol::rpc::{HostEventEnvelope, RpcRequest, RpcResponse, methods};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};

const PLUGIN_ID: &str = "example.native-perf-hud";
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
    let dark_rx = Arc::clone(dark_surface);
    let sampler_thread = thread::current();
    let mut reply_stream = write_stream.try_clone()?;
    thread::spawn(move || {
        let mut line = String::new();
        while reader.read_line(&mut line).unwrap_or(0) > 0 {
            if let Ok(req) = serde_json::from_str::<RpcRequest>(line.trim()) {
                let resp = handle_rpc(req, &enabled_rx, &click_rx, &dark_rx);
                let _ = send_json_line(&mut reply_stream, &resp);
                sampler_thread.unpark();
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
        send_json_line(&mut write_stream, &frame)?;
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

fn build_scene(
    history: &VecDeque<f32>,
    cpu_pct: f32,
    mem_used: u64,
    mem_total: u64,
    temp_c: f32,
    click_through: bool,
    is_dark: bool,
) -> NativeHudScene {
    let mem_ratio = if mem_total > 0 {
        (mem_used as f32 / mem_total as f32).clamp(0.0, 1.0)
    } else {
        0.0
    };
    let temp_ratio = ((temp_c - 20.0) / 65.0).clamp(0.0, 1.0);
    let (
        primary_text,
        gauge_text,
        track_argb,
        wave_stroke,
        wave_fill_top,
        bar_start,
        bar_end,
        gauge_end,
    ) = if is_dark {
        (
            0xFFF1_F5F9,
            0xFFF8_FAFC,
            0x33FF_FFFF,
            0xFF38_BDF8,
            0x5538_BDF8,
            0xFF34_D399,
            0xFF38_BDF8,
            0xFFEF_4444,
        )
    } else {
        (
            0xFF0F_172A,
            0xFF0F_172A,
            0x280F_172A,
            0xFF02_84C7,
            0x4402_84C7,
            0xFF05_9669,
            0xFF02_84C7,
            0xFFDC_2626,
        )
    };
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
                stroke_argb: wave_stroke,
                stroke_width: 2.0,
                fill_top_argb: Some(wave_fill_top),
                fill_bottom_argb: Some(wave_stroke & 0x00FF_FFFF),
                smooth: true,
            },
            HudPrimitive::ProgressBar {
                x: 12.0,
                y: 74.0,
                w: 122.0,
                h: 6.0,
                radius: 3.0,
                value: mem_ratio,
                track_argb,
                fill_start_argb: bar_start,
                fill_end_argb: Some(bar_end),
            },
            HudPrimitive::RadialGauge {
                cx: 174.0,
                cy: 48.0,
                radius: 22.0,
                thickness: 5.0,
                value: temp_ratio,
                start_angle_deg: 135.0,
                sweep_angle_deg: 270.0,
                track_argb,
                fill_start_argb: bar_start,
                fill_end_argb: Some(gauge_end),
            },
        ],
        texts: vec![
            HudTextOverlay {
                x: 12.0,
                y: 20.0,
                text: format!("CPU {cpu_pct:4.1}%  RAM {:.1}G", mem_used as f32 / 1024.0),
                size_sp: 11.0,
                color_argb: primary_text,
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
                color_argb: gauge_text,
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
