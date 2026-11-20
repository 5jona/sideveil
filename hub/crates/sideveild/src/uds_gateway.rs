//! Dual-channel Unix Domain Socket gateway (`hub.sock` & `app.sock`) with `SO_PEERCRED` auth.

use std::ffi::CString;
use std::fs;
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use serde_json::Value;
use sideveil_protocol::plugin::PluginBackendState;
use sideveil_protocol::rpc::{HostEventEnvelope, RpcResponse};
use sideveil_protocol::sensor::{HubToSensor, SensorToHub};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::mpsc;

use crate::peercred::{PeerAuthConfig, PeerCred, SocketChannel, authorize_peer, read_peer_cred};
use crate::rpc::{self, RpcCaller};
use crate::state::HubState;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_LINE_BYTES: usize = 256 * 1024;

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
            handle_plugin_backend_session(reader, write_half, cred, plugin_id, package_name, state)
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

async fn handle_plugin_backend_session<R: AsyncRead + Unpin>(
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
            && let Ok(env) = serde_json::from_value::<HostEventEnvelope>(val)
        {
            state.emit_plugin_scoped_event(&plugin_id, env.event, env.payload);
        }
    }

    writer_task.abort();
    if state.plugin_backends().unregister(&plugin_id, session_id) {
        state
            .logs()
            .info(format!("Plugin backend disconnected: id={plugin_id}"));
        state.emit_plugin_backend_state(&plugin_id, PluginBackendState::Offline);
    }
}

async fn read_bounded_line<R: AsyncRead + Unpin>(
    reader: &mut BufReader<R>,
) -> io::Result<Option<String>> {
    let mut buf = Vec::with_capacity(256);
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            if buf.is_empty() {
                return Ok(None);
            }
            break;
        }
        if let Some(pos) = available.iter().position(|&b| b == b'\n') {
            if buf.len() + pos > MAX_LINE_BYTES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "NDJSON line exceeds maximum frame size",
                ));
            }
            buf.extend_from_slice(&available[..pos]);
            reader.consume(pos + 1);
            break;
        }
        if buf.len() + available.len() > MAX_LINE_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "NDJSON line exceeds maximum frame size",
            ));
        }
        let len = available.len();
        buf.extend_from_slice(available);
        reader.consume(len);
    }
    String::from_utf8(buf)
        .map(Some)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))
}

async fn write_json_line<T: serde::Serialize>(
    writer: &mut tokio::net::unix::OwnedWriteHalf,
    value: &T,
) -> io::Result<()> {
    let mut bytes =
        serde_json::to_vec(value).map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
    bytes.push(b'\n');
    writer.write_all(&bytes).await?;
    writer.flush().await
}

fn bind_socket(path: &Path, mode: u32, owner: Option<(u32, u32)>) -> io::Result<UnixListener> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    let listener = UnixListener::bind(path)?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    if let Some((uid, gid)) = owner {
        let _ = try_chown(path, uid, gid);
    }
    Ok(listener)
}

fn try_chown(path: &Path, uid: u32, gid: u32) -> io::Result<()> {
    let c_path = CString::new(path.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path contains interior NUL"))?;
    let rc = unsafe {
        // SAFETY: `c_path` is a valid NUL-terminated C string.
        libc::chown(c_path.as_ptr(), uid, gid)
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}
