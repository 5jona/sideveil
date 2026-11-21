//! Active UDS plugin backend registry and multiplexed JSON-RPC 2.0 invocation router.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError, RwLock};
use std::time::Duration;

use serde_json::Value;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, error_codes};
use tokio::sync::{mpsc, oneshot};

#[derive(Debug)]
struct BackendSession {
    session_id: u64,
    tx: mpsc::UnboundedSender<RpcRequest>,
    pending: Mutex<HashMap<String, oneshot::Sender<RpcResponse>>>,
}

#[derive(Debug)]
pub struct PluginBackends {
    next_id: AtomicU64,
    sessions: RwLock<HashMap<String, Arc<BackendSession>>>,
}

impl Default for PluginBackends {
    fn default() -> Self {
        Self::new()
    }
}

impl PluginBackends {
    #[must_use]
    pub fn new() -> Self {
        Self {
            next_id: AtomicU64::new(1),
            sessions: RwLock::new(HashMap::new()),
        }
    }

    /// Registers a connected plugin backend stream and returns `(session_id, outbound_rx)`.
    pub fn register(&self, plugin_id: &str) -> (u64, mpsc::UnboundedReceiver<RpcRequest>) {
        let session_id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::unbounded_channel();
        let session = Arc::new(BackendSession {
            session_id,
            tx,
            pending: Mutex::new(HashMap::new()),
        });
        let previous = {
            let mut guard = self
                .sessions
                .write()
                .unwrap_or_else(PoisonError::into_inner);
            guard.insert(plugin_id.to_owned(), session)
        };
        if let Some(old) = previous {
            fail_pending(&old, "Superseded by new backend connection");
        }
        (session_id, rx)
    }

    /// Unregisters a backend session only if `session_id` matches the active connection.
    pub fn unregister(&self, plugin_id: &str, session_id: u64) -> bool {
        let removed = {
            let mut guard = self
                .sessions
                .write()
                .unwrap_or_else(PoisonError::into_inner);
            if guard
                .get(plugin_id)
                .is_some_and(|s| s.session_id == session_id)
            {
                guard.remove(plugin_id)
            } else {
                None
            }
        };
        if let Some(session) = removed {
            fail_pending(&session, "Plugin backend disconnected");
            true
        } else {
            false
        }
    }

    /// Delivers a backend JSON-RPC response to its awaiting caller.
    pub fn complete_response(&self, plugin_id: &str, session_id: u64, response: RpcResponse) {
        let session = {
            let guard = self.sessions.read().unwrap_or_else(PoisonError::into_inner);
            guard
                .get(plugin_id)
                .filter(|s| s.session_id == session_id)
                .cloned()
        };
        if let Some(sess) = session {
            let waiter = {
                let mut pending = sess.pending.lock().unwrap_or_else(PoisonError::into_inner);
                pending.remove(&response.id)
            };
            if let Some(tx) = waiter {
                let _ = tx.send(response);
            }
        }
    }

    /// Returns the set of currently online plugin IDs.
    #[must_use]
    pub fn online_ids(&self) -> HashSet<String> {
        self.sessions
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .keys()
            .cloned()
            .collect()
    }

    /// Invokes a method on the plugin's active UDS backend with caller-bounded timeout protection.
    pub async fn invoke(
        &self,
        plugin_id: &str,
        method: String,
        params: Option<Value>,
        timeout: Duration,
    ) -> Result<Value, (i32, String)> {
        let session = {
            let guard = self.sessions.read().unwrap_or_else(PoisonError::into_inner);
            guard.get(plugin_id).cloned()
        };
        let Some(session) = session else {
            return Err((
                error_codes::BACKEND_OFFLINE,
                format!("Plugin backend '{plugin_id}' is offline"),
            ));
        };

        let req_id = format!("bk_{}", self.next_id.fetch_add(1, Ordering::Relaxed));
        let (resp_tx, resp_rx) = oneshot::channel();
        {
            let mut pending = session
                .pending
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            pending.insert(req_id.clone(), resp_tx);
        }

        let req = RpcRequest::new(req_id.clone(), method, params);
        if session.tx.send(req).is_err() {
            let mut pending = session
                .pending
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            pending.remove(&req_id);
            return Err((
                error_codes::BACKEND_OFFLINE,
                format!("Plugin backend '{plugin_id}' closed channel"),
            ));
        }

        match tokio::time::timeout(timeout, resp_rx).await {
            Ok(Ok(resp)) => {
                if let Some(err) = resp.error {
                    Err((err.code, err.message))
                } else {
                    Ok(resp.result.unwrap_or(Value::Null))
                }
            }
            Ok(Err(_)) => Err((
                error_codes::BACKEND_OFFLINE,
                format!("Plugin backend '{plugin_id}' disconnected before replying"),
            )),
            Err(_) => {
                let mut pending = session
                    .pending
                    .lock()
                    .unwrap_or_else(PoisonError::into_inner);
                pending.remove(&req_id);
                Err((
                    error_codes::BACKEND_TIMEOUT,
                    format!(
                        "Plugin backend '{plugin_id}' RPC timed out after {}ms",
                        timeout.as_millis()
                    ),
                ))
            }
        }
    }
}

fn fail_pending(session: &BackendSession, reason: &str) {
    let mut pending = session
        .pending
        .lock()
        .unwrap_or_else(PoisonError::into_inner);
    for (id, tx) in pending.drain() {
        let _ = tx.send(RpcResponse::err(id, error_codes::BACKEND_OFFLINE, reason));
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[tokio::test]
    async fn invoke_honors_custom_timeout_and_resolves_responses() {
        let backends = Arc::new(PluginBackends::new());
        let (session_id, mut rx) = backends.register("demo.backend");

        let b_clone = Arc::clone(&backends);
        let responder = tokio::spawn(async move {
            if let Some(req) = rx.recv().await {
                assert_eq!(req.method, "fast.ping");
                b_clone.complete_response(
                    "demo.backend",
                    session_id,
                    RpcResponse::ok(req.id, json!({ "pong": true })),
                );
            }
            // Second request intentionally left unanswered to verify custom timeout.
            let _slow = rx.recv().await;
            tokio::time::sleep(Duration::from_millis(200)).await;
        });

        let ok = backends
            .invoke(
                "demo.backend",
                "fast.ping".to_owned(),
                None,
                Duration::from_secs(2),
            )
            .await
            .expect("fast rpc succeeds");
        assert_eq!(ok["pong"], true);

        let timed_out = backends
            .invoke(
                "demo.backend",
                "slow.op".to_owned(),
                None,
                Duration::from_millis(40),
            )
            .await
            .expect_err("slow rpc should time out");
        assert_eq!(timed_out.0, error_codes::BACKEND_TIMEOUT);
        assert!(timed_out.1.contains("40ms"));

        responder.abort();
    }
}
