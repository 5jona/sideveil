//! Dual-priority outbound message queue separating reliable control/RPC frames from
//! high-frequency coalescing ephemeral frames (such as `gesture.dragMove`).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use tokio::sync::{Notify, mpsc};

/// Sender half of the dual-priority host outbound channel.
#[derive(Debug, Clone)]
pub struct OutboundSender {
    critical_tx: mpsc::UnboundedSender<String>,
    ephemeral: Arc<EphemeralSlot>,
}

/// Receiver half draining critical frames ahead of coalesced ephemeral frames.
#[derive(Debug)]
pub struct OutboundReceiver {
    critical_rx: mpsc::UnboundedReceiver<String>,
    ephemeral: Arc<EphemeralSlot>,
}

#[derive(Debug, Default)]
struct EphemeralSlot {
    latest: Mutex<Option<String>>,
    closed: AtomicBool,
    notify: Notify,
}

impl Drop for OutboundSender {
    fn drop(&mut self) {
        // Only mark closed when this was the last strong reference aside from the receiver.
        if Arc::strong_count(&self.ephemeral) <= 2 {
            self.ephemeral.closed.store(true, Ordering::Release);
            self.ephemeral.notify.notify_waiters();
        }
    }
}

/// Creates a paired dual-priority outbound channel.
#[must_use]
pub fn channel() -> (OutboundSender, OutboundReceiver) {
    let (critical_tx, critical_rx) = mpsc::unbounded_channel();
    let ephemeral = Arc::new(EphemeralSlot::default());
    (
        OutboundSender {
            critical_tx,
            ephemeral: Arc::clone(&ephemeral),
        },
        OutboundReceiver {
            critical_rx,
            ephemeral,
        },
    )
}

impl OutboundSender {
    /// Enqueues a critical control or JSON-RPC response frame that must never be dropped.
    pub fn send_critical(&self, payload: String) -> bool {
        self.critical_tx.send(payload).is_ok()
    }

    /// Stores the latest high-frequency ephemeral frame, overwriting any unconsumed older frame.
    pub fn send_ephemeral(&self, payload: String) -> bool {
        if self.critical_tx.is_closed() || self.ephemeral.closed.load(Ordering::Acquire) {
            return false;
        }
        let mut slot = self
            .ephemeral
            .latest
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        *slot = Some(payload);
        drop(slot);
        self.ephemeral.notify.notify_one();
        true
    }

    /// Explicitly marks the channel closed so the receiver finishes after draining critical frames.
    pub fn close(&self) {
        self.ephemeral.closed.store(true, Ordering::Release);
        self.ephemeral.notify.notify_waiters();
    }
}

impl OutboundReceiver {
    /// Receives the next frame with strict priority for critical control/RPC frames.
    pub async fn recv(&mut self) -> Option<String> {
        loop {
            // Register waiter before inspecting state so `close()`'s `notify_waiters()`
            // can never race between the closed check and parking in `select!`.
            let notified = self.ephemeral.notify.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();

            if let Ok(critical) = self.critical_rx.try_recv() {
                return Some(critical);
            }
            if let Some(ephemeral) = self.take_ephemeral() {
                return Some(ephemeral);
            }
            if self.ephemeral.closed.load(Ordering::Acquire) {
                return self.critical_rx.try_recv().ok();
            }

            tokio::select! {
                biased;
                maybe_critical = self.critical_rx.recv() => {
                    match maybe_critical {
                        Some(frame) => return Some(frame),
                        None => return self.take_ephemeral(),
                    }
                }
                () = notified => {
                    // Loop back to re-check critical lane first before taking ephemeral.
                }
            }
        }
    }

    fn take_ephemeral(&self) -> Option<String> {
        let mut slot = self
            .ephemeral
            .latest
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        slot.take()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn critical_frames_are_never_dropped_under_ephemeral_flood() {
        let (tx, mut rx) = channel();

        assert!(tx.send_critical("dragStart".to_owned()));
        for i in 0..10_000 {
            assert!(tx.send_ephemeral(format!("dragMove-{i}")));
        }
        assert!(tx.send_critical("rpcResponse".to_owned()));
        assert!(tx.send_critical("dragEnd".to_owned()));
        drop(tx);

        let mut received = Vec::new();
        while let Some(msg) = rx.recv().await {
            received.push(msg);
        }

        // All 3 critical frames arrive first in FIFO order, followed by the coalesced latest move.
        assert_eq!(
            received,
            vec!["dragStart", "rpcResponse", "dragEnd", "dragMove-9999"]
        );
    }

    #[tokio::test]
    async fn close_wakes_receiver_reliably_while_sender_clone_remains_alive() {
        for _ in 0..100 {
            let (tx, mut rx) = channel();
            let tx_clone = tx.clone();
            let recv_task = tokio::spawn(async move { rx.recv().await });
            tokio::task::yield_now().await;
            tx.close();
            let res = tokio::time::timeout(std::time::Duration::from_secs(1), recv_task)
                .await
                .expect("receiver must wake on close without lost wakeup")
                .expect("join recv_task");
            assert_eq!(res, None);
            assert!(!tx_clone.send_ephemeral("dropped-after-close".to_owned()));
        }
    }
}
