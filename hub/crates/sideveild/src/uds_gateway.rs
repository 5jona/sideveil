//! Dual-channel Unix Domain Socket gateway (`hub.sock` & `app.sock`) with `SO_PEERCRED` auth.

mod backend_session;
mod framing;

use std::io;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use sideveil_protocol::PROTOCOL_VERSION;
use sideveil_protocol::sensor::{HubToSensor, SensorToHub};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};
use tokio::io::{AsyncRead, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{Semaphore, mpsc};

use self::framing::{
    CTL_MAX_LINE_BYTES, DEFAULT_MAX_LINE_BYTES, LineFrameBuf, bind_socket, read_bounded_line,
    write_json_line, write_json_line_with_buf,
};
use crate::peercred::{PeerAuthConfig, SocketChannel, authorize_peer, read_peer_cred};
use crate::rpc::{self, RpcCaller};
use crate::state::HubState;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const HUB_MAX_CONCURRENT_CONNS: usize = 64;
const APP_MAX_CONCURRENT_CONNS: usize = 32;

/// Bound dual-channel UDS gateway serving privileged `hub.sock` and restricted `app.sock`.
#[derive(Debug)]
pub struct UdsGateway {
    hub_listener: UnixListener,
    app_listener: UnixListener,
    hub_path: PathBuf,
    app_path: PathBuf,
    auth: PeerAuthConfig,
}

impl UdsGateway {
    /// Binds `hub.sock` (`0660 0:1000`) and `app.sock` (`0666`) for peer connections.
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

    /// Runs the accept loops for both `hub.sock` and `app.sock` concurrently.
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
            accept_loop(
                self.hub_listener,
                SocketChannel::Hub,
                HUB_MAX_CONCURRENT_CONNS,
                hub_state,
                hub_auth,
            )
            .await;
        });

        let app_state = Arc::clone(&state);
        let app_auth = Arc::clone(&auth);
        let app_task = tokio::spawn(async move {
            accept_loop(
                self.app_listener,
                SocketChannel::App,
                APP_MAX_CONCURRENT_CONNS,
                app_state,
                app_auth,
            )
            .await;
        });

        let _ = tokio::join!(hub_task, app_task);
    }
}

async fn accept_loop(
    listener: UnixListener,
    channel: SocketChannel,
    max_conns: usize,
    state: Arc<HubState>,
    auth: Arc<PeerAuthConfig>,
) {
    let semaphore = Arc::new(Semaphore::new(max_conns));
    loop {
        match listener.accept().await {
            Ok((stream, _addr)) => {
                let Ok(permit) = Arc::clone(&semaphore).try_acquire_owned() else {
                    state.logs().warn(format!(
                        "Rejected UDS peer on {channel:?}: max concurrent connections ({max_conns}) reached"
                    ));
                    drop(stream);
                    continue;
                };
                let st = Arc::clone(&state);
                let au = Arc::clone(&auth);
                tokio::spawn(async move {
                    let _permit = permit;
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
    let mut line_buf = LineFrameBuf::with_capacity(512);

    let hello: UdsClientHello = {
        let Ok(Ok(Some(hello_line))) = tokio::time::timeout(
            HANDSHAKE_TIMEOUT,
            read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES),
        )
        .await
        else {
            return;
        };

        match serde_json::from_str(hello_line) {
            Ok(parsed) => parsed,
            Err(err) => {
                let _ = write_json_line(
                    &mut write_half,
                    &UdsHandshakeAck::rejected(format!("Invalid handshake JSON: {err}")),
                )
                .await;
                return;
            }
        }
    };

    if let Err(err) = authorize_peer(channel, cred, &hello, &auth) {
        let reason = err.to_string();
        state.logs().warn(format!(
            "Rejected UDS peer on {channel:?} (uid={}, pid={:?}): {reason}",
            cred.uid, cred.pid
        ));
        let _ = write_json_line(&mut write_half, &UdsHandshakeAck::rejected(reason)).await;
        return;
    }

    match &hello {
        UdsClientHello::Sensor {
            protocol_version, ..
        } => {
            if *protocol_version != PROTOCOL_VERSION {
                state.logs().warn(format!(
                    "Sensor protocol version mismatch: peer={protocol_version}, hub={PROTOCOL_VERSION}"
                ));
            }
            if let Err(reason) = state.verify_sensor_peer_pid(cred.pid) {
                state.logs().warn(format!(
                    "Rejected unexpected sensor peer (uid={}, pid={:?}): {reason}",
                    cred.uid, cred.pid
                ));
                let _ = write_json_line(&mut write_half, &UdsHandshakeAck::rejected(reason)).await;
                return;
            }
        }
        UdsClientHello::PluginBackend {
            plugin_id,
            package_name,
            protocol_version,
        } => {
            if *protocol_version != PROTOCOL_VERSION {
                state.logs().warn(format!(
                    "Plugin backend '{plugin_id}' protocol version mismatch: peer={protocol_version}, hub={PROTOCOL_VERSION}"
                ));
            }
            if let Err(err) = state.plugin_registry().authorize_backend(
                plugin_id,
                channel == SocketChannel::App,
                package_name.as_deref(),
            ) {
                let reason = err.to_string();
                state.logs().warn(format!(
                    "Rejected plugin backend '{plugin_id}' on {channel:?} (uid={}): {reason}",
                    cred.uid
                ));
                let _ = write_json_line(&mut write_half, &UdsHandshakeAck::rejected(reason)).await;
                return;
            }
            if state
                .plugin_backends()
                .is_owned_by_other_uid(plugin_id, cred.uid)
            {
                let reason =
                    format!("plugin backend '{plugin_id}' is already owned by another UID");
                state.logs().warn(format!(
                    "Blocked backend hijack for '{plugin_id}' from uid={}",
                    cred.uid
                ));
                let _ = write_json_line(&mut write_half, &UdsHandshakeAck::rejected(reason)).await;
                return;
            }
        }
        _ => {}
    }

    if write_json_line(&mut write_half, &UdsHandshakeAck::accepted())
        .await
        .is_err()
    {
        return;
    }

    match hello {
        UdsClientHello::Ctl => handle_ctl_session(reader, line_buf, write_half, state).await,
        UdsClientHello::Sensor { .. } => {
            handle_sensor_session(reader, line_buf, write_half, state).await;
        }
        UdsClientHello::PluginBackend {
            plugin_id,
            package_name,
            ..
        } => {
            backend_session::handle_plugin_backend_session(
                reader,
                line_buf,
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
    mut line_buf: LineFrameBuf,
    mut write_half: tokio::net::unix::OwnedWriteHalf,
    state: Arc<HubState>,
) {
    let mut write_buf = Vec::with_capacity(512);
    while let Ok(Some(line)) =
        read_bounded_line(&mut reader, &mut line_buf, CTL_MAX_LINE_BYTES).await
    {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let resp = rpc::dispatch_rpc_text(&state, trimmed, RpcCaller::Ctl).await;
        if write_json_line_with_buf(&mut write_half, &resp, &mut write_buf)
            .await
            .is_err()
        {
            break;
        }
    }
}

async fn handle_sensor_session<R: AsyncRead + Unpin>(
    mut reader: BufReader<R>,
    mut line_buf: LineFrameBuf,
    mut write_half: tokio::net::unix::OwnedWriteHalf,
    state: Arc<HubState>,
) {
    let (tx, mut rx) = mpsc::unbounded_channel::<HubToSensor>();
    let session_id = state.register_sensor_session(tx);

    let writer_task = tokio::spawn(async move {
        let mut write_buf = Vec::with_capacity(512);
        while let Some(msg) = rx.recv().await {
            if write_json_line_with_buf(&mut write_half, &msg, &mut write_buf)
                .await
                .is_err()
            {
                break;
            }
        }
    });

    while let Ok(Some(line)) =
        read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES).await
    {
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
