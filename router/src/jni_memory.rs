//! `jni_memory.rs` — the memory store, reached from Kotlin.
//!
//! History
//!   2026-08-09  A. Sigdel  Created.
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

use crate::ffi_memory::Memory;
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
    let Ok(path) = env.get_string(&path) else {
        return 0;
    };
    let Ok(path) = std::ffi::CString::new(path.to_string_lossy().as_ref()) else {
        return 0;
    };

    // Negative keep would wrap into an enormous usize and bound nothing, which
    // is the one way this could quietly do the opposite of its job.
    let keep = usize::try_from(keep.max(0)).unwrap_or(usize::MAX);

    let store = unsafe { crate::ffi_memory::wattrouter_memory_open(path.as_ptr(), keep) };
    store as jlong
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
    if handle != 0 {
        unsafe { crate::ffi_memory::wattrouter_memory_free(handle as *mut Memory) };
    }
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
}

/// A Java string as a C string, or nothing if it was neither.
fn owned(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<std::ffi::CString> {
    let read = env.get_string(value).ok()?;
    std::ffi::CString::new(read.to_string_lossy().as_ref()).ok()
}

/// Hand an envelope to Kotlin and free the Rust copy.
fn answered(env: &mut JNIEnv<'_>, raw: *mut std::ffi::c_char) -> jstring {
    if raw.is_null() {
        return std::ptr::null_mut();
    }
    let read = unsafe { std::ffi::CStr::from_ptr(raw) }
        .to_str()
        .ok()
        .and_then(|json| env.new_string(json).ok());

    // Before returning, and whether or not the conversion worked: new_string
    // copies, so nothing here refers to the Rust allocation afterwards.
    unsafe { crate::ffi_answer::wattrouter_string_free(raw) };

    read.map_or(std::ptr::null_mut(), jni::objects::JString::into_raw)
}
