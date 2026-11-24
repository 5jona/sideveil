//! Reference `uds-native` plugin backend pushing zero-WebView `NativeHudScene` telemetry
//! frames to `sideveild` -> `libsideveil_hud.so` (`vello_cpu` NEON + HWUI text).

mod sampler;
mod scene;

use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use sampler::{read_cpu, sample_cpu, sample_mem_mb, sample_temp_c};
use scene::{PLUGIN_ID, build_scene};
use serde_json::{Value, json};
use sideveil_plugin_sdk::{
    CancellationToken, PluginBackendConfig, PluginBackendHandle, PluginBackendRunner,
    PluginRpcContext, PluginRpcError, PluginRpcHandler, SdkError, wait_shutdown_signal,
};
use sideveil_protocol::paths;
use sideveil_protocol::rpc::{RpcRequest, events};
use tokio::sync::Notify;

const HISTORY_LEN: usize = 24;
const SAMPLE_INTERVAL: Duration = Duration::from_millis(500);

#[derive(Debug)]
struct HudState {
    enabled: AtomicBool,
    click_through: AtomicBool,
    dark_surface: AtomicBool,
    wake: Notify,
}

impl HudState {
    fn new() -> Self {
        Self {
            enabled: AtomicBool::new(false),
            click_through: AtomicBool::new(false),
            dark_surface: AtomicBool::new(true),
            wake: Notify::new(),
        }
    }

    fn snapshot(&self) -> Value {
        json!({
            "enabled": self.enabled.load(Ordering::Relaxed),
            "clickThrough": self.click_through.load(Ordering::Relaxed),
            "isDark": self.dark_surface.load(Ordering::Relaxed),
        })
    }
}

#[derive(Debug, Clone)]
struct PerfHudHandler {
    state: Arc<HudState>,
}

impl PluginRpcHandler for PerfHudHandler {
    async fn handle_rpc(
        &self,
        req: RpcRequest,
        _ctx: PluginRpcContext,
    ) -> Result<Value, PluginRpcError> {
        let params = req.params.as_ref();
        match req.method.as_str() {
            "setEnabled" => {
                if let Some(flag) = params
                    .and_then(|v| v.get("enabled"))
                    .and_then(Value::as_bool)
                {
                    self.state.enabled.store(flag, Ordering::Relaxed);
                }
            }
            "setClickThrough" => {
                if let Some(flag) = params
                    .and_then(|v| v.get("clickThrough"))
                    .and_then(Value::as_bool)
                {
                    self.state.click_through.store(flag, Ordering::Relaxed);
                }
            }
            "setAppearance" => {
                if let Some(flag) = params
                    .and_then(|v| v.get("isDark"))
                    .and_then(Value::as_bool)
                {
                    self.state.dark_surface.store(flag, Ordering::Relaxed);
                }
            }
            _ => {}
        }
        self.state.wake.notify_one();
        Ok(self.state.snapshot())
    }

    async fn on_system_event(&self, event: String, payload: Value, _ctx: PluginRpcContext) {
        if event == events::APPEARANCE_CHANGED
            && let Some(flag) = parse_dark_surface(&payload)
        {
            self.state.dark_surface.store(flag, Ordering::Relaxed);
            self.state.wake.notify_one();
        }
    }

    async fn on_connected(&self, _ctx: PluginRpcContext) {
        self.state.wake.notify_one();
    }

    async fn on_error(&self, err: &SdkError) {
        eprintln!("[{PLUGIN_ID}] hub session error: {err}");
    }
}

fn parse_dark_surface(payload: &Value) -> Option<bool> {
    if let Some(mode) = payload.get("colorMode").and_then(Value::as_str) {
        match mode {
            "light" => return Some(false),
            "dark" => return Some(true),
            _ => {}
        }
    }
    payload
        .get("isDark")
        .or_else(|| payload.get("dark"))
        .and_then(Value::as_bool)
}

async fn run_sampler_loop(
    handle: PluginBackendHandle,
    state: Arc<HudState>,
    cancel: CancellationToken,
) {
    let mut cpu_prev = read_cpu().unwrap_or_default();
    let mut cpu_history: VecDeque<f32> = VecDeque::from(vec![0.0; HISTORY_LEN]);
    let mut was_enabled = false;

    while !cancel.is_cancelled() {
        if !handle.is_connected() {
            was_enabled = false;
            tokio::select! {
                biased;
                () = cancel.cancelled() => break,
                () = state.wake.notified() => {}
                () = tokio::time::sleep(Duration::from_millis(250)) => {}
            }
            continue;
        }

        if !state.enabled.load(Ordering::Relaxed) {
            if was_enabled {
                was_enabled = false;
                let _ = handle.dismiss_hud().await;
            }
            tokio::select! {
                biased;
                () = cancel.cancelled() => break,
                () = state.wake.notified() => {}
            }
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
            state.click_through.load(Ordering::Relaxed),
            state.dark_surface.load(Ordering::Relaxed),
        );
        let _ = handle.push_hud(&scene).await;

        tokio::select! {
            biased;
            () = cancel.cancelled() => break,
            () = state.wake.notified() => {}
            () = tokio::time::sleep(SAMPLE_INTERVAL) => {}
        }
    }
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let config = PluginBackendConfig::from_env().unwrap_or_else(|_| {
        let socket = std::env::args()
            .nth(1)
            .unwrap_or_else(|| paths::HUB_SOCKET.to_owned());
        PluginBackendConfig::new(PLUGIN_ID).with_socket_path(socket)
    });
    let cancel = CancellationToken::new();

    let (handle, runner) = match PluginBackendRunner::new(config, cancel.clone()) {
        Ok(pair) => pair,
        Err(err) => {
            eprintln!("[{PLUGIN_ID}] invalid config: {err}");
            std::process::exit(1);
        }
    };

    let state = Arc::new(HudState::new());
    let handler = PerfHudHandler {
        state: Arc::clone(&state),
    };
    let sampler_task = tokio::spawn(run_sampler_loop(
        handle.clone(),
        Arc::clone(&state),
        cancel.clone(),
    ));

    let shutdown_cancel = cancel.clone();
    let signal_task = tokio::spawn(async move {
        wait_shutdown_signal().await;
        shutdown_cancel.cancel();
    });

    if let Err(err) = runner.run(handler).await {
        eprintln!("[{PLUGIN_ID}] backend exited with error: {err}");
    }
    cancel.cancel();
    signal_task.abort();
    let _ = sampler_task.await;
}

#[cfg(test)]
mod tests;
