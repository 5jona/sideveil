//! Plugin backend UDS session loop with ephemeral event routing and per-plugin rate protection.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use sideveil_protocol::hud::NativeHudScene;
use sideveil_protocol::plugin::{
    PLUGIN_EVENT_LAGGED_SUBEVENT, PluginBackendEvent, PluginBackendState, PluginCapability,
};
use sideveil_protocol::rpc::{RpcResponse, events, methods};
use sideveil_protocol::sensor::NetworkTransportKind;
use tokio::io::{AsyncRead, BufReader};

use super::{DEFAULT_MAX_LINE_BYTES, LineFrameBuf, read_bounded_line, write_json_line_with_buf};
use crate::peercred::PeerCred;
use crate::rate_limiter::RateLimitBucket;
use crate::state::HubState;

/// Maximum reliable `plugin.event` frames forwarded per plugin backend per 1-second window.
const RELIABLE_EVENTS_PER_WINDOW: u32 = 120;
const RATE_WINDOW: Duration = Duration::from_secs(1);

#[derive(Debug)]
struct PluginEventRateLimiter {
    window_start: Instant,
    window: Duration,
    limit: u32,
    sent_in_window: u32,
    dropped_in_window: u32,
}

#[derive(Debug, PartialEq, Eq)]
enum RateDecision {
    Allow { prev_dropped: u32 },
    Drop,
}

impl PluginEventRateLimiter {
    fn new(limit: u32, window: Duration, now: Instant) -> Self {
        Self {
            window_start: now,
            window,
            limit,
            sent_in_window: 0,
            dropped_in_window: 0,
        }
    }

    fn check(&mut self, now: Instant) -> RateDecision {
        let mut prev_dropped = 0;
        if now.duration_since(self.window_start) >= self.window {
            prev_dropped = self.dropped_in_window;
            self.window_start = now;
            self.sent_in_window = 0;
            self.dropped_in_window = 0;
        }
        if self.sent_in_window < self.limit {
            self.sent_in_window += 1;
            RateDecision::Allow { prev_dropped }
        } else {
            self.dropped_in_window = self.dropped_in_window.saturating_add(1);
            RateDecision::Drop
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub(super) async fn handle_plugin_backend_session<R: AsyncRead + Unpin>(
    mut reader: BufReader<R>,
    mut line_buf: LineFrameBuf,
    mut write_half: tokio::net::unix::OwnedWriteHalf,
    cred: PeerCred,
    plugin_id: String,
    package_name: Option<String>,
    is_native: bool,
    state: Arc<HubState>,
) {
    let Ok((session_id, mut out_rx, mut disconnect_rx)) = state
        .plugin_backends()
        .register(&plugin_id, is_native, cred.uid)
    else {
        state.logs().warn(format!(
            "Failed registering backend '{plugin_id}' for uid={}: owned by another UID",
            cred.uid
        ));
        return;
    };
    state.logs().info(format!(
        "Plugin backend connected: id={plugin_id} session=#{session_id} native={is_native} uid={} pid={:?} pkg={:?}",
        cred.uid, cred.pid, package_name
    ));
    state.emit_plugin_backend_state(&plugin_id, PluginBackendState::Online);

    if is_native
        && state
            .plugin_registry()
            .has_capability(&plugin_id, PluginCapability::NetworkContext)
    {
        let net_ctx = state.network_context();
        if (net_ctx.transport != NetworkTransportKind::None
            || net_ctx.ssid.is_some()
            || net_ctx.gateway.is_some())
            && let Ok(val) = serde_json::to_value(&net_ctx)
        {
            state.plugin_backends().send_system_event(
                &plugin_id,
                session_id,
                events::NETWORK_CONTEXT_CHANGED,
                val,
            );
        }
    }

    let writer_task = tokio::spawn(async move {
        let mut write_buf = Vec::with_capacity(512);
        while let Some(frame) = out_rx.recv().await {
            if write_json_line_with_buf(&mut write_half, &frame, &mut write_buf)
                .await
                .is_err()
            {
                break;
            }
        }
    });

    let mut limiter =
        PluginEventRateLimiter::new(RELIABLE_EVENTS_PER_WINDOW, RATE_WINDOW, Instant::now());

    loop {
        let line = tokio::select! {
            biased;
            res = disconnect_rx.changed() => {
                if res.is_err() || *disconnect_rx.borrow() {
                    break;
                }
                continue;
            }
            read_res = read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES) => {
                match read_res {
                    Ok(Some(line)) => line,
                    Ok(None) | Err(_) => break,
                }
            }
        };
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let Ok(val) = serde_json::from_str::<Value>(trimmed) else {
            continue;
        };
        let Some(obj) = val.as_object() else {
            continue;
        };
        if obj.contains_key("id")
            && let Ok(resp) = serde_json::from_value::<RpcResponse>(val.clone())
        {
            state
                .plugin_backends()
                .complete_response(&plugin_id, session_id, resp);
            continue;
        }
        if obj.contains_key("event")
            && let Ok(env) = serde_json::from_value::<PluginBackendEvent>(val)
        {
            route_backend_event(&state, &plugin_id, session_id, env, &mut limiter);
        }
    }

    writer_task.abort();
    if state.plugin_backends().unregister(&plugin_id, session_id) {
        state
            .logs()
            .info(format!("Plugin backend disconnected: id={plugin_id}"));
        state.dismiss_hud(Some(plugin_id.clone()));
        state.emit_plugin_backend_state(&plugin_id, PluginBackendState::Offline);
    }
}

fn route_backend_event(
    state: &Arc<HubState>,
    plugin_id: &str,
    session_id: u64,
    env: PluginBackendEvent,
    limiter: &mut PluginEventRateLimiter,
) {
    if !state
        .plugin_backends()
        .is_session_active(plugin_id, session_id)
        || !state.plugin_registry().is_plugin_enabled(plugin_id)
    {
        return;
    }
    match env.event.as_str() {
        methods::HUD_UPDATE_SCENE => {
            if state
                .plugin_registry()
                .has_capability(plugin_id, PluginCapability::NativeHud)
                && state
                    .rate_limiter()
                    .check_and_record(plugin_id, RateLimitBucket::HudUpdate)
                && let Some(payload) = env.payload
                && let Ok(mut scene) = serde_json::from_value::<NativeHudScene>(payload)
            {
                scene.plugin_id = plugin_id.to_owned();
                state.push_hud_scene(scene);
            }
        }
        methods::HUD_DISMISS => {
            if state
                .plugin_registry()
                .has_capability(plugin_id, PluginCapability::NativeHud)
            {
                state.dismiss_hud(Some(plugin_id.to_owned()));
            }
        }
        _ if env.ephemeral => {
            if state
                .rate_limiter()
                .check_and_record(plugin_id, RateLimitBucket::HudUpdate)
            {
                state.emit_plugin_scoped_event_ephemeral(plugin_id, env.event, env.payload);
            }
        }
        _ => match limiter.check(Instant::now()) {
            RateDecision::Allow { prev_dropped } => {
                if prev_dropped > 0 {
                    state.logs().warn(format!(
                        "Plugin backend '{plugin_id}' rate-limited ({prev_dropped} event(s) dropped)"
                    ));
                    state.emit_plugin_scoped_event(
                        plugin_id,
                        PLUGIN_EVENT_LAGGED_SUBEVENT.to_owned(),
                        Some(json!({ "dropped": prev_dropped })),
                    );
                }
                state.emit_plugin_scoped_event(plugin_id, env.event, env.payload);
            }
            RateDecision::Drop => {}
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rate_limiter_sheds_bursts_and_reports_lag_on_next_window() {
        let start = Instant::now();
        let mut limiter = PluginEventRateLimiter::new(3, Duration::from_secs(1), start);

        assert_eq!(
            limiter.check(start),
            RateDecision::Allow { prev_dropped: 0 }
        );
        assert_eq!(
            limiter.check(start),
            RateDecision::Allow { prev_dropped: 0 }
        );
        assert_eq!(
            limiter.check(start),
            RateDecision::Allow { prev_dropped: 0 }
        );
        assert_eq!(limiter.check(start), RateDecision::Drop);
        assert_eq!(limiter.check(start), RateDecision::Drop);

        let next_window = start + Duration::from_millis(1050);
        assert_eq!(
            limiter.check(next_window),
            RateDecision::Allow { prev_dropped: 2 }
        );
        assert_eq!(
            limiter.check(next_window),
            RateDecision::Allow { prev_dropped: 0 }
        );
    }
}
