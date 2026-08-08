//! `ffi_memory.rs` — the memory store, across the C ABI.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!
//! Contents
//!   `Memory`                     A store; opaque.
//!   `wattrouter_memory_open`     Bound it, then open it.
//!   `wattrouter_memory_free`     Release it.
//!   `wattrouter_memory_remember` Put a turn in.
//!   `wattrouter_memory_recall`   Ask it something.
//!
//! Opening is where the horizon runs. `ZeroMem::open` loads and indexes every
//! turn, so bounding afterwards is bounding it after paying for it —
//! `memory::apply` goes first, in the same call, so no caller can get that wrong.
//!
//! A handle rather than a path per call, unlike the git half: libgit2 is cheap to
//! open and a repository changes underneath, where a `ZeroMem` is the index and
//! rebuilding it per question is the cost this milestone exists to avoid. Behind
//! a mutex, because `ingest_turn` takes `&mut self`.

use crate::ffi_answer::{borrowed, guarded, refused, rendered};
use crate::memory;
use std::ffi::c_char;
use std::path::Path;
use std::sync::Mutex;

/// A memory store; opaque to C.
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
/// * `path` — the database, WHERE it need not exist yet.
/// * `keep` — how many recent turns to leave in front of the horizon.
///
/// # Returns
/// A handle to pass to [`wattrouter_memory_free`], or null IF `path` was null or
/// not UTF-8, IF the horizon failed, or IF the store would not open. Null rather
/// than an envelope: with no handle there is nothing to free and nothing to hang
/// a message on.
///
/// # Safety
/// `path` must be null or a valid NUL-terminated string outliving the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_memory_open(path: *const c_char, keep: usize) -> *mut Memory {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Some(path) = (unsafe { borrowed(path) }) else {
            return std::ptr::null_mut();
        };
        let path = Path::new(path);

        // Before open, not after: opening is what loads everything.
        if memory::apply(path, keep).is_err() {
            return std::ptr::null_mut();
        }

        // The hash embedder, always: the ONNX one cannot come to a phone, and a
        // store written by one is cleared when opened by the other.
        let store = zeromem::ZeroMem::open(
            path,
            zeromem::config::Config::default(),
            Box::new(zeromem::embed::HashEmbedder::default()),
        );
        match store {
            Ok(store) => Box::into_raw(Box::new(Memory {
                inner: Mutex::new(store),
            })),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Release a store.
///
/// # Safety
/// `memory` must come from [`wattrouter_memory_open`] and not already be freed.
/// Null is accepted and ignored.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_memory_free(memory: *mut Memory) {
    if memory.is_null() {
        return;
    }
    // Discarded deliberately: a caller freeing a store has nowhere to report a
    // failure to, and nothing it could do about one.
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        drop(unsafe { Box::from_raw(memory) });
    }));
}

/// Put a turn in.
///
/// # Returns
/// An owned JSON string to pass to `wattrouter_string_free`, carrying `ok` with
/// the turn's id or `error`. Null IF any pointer was null or not UTF-8.
///
/// # Safety
/// Every pointer must be null or a valid NUL-terminated string outliving the
/// call, and `memory` must come from [`wattrouter_memory_open`].
///
/// # Atomic
/// Serialised on the store's lock. A second caller waits.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_memory_remember(
    memory: *const Memory,
    session: *const c_char,
    speaker: *const c_char,
    text: *const c_char,
    ts: i64,
) -> *mut c_char {
    guarded(|| {
        let (Some(memory), Some(session), Some(speaker), Some(text)) = (
            unsafe { memory.as_ref() },
            unsafe { borrowed(session) },
            unsafe { borrowed(speaker) },
            unsafe { borrowed(text) },
        ) else {
            return std::ptr::null_mut();
        };

        // Refused rather than stored: zeromem indexes nothing, so it becomes a
        // turn that cannot be recalled and counts against the horizon anyway.
        if text.trim().is_empty() {
            return refused("a turn with no text cannot be remembered");
        }

        rendered(memory.with(|store| {
            store
                .ingest_turn(session, speaker, text, ts)
                .map_err(|why| Failed::Store(why.to_string()))
        }))
    })
}

/// Ask the store something.
///
/// # Arguments
/// * `top_k` — how many pieces of evidence to return, WHERE `0` takes zeromem's
///   own default rather than returning nothing.
///
/// # Returns
/// An owned JSON string to pass to `wattrouter_string_free`, carrying `ok` with
/// the route taken and the evidence found, or `error`. Null on the terms
/// [`wattrouter_memory_remember`] states.
///
/// # Safety
/// As [`wattrouter_memory_remember`].
///
/// # Atomic
/// Serialised on the store's lock, which `remember` also takes: recall reads the
/// index that ingest mutates.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_memory_recall(
    memory: *const Memory,
    query: *const c_char,
    top_k: usize,
) -> *mut c_char {
    guarded(|| {
        let (Some(memory), Some(query)) = (unsafe { memory.as_ref() }, unsafe { borrowed(query) })
        else {
            return std::ptr::null_mut();
        };

        rendered(memory.with(|store| {
            store
                .query(query, (top_k > 0).then_some(top_k))
                .map_err(|why| Failed::Store(why.to_string()))
        }))
    })
}

impl Memory {
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
    use crate::ffi_answer::wattrouter_string_free;
    use crate::testenv::Scratch;
    use serde_json::Value;
    use std::ffi::{CStr, CString, c_char};

    /// Open a store as the app will, and free it afterwards.
    fn with_store<T>(name: &str, keep: usize, body: impl FnOnce(*mut super::Memory) -> T) -> T {
        let scratch = Scratch::new(name);
        let path = CString::new(scratch.path().join("memory.db").to_str().unwrap()).unwrap();
        let store = unsafe { super::wattrouter_memory_open(path.as_ptr(), keep) };
        assert!(!store.is_null(), "the store did not open");

        let out = body(store);
        unsafe { super::wattrouter_memory_free(store) };
        out
    }

    /// Take ownership of an envelope and read it.
    fn answer(returned: *mut c_char) -> Value {
        assert!(!returned.is_null(), "no answer at all");
        let json = unsafe { CStr::from_ptr(returned) }
            .to_str()
            .expect("the envelope is UTF-8")
            .to_owned();
        unsafe { wattrouter_string_free(returned) };
        serde_json::from_str(&json).expect("the envelope is JSON")
    }

    fn remember(store: *mut super::Memory, text: &str, ts: i64) -> Value {
        let session = CString::new("s").unwrap();
        let speaker = CString::new("user").unwrap();
        let text = CString::new(text).unwrap();
        answer(unsafe {
            super::wattrouter_memory_remember(
                store,
                session.as_ptr(),
                speaker.as_ptr(),
                text.as_ptr(),
                ts,
            )
        })
    }

    #[test]
    fn a_store_opens_where_there_was_no_file() {
        // A first run: the horizon does nothing and the store makes the file.
        with_store("ffi-memory-fresh", 100, |store| {
            let written = remember(store, "the boiler is behind the airing cupboard", 1);
            assert!(written["ok"].is_i64(), "crossed as {written}");
        });
    }

    #[test]
    fn a_turn_with_no_text_is_refused_rather_than_stored() {
        // zeromem indexes nothing, so it is a turn that can never be recalled
        // and still counts against the horizon.
        with_store("ffi-memory-empty", 100, |store| {
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
        // The round trip, through C both ways. Recall returns evidence rather
        // than an answer, which is what the tool above it will render.
        with_store("ffi-memory-roundtrip", 100, |store| {
            remember(store, "the spare key is with Dave next door", 1);

            let query = CString::new("where is the spare key").unwrap();
            let found =
                answer(unsafe { super::wattrouter_memory_recall(store, query.as_ptr(), 5) });

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
        with_store("ffi-memory-topk", 100, |store| {
            remember(store, "the bins go out on Tuesday", 1);

            let query = CString::new("bins").unwrap();
            let found =
                answer(unsafe { super::wattrouter_memory_recall(store, query.as_ptr(), 0) });
            assert!(
                !found["ok"]["evidence"]
                    .as_array()
                    .expect("evidence")
                    .is_empty(),
                "zero was read as none: {found}"
            );
        });
    }

    #[test]
    fn hostile_input_returns_a_value_rather_than_unwinding() {
        // A panic across the boundary is undefined behaviour, and so is a bad free.
        assert!(unsafe { super::wattrouter_memory_open(std::ptr::null(), 10) }.is_null());
        unsafe { super::wattrouter_memory_free(std::ptr::null_mut()) };

        let t = CString::new("x").unwrap();
        let (p, n) = (t.as_ptr(), std::ptr::null());
        assert!(unsafe { super::wattrouter_memory_remember(n, p, p, p, 1) }.is_null());
        assert!(unsafe { super::wattrouter_memory_recall(n, p, 1) }.is_null());
    }
}
