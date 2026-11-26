#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::net::UnixListener;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

fn make_temp_dir(label: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-ctl-{label}-{}-{nanos}",
        std::process::id()
    ));
    fs::create_dir_all(&dir).expect("create temp test dir");
    dir
}

#[test]
fn closed_stdout_pipe_exits_cleanly_without_panic() {
    let dir = make_temp_dir("broken-pipe");
    let sock_path = dir.join("hub.sock");
    let listener = UnixListener::bind(&sock_path).expect("bind mock hub.sock");

    let (ready_tx, ready_rx) = mpsc::channel::<()>();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept ctl connection");
        let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));

        let mut hello = String::new();
        reader.read_line(&mut hello).expect("read handshake");
        writeln!(stream, r#"{{"ok":true}}"#).expect("write handshake ack");
        stream.flush().expect("flush handshake ack");

        let mut req = String::new();
        reader.read_line(&mut req).expect("read rpc request");

        // Wait until the test has explicitly closed the child's stdout pipe.
        ready_rx.recv().expect("wait for stdout close");

        let payload = "x".repeat(128 * 1024);
        writeln!(
            stream,
            r#"{{"id":"ctl","result":{{"payload":"{payload}"}}}}"#
        )
        .expect("write rpc response");
        stream.flush().expect("flush rpc response");
    });

    let mut child = Command::new(env!("CARGO_BIN_EXE_sideveil-ctl"))
        .arg("--socket")
        .arg(&sock_path)
        .arg("status")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn sideveil-ctl");

    // Close the downstream stdout reader before sideveil-ctl writes its response.
    drop(child.stdout.take());
    ready_tx.send(()).expect("signal server to reply");

    let mut stderr = String::new();
    child
        .stderr
        .take()
        .expect("take stderr")
        .read_to_string(&mut stderr)
        .expect("read stderr");
    let status = child.wait().expect("wait for sideveil-ctl");
    server.join().expect("join mock hub thread");

    assert!(
        status.success(),
        "expected exit 0 on broken stdout pipe, got {status:?}; stderr={stderr:?}"
    );
    assert!(
        stderr.is_empty(),
        "expected silent exit without panic on broken pipe; stderr={stderr:?}"
    );

    let _ = fs::remove_dir_all(&dir);
}
