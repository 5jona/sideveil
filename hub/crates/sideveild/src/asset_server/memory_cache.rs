//! Bounded in-memory LRU cache for small static plugin assets (`<= 512 KiB`, `8 MiB` total).
//!
//! Validates every cache hit against the live filesystem `(mtime, len)` stamp so modified
//! plugin bundles invalidate immediately without serving stale bytes.

use std::collections::{HashMap, VecDeque};
use std::fs::Metadata;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::UNIX_EPOCH;

pub(crate) const MAX_CACHED_ASSET_BYTES: usize = 512 * 1024;
pub(crate) const MAX_CACHE_TOTAL_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct AssetStamp {
    pub(crate) mtime_secs: u64,
    pub(crate) mtime_nanos: u32,
    pub(crate) len: u64,
}

impl AssetStamp {
    /// Extracts `(mtime, len)` from file metadata, returning `None` if `mtime` is unavailable.
    pub(crate) fn from_metadata(meta: &Metadata) -> Option<Self> {
        let modified = meta.modified().ok()?;
        let duration = modified.duration_since(UNIX_EPOCH).ok()?;
        Some(Self {
            mtime_secs: duration.as_secs(),
            mtime_nanos: duration.subsec_nanos(),
            len: meta.len(),
        })
    }
}

#[derive(Debug, Clone)]
struct CachedAsset {
    stamp: AssetStamp,
    bytes: Arc<Vec<u8>>,
    seq: u64,
}

#[derive(Debug)]
struct AssetCacheInner {
    entries: HashMap<PathBuf, CachedAsset>,
    order: VecDeque<(PathBuf, u64)>,
    total_bytes: usize,
    next_seq: u64,
}

impl AssetCacheInner {
    fn new() -> Self {
        Self {
            entries: HashMap::new(),
            order: VecDeque::new(),
            total_bytes: 0,
            next_seq: 1,
        }
    }

    fn alloc_seq(&mut self) -> u64 {
        let seq = self.next_seq;
        self.next_seq = self.next_seq.wrapping_add(1);
        seq
    }

    fn compact_order_if_needed(&mut self) {
        let threshold = self.entries.len().saturating_mul(4).max(64);
        if self.order.len() > threshold {
            self.order
                .retain(|(path, seq)| self.entries.get(path).is_some_and(|e| e.seq == *seq));
        }
    }
}

/// Thread-safe bounded LRU cache storing `Arc<Vec<u8>>` payloads keyed by canonical file path.
#[derive(Debug)]
pub(crate) struct AssetMemoryCache {
    max_entry_bytes: usize,
    max_total_bytes: usize,
    inner: Mutex<AssetCacheInner>,
}

impl Default for AssetMemoryCache {
    fn default() -> Self {
        Self::new()
    }
}

impl AssetMemoryCache {
    #[must_use]
    pub(crate) fn new() -> Self {
        Self::with_limits(MAX_CACHED_ASSET_BYTES, MAX_CACHE_TOTAL_BYTES)
    }

    #[must_use]
    pub(crate) fn with_limits(max_entry_bytes: usize, max_total_bytes: usize) -> Self {
        Self {
            max_entry_bytes,
            max_total_bytes,
            inner: Mutex::new(AssetCacheInner::new()),
        }
    }

    /// Returns cached bytes iff `stamp` matches `(mtime, len)`; stale entries are evicted.
    #[must_use]
    pub(crate) fn get(&self, path: &Path, stamp: AssetStamp) -> Option<Arc<Vec<u8>>> {
        let mut inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        let next_seq = inner.alloc_seq();
        let entry = inner.entries.get_mut(path)?;
        if entry.stamp != stamp {
            let removed = inner.entries.remove(path);
            if let Some(stale) = removed {
                inner.total_bytes = inner.total_bytes.saturating_sub(stale.bytes.len());
            }
            return None;
        }
        entry.seq = next_seq;
        let bytes = Arc::clone(&entry.bytes);
        inner.order.push_back((path.to_path_buf(), next_seq));
        inner.compact_order_if_needed();
        Some(bytes)
    }

    /// Inserts `bytes` for `path` if within `max_entry_bytes`, evicting LRU entries as needed.
    pub(crate) fn insert(&self, path: PathBuf, stamp: AssetStamp, bytes: Arc<Vec<u8>>) {
        let len = bytes.len();
        if len > self.max_entry_bytes || len > self.max_total_bytes {
            return;
        }
        let mut inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(prev) = inner.entries.remove(&path) {
            inner.total_bytes = inner.total_bytes.saturating_sub(prev.bytes.len());
        }
        while inner.total_bytes.saturating_add(len) > self.max_total_bytes {
            let Some((evict_path, evict_seq)) = inner.order.pop_front() else {
                break;
            };
            if inner
                .entries
                .get(&evict_path)
                .is_some_and(|e| e.seq == evict_seq)
                && let Some(removed) = inner.entries.remove(&evict_path)
            {
                inner.total_bytes = inner.total_bytes.saturating_sub(removed.bytes.len());
            }
        }
        let seq = inner.alloc_seq();
        inner.total_bytes = inner.total_bytes.saturating_add(len);
        inner.order.push_back((path.clone(), seq));
        inner
            .entries
            .insert(path, CachedAsset { stamp, bytes, seq });
        inner.compact_order_if_needed();
    }

    #[cfg(test)]
    pub(crate) fn stats(&self) -> (usize, usize) {
        let inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        (inner.entries.len(), inner.total_bytes)
    }

    #[cfg(test)]
    pub(crate) fn contains(&self, path: &Path) -> bool {
        let inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        inner.entries.contains_key(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stamp(secs: u64, len: u64) -> AssetStamp {
        AssetStamp {
            mtime_secs: secs,
            mtime_nanos: 0,
            len,
        }
    }

    #[test]
    fn skips_entries_above_single_file_limit() {
        let cache = AssetMemoryCache::with_limits(8, 64);
        let p = PathBuf::from("/tmp/big.bin");
        cache.insert(p.clone(), stamp(1, 9), Arc::new(vec![1u8; 9]));
        assert_eq!(cache.stats(), (0, 0));
        assert!(!cache.contains(&p));
    }

    #[test]
    fn evicts_least_recently_used_when_total_capacity_exceeded() {
        let cache = AssetMemoryCache::with_limits(16, 10);
        let p1 = PathBuf::from("/tmp/a.js");
        let p2 = PathBuf::from("/tmp/b.js");
        let p3 = PathBuf::from("/tmp/c.js");

        cache.insert(p1.clone(), stamp(1, 4), Arc::new(vec![1u8; 4]));
        cache.insert(p2.clone(), stamp(1, 4), Arc::new(vec![2u8; 4]));
        assert_eq!(cache.stats(), (2, 8));

        // Touch p1 so p2 becomes least recently used.
        assert!(cache.get(&p1, stamp(1, 4)).is_some());

        // Inserting 4 bytes exceeds 10-byte cap -> p2 must be evicted, p1 retained.
        cache.insert(p3.clone(), stamp(1, 4), Arc::new(vec![3u8; 4]));
        assert_eq!(cache.stats(), (2, 8));
        assert!(cache.contains(&p1));
        assert!(!cache.contains(&p2));
        assert!(cache.contains(&p3));
    }

    #[test]
    fn invalidates_stale_entry_on_stamp_mismatch() {
        let cache = AssetMemoryCache::with_limits(64, 128);
        let p = PathBuf::from("/tmp/index.html");
        cache.insert(p.clone(), stamp(10, 5), Arc::new(b"hello".to_vec()));
        assert_eq!(cache.stats(), (1, 5));

        assert!(cache.get(&p, stamp(11, 5)).is_none());
        assert_eq!(cache.stats(), (0, 0));
    }
}
