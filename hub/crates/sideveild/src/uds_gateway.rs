//! Dual-channel Unix Domain Socket gateway (`hub.sock` & `app.sock`) with `SO_PEERCRED` auth.

mod backend_session;
mod framing;

use std::io;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use sideveil_protocol::sensor::{HubToSensor, SensorToHub};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};
use tokio::io::{AsyncRead, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::mpsc;

use self::framing::{bind_socket, read_bounded_line, write_json_line};
use crate::peercred::{PeerAuthConfig, SocketChannel, authorize_peer, read_peer_cred};
use crate::rpc::{self, RpcCaller};
use crate::state::HubState;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug)]
pub struct UdsGateway {
    hub_listener: UnixListener,
    app_listener: UnixListener,
    hub_path: PathBuf,
    app_path: PathBuf,
    auth: PeerAuthConfig,
}

impl UdsGateway {
    pub fn bind(hub_path: PathBuf, app_path: PathBuf, auth: PeerAuthConfig) -> io::Result<Self> {
        let hub_listener = bind_socket(&hub_path, 0o660, Some((0, 1000)))?;
        let app_listener = bind_socket(&app_path, 0o666, None)?;
        Ok(Self {
            hub_listener,
            app_listener,
            hub_path,
            app_path,
            auth,
        })
    }

    pub async fn serve(self, state: Arc<HubState>) {
        state.logs().info(format!(
            "UDS gateway listening on {} and {}",
            self.hub_path.display(),
            self.app_path.display()
        ));
        let auth = Arc::new(self.auth);
        let hub_state = Arc::clone(&state);
        let hub_auth = Arc::clone(&auth);
        let hub_task = tokio::spawn(async move {
            accept_loop(self.hub_listener, SocketChannel::Hub, hub_state, hub_auth).await;
        });

        let app_state = Arc::clone(&state);
        let app_auth = Arc::clone(&auth);
        let app_task = tokio::spawn(async move {
            accept_loop(self.app_listener, SocketChannel::App, app_state, app_auth).await;
        });

        let _ = tokio::join!(hub_task, app_task);
    }
}

async fn accept_loop(
    listener: UnixListener,
    channel: SocketChannel,
    state: Arc<HubState>,
    auth: Arc<PeerAuthConfig>,
) {
    loop {
        match listener.accept().await {
            Ok((stream, _addr)) => {
                let st = Arc::clone(&state);
                let au = Arc::clone(&auth);
                tokio::spawn(async move {
                    handle_peer(stream, channel, st, au).await;
                });
            }
            Err(err) => {
                state
                    .logs()
                    .warn(format!("UDS {channel:?} accept error: {err}"));
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
}

async fn handle_peer(
    stream: UnixStream,
    channel: SocketChannel,
    state: Arc<HubState>,
    auth: Arc<PeerAuthConfig>,
) {
    let cred = match read_peer_cred(&stream) {
        Ok(c) => c,
        Err(err) => {
            state
                .logs()
                .warn(format!("Failed reading SO_PEERCRED on {channel:?}: {err}"));
            return;
        }
    };

    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let Ok(Ok(Some(hello_line))) =
        tokio::time::timeout(HANDSHAKE_TIMEOUT, read_bounded_line(&mut reader)).await
    else {
        return;
    };

    let hello: UdsClientHello = match serde_json::from_str(&hello_line) {
        Ok(parsed) => parsed,
        Err(err) => {
            let _ = write_json_line(
                &mut write_half,
                &UdsHandshakeAck::rejected(format!("Invalid handshake JSON: {err}")),
            )
            .await;
            return;
        }
    };

    if let Err(reason) = authorize_peer(channel, cred, &hello, &auth) {
        state.logs().warn(format!(
            "Rejected UDS peer on {channel:?} (uid={}, pid={:?}): {reason}",
            cred.uid, cred.pid
        ));
        let _ = write_json_line(&mut write_half, &UdsHandshakeAck::rejected(reason)).await;
        return;
    }

    if let UdsClientHello::PluginBackend {
        ref plugin_id,
        ref package_name,
    } = hello
        && let Err(reason) = state.plugin_registry().authorize_backend(
            plugin_id,
            channel == SocketChannel::App,
            package_name.as_deref(),
        )
    {
        state.logs().warn(format!(
            "Rejected plugin backend '{plugin_id}' on {channel:?} (uid={}): {reason}",
            cred.uid
        ));
        let _ = write_json_line(&mut write_half, &UdsHandshakeAck::rejected(reason)).await;
        return;
    }

    if write_json_line(&mut write_half, &UdsHandshakeAck::accepted())
        .await
        .is_err()
    {
        return;
    }

    match hello {
        UdsClientHello::Ctl => handle_ctl_session(reader, write_half, state).await,
        UdsClientHello::Sensor { .. } => {
            handle_sensor_session(reader, write_half, state).await;
        }
        UdsClientHello::PluginBackend {
            plugin_id,
            package_name,
        } => {
            backend_session::handle_plugin_backend_session(
                reader,
                write_half,
                cred,
                plugin_id,
                package_name,
                channel == SocketChannel::Hub,
                state,
            )
            .await;
        }
    }
}

async fn handle_ctl_session<R: AsyncRead + Unpin>(
    mut reader: BufReader<R>,
    mut write_half: tokio::net::unix::OwnedWriteHalf,
    state: Arc<HubState>,
) {
    while let Ok(Some(line)) = read_bounded_line(&mut reader).await {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let resp = rpc::dispatch_rpc_text(&state, trimmed, RpcCaller::Ctl).await;
        if write_json_line(&mut write_half, &resp).await.is_err() {
            break;
        }
    }
}

async fn handle_sensor_session<R: AsyncRead + Unpin>(
    mut reader: BufReader<R>,
    mut write_half: tokio::net::unix::OwnedWriteHalf,
    state: Arc<HubState>,
) {
    let (tx, mut rx) = mpsc::unbounded_channel::<HubToSensor>();
    let session_id = state.register_sensor_session(tx);

    let writer_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if write_json_line(&mut write_half, &msg).await.is_err() {
                break;
            }
        }
    });

    while let Ok(Some(line)) = read_bounded_line(&mut reader).await {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        match serde_json::from_str::<SensorToHub>(trimmed) {
            Ok(msg) => state.on_sensor_message(msg),
            Err(err) => {
                state
                    .logs()
                    .warn(format!("Malformed sensor NDJSON frame: {err}"));
            }
        }
    }

    writer_task.abort();
    state.clear_sensor_session(session_id);
}
