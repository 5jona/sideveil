//! Unit tests for the generic `PluginDaemonSupervisor` (approval, stderr logs, stop, circuit breaker).
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use sideveil_protocol::plugin::{PluginDaemonStatus, PluginGrantCapabilitiesParams};
use tokio::sync::watch;

use super::{PluginDaemonManager, run_daemon_supervisor_loop};
use crate::log_ring::LogRing;
use crate::plugin_registry::PluginRegistry;

fn temp_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-sup-{tag}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&dir).expect("mkdir");
    dir
}

fn write_daemon_plugin(plugins_dir: &std::path::Path, id: &str, script: &str, max_restarts: u32) {
    let pdir = plugins_dir.join(id);
    fs::create_dir_all(pdir.join("web")).expect("mkdir web");
    fs::create_dir_all(pdir.join("bin")).expect("mkdir bin");
    fs::write(pdir.join("web/index.html"), "<html></html>").expect("write html");
    let bin = pdir.join("bin/daemon.sh");
    fs::write(&bin, script).expect("write daemon script");
    fs::set_permissions(&bin, fs::Permissions::from_mode(0o755)).expect("chmod");
    let manifest = serde_json::json!({
        "schemaVersion": 1,
        "apiVersion": 1,
        "id": id,
        "name": id,
        "version": "1.0.0",
        "defaultEnabled": true,
        "entry": "web/index.html",
        "backend": "uds-native",
        "daemon": {
            "binary": "bin/daemon.sh",
            "restartPolicy": "on-failure",
            "maxRestarts": max_restarts,
            "backoffMs": 50
        }
    });
    fs::write(pdir.join("manifest.json"), manifest.to_string()).expect("write manifest");
}

async fn wait_status(
    manager: &PluginDaemonManager,
    id: &str,
    expected: PluginDaemonStatus,
) -> super::DaemonRuntimeInfo {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(4);
    loop {
        if let Some(info) = manager.status_of(id)
            && info.status == expected
        {
            return info;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for '{id}' to reach {expected:?}, last={:?}",
            manager.status_of(id)
        );
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

#[tokio::test]
async fn supervises_approval_stderr_capture_disable_stop_and_circuit_breaker() {
    let dir = temp_dir("lifecycle");
    let plugins_dir = dir.join("plugins");
    let logs = Arc::new(LogRing::new());

    // 1. Healthy long-running daemon that logs env vars and an oversized stderr line (>4 KiB) then sleeps.
    write_daemon_plugin(
        &plugins_dir,
        "com.example.good",
        "#!/bin/sh\necho \"started:$SIDEVEIL_PLUGIN_ID\" >&2\n/usr/bin/head -c 4096 /dev/zero | /usr/bin/tr '\\0' 'Z' >&2\necho '' >&2\nexec /bin/sleep 30\n",
        3,
    );
    // 2. Crash-looping daemon to trigger circuit breaker.
    write_daemon_plugin(
        &plugins_dir,
        "com.example.crash",
        "#!/bin/sh\necho \"fatal crash\" >&2\nexit 1\n",
        2,
    );

    let registry = PluginRegistry::open(plugins_dir, dir.join("plugins.json"), Arc::clone(&logs));
    let manager = PluginDaemonManager::new();
    let (shutdown_tx, shutdown_rx) = watch::channel(false);

    let sup_task = tokio::spawn(run_daemon_supervisor_loop(
        dir.join("module"),
        dir.clone(),
        dir.join("hub.sock"),
        Arc::clone(&registry),
        Arc::clone(&manager),
        logs,
        shutdown_rx,
    ));

    // Unapproved daemons must stay AwaitingApproval (never auto-spawned!).
    wait_status(
        &manager,
        "com.example.good",
        PluginDaemonStatus::AwaitingApproval,
    )
    .await;
    wait_status(
        &manager,
        "com.example.crash",
        PluginDaemonStatus::AwaitingApproval,
    )
    .await;

    // Approve com.example.good -> spawns, captures stderr, exposes PID.
    registry
        .grant_capabilities(&PluginGrantCapabilitiesParams {
            plugin_id: "com.example.good".to_owned(),
            capabilities: None,
            grant_all_pending: false,
            revoke_all: false,
            approve_daemon: Some(true),
        })
        .expect("approve good daemon");
    manager.notify_reconcile();

    let running = wait_status(&manager, "com.example.good", PluginDaemonStatus::Running).await;
    assert!(running.pid.is_some());

    // Wait briefly for stderr pump to capture both the startup line and the truncated >4 KiB line.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
    loop {
        let lines = manager.daemon_logs("com.example.good", 50);
        if lines.iter().any(|l| l.contains("started:com.example.good"))
            && lines.iter().any(|l| l.contains("...[truncated]"))
        {
            break;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "stderr not captured/truncated: {lines:?}"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }

    // Disabling plugin stops daemon cleanly.
    registry
        .set_enabled("com.example.good", false)
        .expect("disable good");
    manager.notify_reconcile();
    wait_status(&manager, "com.example.good", PluginDaemonStatus::Stopped).await;

    // Approving crash-looping daemon opens circuit breaker after maxRestarts=2.
    registry
        .grant_capabilities(&PluginGrantCapabilitiesParams {
            plugin_id: "com.example.crash".to_owned(),
            capabilities: None,
            grant_all_pending: false,
            revoke_all: false,
            approve_daemon: Some(true),
        })
        .expect("approve crash daemon");
    manager.notify_reconcile();

    let opened = wait_status(
        &manager,
        "com.example.crash",
        PluginDaemonStatus::CircuitOpen,
    )
    .await;
    assert_eq!(opened.restart_count, 2);
    assert!(
        opened
            .last_error
            .as_deref()
            .unwrap_or("")
            .contains("circuit breaker")
    );

    let _ = shutdown_tx.send(true);
    let _ = tokio::time::timeout(Duration::from_secs(3), sup_task).await;
    let _ = fs::remove_dir_all(&dir);
}
