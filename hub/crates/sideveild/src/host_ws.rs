//! Authenticated loopback WebSocket endpoint (`127.0.0.1:0/ws`) for `SideVeilHost` (`priv-app`).

use std::io;
use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::Value;
use sideveil_protocol::rpc::{HostEventEnvelope, RpcResponse};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::MissedTickBehavior;
use tokio_tungstenite::accept_hdr_async;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};

use crate::asset_server;
use crate::outbound_queue;
use crate::rpc::{self, RpcCaller};
use crate::state::HubState;

const PING_INTERVAL: Duration = Duration::from_secs(15);

#[derive(Debug)]
pub struct HostWsServer {
    listener: TcpListener,
    port: u16,
}

impl HostWsServer {
    pub async fn bind() -> io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        Ok(Self { listener, port })
    }

    #[must_use]
    pub fn port(&self) -> u16 {
        self.port
    }

    pub async fn serve(self, state: Arc<HubState>) {
        state.set_ws_port(self.port);
        state.logs().info(format!(
            "Host WebSocket server listening on 127.0.0.1:{}",
            self.port
        ));

        loop {
            match self.listener.accept().await {
                Ok((stream, _addr)) => {
                    let st = Arc::clone(&state);
                    tokio::spawn(async move {
                        handle_connection(stream, st).await;
                    });
                }
                Err(err) => {
                    state.logs().warn(format!("Host WS accept error: {err}"));
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
            }
        }
    }
}

async fn handle_connection(stream: TcpStream, state: Arc<HubState>) {
    let _ = stream.set_nodelay(true);
    let mut prefix = [0u8; 24];
    let peeked = match tokio::time::timeout(Duration::from_secs(3), stream.peek(&mut prefix)).await
    {
        Ok(Ok(n)) if n > 0 => &prefix[..n],
        _ => return,
    };
    if peeked.starts_with(b"GET /plugins/") || peeked.starts_with(b"HEAD /plugins/") {
        asset_server::handle_http_asset(stream, state).await;
        return;
    }

    let expected_auth = format!("Bearer {}", state.ws_token());

    let ws_stream = match accept_hdr_async(
        stream,
        #[allow(clippy::result_large_err)]
        |req: &Request, resp: Response| match validate_ws_request(req, &expected_auth) {
            Ok(()) => Ok(resp),
            Err((status, body)) => Err(reject_response(status, body)),
        },
    )
    .await
    {
        Ok(ws) => ws,
        Err(err) => {
            state
                .logs()
                .warn(format!("Rejected Host WebSocket handshake: {err}"));
            return;
        }
    };

    let (mut ws_sink, mut ws_source) = ws_stream.split();
    let (out_tx, mut out_rx) = outbound_queue::channel();
    let session_id = state.register_host_session(out_tx.clone());

    let writer_task = tokio::spawn(async move {
        let mut ping_ticker = tokio::time::interval(PING_INTERVAL);
        ping_ticker.set_missed_tick_behavior(MissedTickBehavior::Delay);
        // Consume immediate initial tick.
        ping_ticker.tick().await;

        loop {
            tokio::select! {
                biased;
                maybe_msg = out_rx.recv() => {
                    let Some(text) = maybe_msg else {
                        let _ = ws_sink.send(Message::Close(None)).await;
                        break;
                    };
                    if ws_sink.send(Message::Text(text.into())).await.is_err() {
                        break;
                    }
                }
                _ = ping_ticker.tick() => {
                    if ws_sink.send(Message::Ping(Vec::new().into())).await.is_err() {
                        break;
                    }
                }
            }
        }
    });

    while let Some(frame_res) = ws_source.next().await {
        match frame_res {
            Ok(Message::Text(text)) => {
                handle_incoming_text(&state, &out_tx, text.as_str());
            }
            Ok(Message::Close(_)) => break,
            Ok(Message::Ping(_) | Message::Pong(_) | Message::Binary(_) | Message::Frame(_)) => {}
            Err(err) => {
                state
                    .logs()
                    .warn(format!("Host WS session #{session_id} read error: {err}"));
                break;
            }
        }
    }

    out_tx.close();
    writer_task.abort();
    state.clear_host_session(session_id);
}

fn handle_incoming_text(state: &Arc<HubState>, out_tx: &outbound_queue::OutboundSender, raw: &str) {
    let Ok(val) = serde_json::from_str::<Value>(raw) else {
        return;
    };
    let Some(obj) = val.as_object() else {
        return;
    };

    if obj.contains_key("jsonrpc") && obj.contains_key("id") && obj.contains_key("method") {
        let st = Arc::clone(state);
        let tx = out_tx.clone();
        let raw_owned = raw.to_owned();
        tokio::spawn(async move {
            let resp = rpc::dispatch_rpc_text(&st, &raw_owned, RpcCaller::HostWebView).await;
            if let Ok(encoded) = serde_json::to_string(&resp) {
                let _ = tx.send_critical(encoded);
            }
        });
        return;
    }

    if obj.contains_key("jsonrpc") && obj.contains_key("id") && !obj.contains_key("method") {
        if let Ok(resp) = serde_json::from_value::<RpcResponse>(val) {
            let _ = state.resolve_host_rpc_response(resp);
        }
        return;
    }

    if obj.contains_key("event")
        && let Ok(env) = serde_json::from_value::<HostEventEnvelope>(val)
    {
        state.on_host_event(env);
    }
}

fn validate_ws_request(req: &Request, expected_auth: &str) -> Result<(), (u16, &'static str)> {
    if req.uri().path() != "/ws" {
        return Err((404, "Not Found"));
    }

    if let Some(origin) = req.headers().get("origin")
        && !origin.as_bytes().is_empty()
    {
        return Err((403, "Origin header is forbidden"));
    }

    let auth = req
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    if !constant_time_eq(auth.as_bytes(), expected_auth.as_bytes()) {
        return Err((401, "Unauthorized"));
    }

    Ok(())
}

fn reject_response(status: u16, body: &str) -> ErrorResponse {
    tokio_tungstenite::tungstenite::http::Response::builder()
        .status(status)
        .body(Some(body.to_owned()))
        .unwrap_or_else(|_| tokio_tungstenite::tungstenite::http::Response::new(None))
}

/// Constant-time byte slice equality to prevent timing leaks on Bearer tokens.
#[must_use]
pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    let max_len = a.len().max(b.len());
    let mut diff = a.len() ^ b.len();
    for i in 0..max_len {
        let ab = a.get(i).copied().unwrap_or(0);
        let bb = b.get(i).copied().unwrap_or(0);
        diff |= usize::from(ab ^ bb);
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constant_time_eq_compares_accurately() {
        assert!(constant_time_eq(b"secret-token-123", b"secret-token-123"));
        assert!(!constant_time_eq(b"secret-token-123", b"secret-token-124"));
        assert!(!constant_time_eq(b"short", b"longer"));
        assert!(constant_time_eq(b"", b""));
    }
}
