//! `core_memory.rs`: the memory store, as an envelope a model can read.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!   2026-08-11  A. Sigdel  Named what is here rather than what used to be.
//!                          #565 took the C ABI and #581 took the prefix; the
//!                          list below outlived both, and one of the four it
//!                          named had already become a `Drop`.
//!
//! Contents
//!   `Memory`    A store, and the lock that makes one shareable.
//!   `open`      Bound it, then open it.
//!   `remember`  Put a turn in.
//!   `recall`    Ask it something.
//!
//! There is no release. The store closes when the `Memory` drops, which is what
//! `jni_memory` does with the box it holds; the entry point that used to be
//! named here was the C ABI's way of saying the same thing.
//!
//! Opening is where the horizon runs. `ZeroMem::open` loads and indexes every
//! turn, so bounding afterwards is bounding it after paying for it, so
//! `memory::apply` goes first, in the same call, so no caller can get that wrong.
//!
//! A handle rather than a path per call, unlike the git half: libgit2 is cheap to
//! open and a repository changes underneath, where a `ZeroMem` is the index and
//! rebuilding it per question is the cost this milestone exists to avoid. Behind
//! a mutex, because `ingest_turn` takes `&mut self`.

use crate::answer::{refused, rendered};
use crate::memory;
use std::path::Path;
use std::sync::Mutex;

/// A memory store.
pub struct Memory {
    /// The store, behind the lock that makes one handle shareable.
    inner: Mutex<zeromem::ZeroMem>,
}

/// Why a call could not be served. The horizon's own failures never reach here:
/// they happen at open, where null is the whole answer.
#[derive(Debug, thiserror::Error)]
enum Failed {
    /// The store refused.
    #[error("memory: {0}")]
    Store(String),
    /// A panic left the lock broken.
    #[error("the memory store is unusable after an earlier failure")]
    Poisoned,
}

/// Bound the store, then open it.
///
/// # Arguments
/// * `path`: the database, WHERE it need not exist yet.
/// * `keep`: how many recent turns to leave in front of the horizon.
///
/// # Returns
/// [`None`] IF the horizon failed or the store would not open. A path that could
/// not be read is no longer one of the cases: this takes a `&Path`.
///
/// Absence rather than an envelope, which is the shape the pointer version had
/// for the same reason: with no store there is nothing to hold and nothing to
/// hang a message on.
pub(crate) fn open(path: &Path, keep: usize) -> Option<Memory> {
    // Before open, not after: opening is what loads everything.
    memory::apply(path, keep).ok()?;

    // The hash embedder, always: the ONNX one cannot come to a phone, and a
    // store written by one is cleared when opened by the other.
    let store = zeromem::ZeroMem::open(
        path,
        zeromem::config::Config::default(),
        Box::new(zeromem::embed::HashEmbedder::default()),
    )
    .ok()?;

    Some(Memory {
        inner: Mutex::new(store),
    })
}

impl Memory {
    /// Put a turn in.
    ///
    /// # Returns
    /// The envelope, carrying `ok` with the turn's id or `error`.
    ///
    /// # Atomic
    /// Serialised on the store's lock. A second caller waits.
    pub(crate) fn remember(&self, session: &str, speaker: &str, text: &str, ts: i64) -> String {
        // Refused rather than stored: zeromem indexes nothing, so it becomes a
        // turn that cannot be recalled and counts against the horizon anyway.
        if text.trim().is_empty() {
            return refused("a turn with no text cannot be remembered");
        }

        rendered(self.with(|store| {
            store
                .ingest_turn(session, speaker, text, ts)
                .map_err(|why| Failed::Store(why.to_string()))
        }))
    }

    /// Ask the store something.
    ///
    /// # Arguments
    /// * `top_k`: how many pieces of evidence to return, WHERE `0` takes
    ///   zeromem's own default rather than returning nothing.
    ///
    /// # Returns
    /// The envelope, carrying `ok` with the route taken and the evidence found,
    /// or `error`.
    ///
    /// # Atomic
    /// Serialised on the store's lock, which [`Self::remember`] also takes:
    /// recall reads the index that ingest mutates.
    pub(crate) fn recall(&self, query: &str, top_k: usize) -> String {
        rendered(self.with(|store| {
            store
                .query(query, (top_k > 0).then_some(top_k))
                .map_err(|why| Failed::Store(why.to_string()))
        }))
    }

    /// Run `body` against the store, holding its lock.
    ///
    /// A poisoned lock is reported rather than recovered from: `ingest_turn`
    /// mutates the index in place, so a panic partway leaves it in a state
    /// nothing here can describe. `testenv::with_env` recovers because its guard
    /// restores what it touched; this has no such guarantee.
    fn with<T>(
        &self,
        body: impl FnOnce(&mut zeromem::ZeroMem) -> Result<T, Failed>,
    ) -> Result<T, Failed> {
        let mut store = self.inner.lock().map_err(|_| Failed::Poisoned)?;
        body(&mut store)
    }
}

#[cfg(test)]
mod tests {
    use crate::testenv::Scratch;
    use serde_json::Value;

    /// Open a store as the app will. Dropping it is the whole of closing it, so
    /// nothing here has to remember to.
    fn with_store<T>(name: &str, keep: usize, body: impl FnOnce(&super::Memory) -> T) -> T {
        let scratch = Scratch::new(name);
        let path = scratch.path().join("memory.db");
        let store = super::open(&path, keep).expect("the store did not open");
        body(&store)
    }

    /// Decode what a call answered.
    fn answer(json: &str) -> Value {
        serde_json::from_str(json).expect("the envelope is JSON")
    }

    fn remember(store: &super::Memory, text: &str, ts: i64) -> Value {
        answer(&store.remember("s", "user", text, ts))
    }

    #[test]
    fn a_store_opens_where_there_was_no_file() {
        // A first run: the horizon does nothing and the store makes the file.
        with_store("memory-fresh", 100, |store| {
            let written = remember(store, "the boiler is behind the airing cupboard", 1);
            assert!(written["ok"].is_i64(), "crossed as {written}");
        });
    }

    #[test]
    fn a_turn_with_no_text_is_refused_rather_than_stored() {
        // zeromem indexes nothing, so it is a turn that can never be recalled
        // and still counts against the horizon.
        with_store("memory-empty", 100, |store| {
            let refusal = remember(store, "   ", 1);
            assert!(
                refusal["error"]
                    .as_str()
                    .unwrap_or_default()
                    .contains("no text"),
                "stored it: {refusal}"
            );
        });
    }

    #[test]
    fn what_went_in_comes_back_out() {
        // The round trip. Recall returns evidence rather than an answer, which is
        // what the tool above it will render.
        with_store("memory-roundtrip", 100, |store| {
            remember(store, "the spare key is with Dave next door", 1);

            let found = answer(&store.recall("where is the spare key", 5));

            let evidence = found["ok"]["evidence"].as_array().expect("evidence");
            assert!(!evidence.is_empty(), "recalled nothing: {found}");
            assert!(
                evidence[0]["text"]
                    .as_str()
                    .unwrap_or_default()
                    .contains("spare key"),
                "recalled the wrong turn: {found}"
            );
        });
    }

    #[test]
    fn a_top_k_of_zero_takes_the_default_rather_than_returning_nothing() {
        // Zero is what a caller sends when it has no opinion, and an unchecked
        // `Some(0)` would answer every question with silence.
        with_store("memory-topk", 100, |store| {
            remember(store, "the bins go out on Tuesday", 1);

            let found = answer(&store.recall("bins", 0));
            assert!(
                !found["ok"]["evidence"]
                    .as_array()
                    .expect("evidence")
                    .is_empty(),
                "zero was read as none: {found}"
            );
        });
    }
}
