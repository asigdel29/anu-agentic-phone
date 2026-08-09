//! `jni_memory.rs` — the memory store, reached from Kotlin.
//!
//! History
//!   2026-08-09  A. Sigdel  Created.
//!   2026-08-09  A. Sigdel  Moved the two helpers to `jni_answer.rs`.
//!   2026-08-09  A. Sigdel  Guarded the four entry points.
//!
//! The C ABI beside this is what Swift links; Kotlin cannot use it, so these
//! wrap the same calls in the shape JNI wants. They go through
//! `wattrouter_memory_*` rather than `crate::memory` directly, so both phones
//! reach the store by the same path and a change to one is a change to both.
//!
//! A store is a handle with a lifetime, which is the second thing Kotlin owns
//! and must free — `Core` was the first, and `Memory.kt` copies its shape: a
//! private constructor over a `Long`, `AutoCloseable`, an idempotent `close`.
//!
//! Every allocating call here frees the Rust string before returning. A Kotlin
//! `String` is a copy by the time `new_string` returns, so there is nothing to
//! keep and leaving it would leak once per call.
//!
//! `owned`, `answered` and `guarded` are `jni_answer.rs`'s. The copy that used
//! to be at the bottom of this file was identical to `jni_git.rs`'s and neither
//! applied the rules `jni.rs` states — see #468 and #482.

use crate::ffi_memory::Memory;
use crate::jni_answer::{answered, guarded, owned};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};

/// Open a store, applying the horizon on the way in.
///
/// # Safety
/// Called from Kotlin through `Memory.open`, which passes a path it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Memory_nativeOpen<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    keep: jlong,
) -> jlong {
    guarded(0, || {
        let Some(path) = owned(&mut env, &path) else {
            return 0;
        };

        // Negative keep would wrap into an enormous usize and bound nothing,
        // which is the one way this could quietly do the opposite of its job.
        // The saturation beside it cannot fire on any ABI this ships for —
        // `abiFilters` is arm64-v8a alone — and is written for the one it does
        // not, rather than left to be found later and mistaken for a hazard.
        let keep = usize::try_from(keep.max(0)).unwrap_or(usize::MAX);

        let store = unsafe { crate::ffi_memory::wattrouter_memory_open(path.as_ptr(), keep) };
        store as jlong
    })
}

/// Release a store. Zero is accepted and ignored, so `close` may be idempotent.
///
/// # Safety
/// `handle` must be zero or a store from
/// [`Java_com_getlora_wattrouter_Memory_nativeOpen`], freed once.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Memory_nativeFree(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    guarded((), || {
        if handle != 0 {
            unsafe { crate::ffi_memory::wattrouter_memory_free(handle as *mut Memory) };
        }
    });
}

/// Put a turn in.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Memory_nativeFree`], and the handle must
/// not be freed concurrently.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Memory_nativeRemember<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    session: JString<'a>,
    speaker: JString<'a>,
    text: JString<'a>,
    ts: jlong,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let (Some(session), Some(speaker), Some(text)) = (
            owned(&mut env, &session),
            owned(&mut env, &speaker),
            owned(&mut env, &text),
        ) else {
            return std::ptr::null_mut();
        };

        answered(&mut env, unsafe {
            crate::ffi_memory::wattrouter_memory_remember(
                handle as *const Memory,
                session.as_ptr(),
                speaker.as_ptr(),
                text.as_ptr(),
                ts,
            )
        })
    })
}

/// Ask the store something.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Memory_nativeRemember`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Memory_nativeRecall<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    query: JString<'a>,
    most: jlong,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let Some(query) = owned(&mut env, &query) else {
            return std::ptr::null_mut();
        };

        answered(&mut env, unsafe {
            crate::ffi_memory::wattrouter_memory_recall(
                handle as *const Memory,
                query.as_ptr(),
                usize::try_from(most.max(0)).unwrap_or(usize::MAX),
            )
        })
    })
}
