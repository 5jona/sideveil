//! Active UDS handshake and split reader/writer session runner for `sideveil-plugin-sdk`.

use std::sync::Arc;

use futures_util::StreamExt;
use sideveil_protocol::plugin::PluginBackendEvent;
use sideveil_protocol::rpc::RpcResponse;
use sideveil_protocol::uds::{BackendOutboundFrame, UdsClientHello, UdsHandshakeAck};
use tokio::net::UnixStream;
use tokio::sync::{mpsc, oneshot};
use tokio_util::codec::{Framed, LinesCodec};
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;

use crate::client::{PluginRpcContext, PluginRpcHandler};
use crate::codec::{
    decode_json_line, map_codec_error, ndjson_framed, recv_json_frame, send_json_frame,
};
use crate::config::PluginBackendConfig;
use crate::error::SdkError;
use crate::handle::PluginBackendHandle;

#[derive(Debug)]
pub(crate) struct OutboundReceivers {
    pub(crate) reliable_rx: mpsc::Receiver<PluginBackendEvent>,
    pub(crate) ephemeral_rx: mpsc::Receiver<PluginBackendEvent>,
}

pub(crate) async fn connect_and_handshake(
    config: &PluginBackendConfig,
    cancel: &CancellationToken,
) -> Result<Framed<UnixStream, LinesCodec>, SdkError> {
    let stream = tokio::select! {
        biased;
        () = cancel.cancelled() => return Err(SdkError::Cancelled),
        res = tokio::time::timeout(config.connect_timeout, UnixStream::connect(&config.socket_path)) => {
            match res {
                Ok(Ok(stream)) => stream,
                Ok(Err(err)) => return Err(SdkError::Io(err)),
                Err(_) => {
                    return Err(SdkError::ConnectTimeout {
                        path: config.socket_path.clone(),
                        timeout: config.connect_timeout,
                    });
                }
            }
        }
    };

    let mut framed = ndjson_framed(stream, config.max_frame_bytes);
    let hello = UdsClientHello::PluginBackend {
        plugin_id: config.plugin_id.clone(),
        package_name: config.package_name.clone(),
    };

    let handshake = async {
        send_json_frame(&mut framed, &hello, config.max_frame_bytes).await?;
        let ack: UdsHandshakeAck = recv_json_frame(&mut framed, config.max_frame_bytes)
            .await?
            .ok_or(SdkError::ConnectionClosed)?;
        if !ack.ok {
            return Err(SdkError::HandshakeRejected(
                ack.error.unwrap_or_else(|| "handshake rejected".to_owned()),
            ));
        }
        Ok(framed)
    };

    tokio::select! {
        biased;
        () = cancel.cancelled() => Err(SdkError::Cancelled),
        res = tokio::time::timeout(config.handshake_timeout, handshake) => {
            match res {
                Ok(inner) => inner,
                Err(_) => Err(SdkError::HandshakeTimeout {
                    timeout: config.handshake_timeout,
                }),
            }
        }
    }
}

pub(crate) async fn run_active_session<H: PluginRpcHandler>(
    config: &PluginBackendConfig,
    plugin_id: &Arc<str>,
    handle: &PluginBackendHandle,
    cancel: &CancellationToken,
    framed: Framed<UnixStream, LinesCodec>,
    receivers: OutboundReceivers,
    handler: &Arc<H>,
) -> Option<OutboundReceivers> {
    let session_cancel = cancel.child_token();
    let tracker = TaskTracker::new();
    let (mut sink, mut stream) = framed.split();
    let (rpc_reply_tx, mut rpc_reply_rx) =
        mpsc::channel::<RpcResponse>(config.reliable_queue_capacity);

    let max_frame_bytes = config.max_frame_bytes;
    let (rx_return_tx, rx_return_rx) = oneshot::channel();

    handle.set_connected(true);
    let base_ctx = PluginRpcContext::new(
        Arc::clone(plugin_id),
        handle.clone(),
        session_cancel.clone(),
    );

    let on_conn_handler = Arc::clone(handler);
    let on_conn_ctx = base_ctx.clone();
    tracker.spawn(async move {
        on_conn_handler.on_connected(on_conn_ctx).await;
    });

    let writer_cancel = session_cancel.clone();
    tracker.spawn(async move {
        let mut reliable_rx = receivers.reliable_rx;
        let mut ephemeral_rx = receivers.ephemeral_rx;
        loop {
            tokio::select! {
                biased;
                () = writer_cancel.cancelled() => break,
                Some(resp) = rpc_reply_rx.recv() => {
                    if send_json_frame(&mut sink, &resp, max_frame_bytes).await.is_err() {
                        writer_cancel.cancel();
                        break;
                    }
                }
                Some(ev) = reliable_rx.recv() => {
                    if send_json_frame(&mut sink, &ev, max_frame_bytes).await.is_err() {
                        writer_cancel.cancel();
                        break;
                    }
                }
                Some(ev) = ephemeral_rx.recv() => {
                    if send_json_frame(&mut sink, &ev, max_frame_bytes).await.is_err() {
                        writer_cancel.cancel();
                        break;
                    }
                }
                else => break,
            }
        }
        let _ = rx_return_tx.send(OutboundReceivers {
            reliable_rx,
            ephemeral_rx,
        });
    });

    let reader_cancel = session_cancel.clone();
    let reader_tracker = tracker.clone();
    let reader_handler = Arc::clone(handler);
    tracker.spawn(async move {
        loop {
            let next_line = tokio::select! {
                biased;
                () = reader_cancel.cancelled() => break,
                item = stream.next() => item,
            };
            let Some(item) = next_line else {
                break;
            };
            let Ok(line) = item.map_err(|err| map_codec_error(err, max_frame_bytes)) else {
                break;
            };
            if line.trim().is_empty() {
                continue;
            }
            let Ok(frame) = decode_json_line::<BackendOutboundFrame>(&line) else {
                continue;
            };
            dispatch_frame(
                frame,
                &reader_tracker,
                &reader_handler,
                &base_ctx,
                &rpc_reply_tx,
            );
        }
        reader_cancel.cancel();
    });

    session_cancel.cancelled().await;
    tracker.close();
    tracker.wait().await;
    handle.set_connected(false);

    let mut returned = rx_return_rx.await.ok();
    if let Some(ref mut rx) = returned {
        while rx.ephemeral_rx.try_recv().is_ok() {}
    }
    handler.on_disconnected(config.plugin_id.clone()).await;
    returned
}

fn dispatch_frame<H: PluginRpcHandler>(
    frame: BackendOutboundFrame,
    tracker: &TaskTracker,
    handler: &Arc<H>,
    ctx: &PluginRpcContext,
    rpc_reply_tx: &mpsc::Sender<RpcResponse>,
) {
    match frame {
        BackendOutboundFrame::Rpc(req) => {
            let handler = Arc::clone(handler);
            let ctx = ctx.clone();
            let reply_tx = rpc_reply_tx.clone();
            tracker.spawn(async move {
                let req_id = req.id.clone();
                let resp = match handler.handle_rpc(req, ctx.clone()).await {
                    Ok(value) => RpcResponse::ok(req_id, value),
                    Err(err) => err.into_rpc_response(req_id),
                };
                tokio::select! {
                    biased;
                    () = ctx.cancel_token().cancelled() => {}
                    _ = reply_tx.send(resp) => {}
                }
            });
        }
        BackendOutboundFrame::SystemEvent { event, payload } => {
            let handler = Arc::clone(handler);
            let ctx = ctx.clone();
            tracker.spawn(async move {
                handler.on_system_event(event, payload, ctx).await;
            });
        }
    }
}
