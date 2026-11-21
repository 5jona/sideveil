//! Plugin backend UDS session loop with ephemeral event routing and per-plugin rate protection.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use sideveil_protocol::hud::NativeHudScene;
use sideveil_protocol::plugin::{
    PLUGIN_EVENT_LAGGED_SUBEVENT, PluginBackendEvent, PluginBackendState, PluginCapability,
};
use sideveil_protocol::rpc::{RpcResponse, methods};
use tokio::io::{AsyncRead, BufReader};

use super::{read_bounded_line, write_json_line};
use crate::peercred::PeerCred;
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

pub(super) async fn handle_plugin_backend_session<R: AsyncRead + Unpin>(
    mut reader: BufReader<R>,
    mut write_half: tokio::net::unix::OwnedWriteHalf,
    cred: PeerCred,
    plugin_id: String,
    package_name: Option<String>,
    state: Arc<HubState>,
) {
    let (session_id, mut out_rx) = state.plugin_backends().register(&plugin_id);
    state.logs().info(format!(
        "Plugin backend connected: id={plugin_id} session=#{session_id} uid={} pid={:?} pkg={:?}",
        cred.uid, cred.pid, package_name
    ));
    state.emit_plugin_backend_state(&plugin_id, PluginBackendState::Online);

    let writer_task = tokio::spawn(async move {
        while let Some(req) = out_rx.recv().await {
            if write_json_line(&mut write_half, &req).await.is_err() {
                break;
            }
        }
    });

    let mut limiter =
        PluginEventRateLimiter::new(RELIABLE_EVENTS_PER_WINDOW, RATE_WINDOW, Instant::now());

    while let Ok(Some(line)) = read_bounded_line(&mut reader).await {
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
            route_backend_event(&state, &plugin_id, env, &mut limiter);
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
    env: PluginBackendEvent,
    limiter: &mut PluginEventRateLimiter,
) {
    match env.event.as_str() {
        methods::HUD_UPDATE_SCENE => {
            if state
                .plugin_registry()
                .has_capability(plugin_id, PluginCapability::NativeHud)
                && let Some(payload) = env.payload
                && let Ok(mut scene) = serde_json::from_value::<NativeHudScene>(payload)
            {
                scene.plugin_id = plugin_id.to_owned();
                state.push_hud_scene(scene);
            }
        }
        methods::HUD_DISMISS => state.dismiss_hud(Some(plugin_id.to_owned())),
        _ if env.ephemeral => {
            state.emit_plugin_scoped_event_ephemeral(plugin_id, env.event, env.payload);
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
