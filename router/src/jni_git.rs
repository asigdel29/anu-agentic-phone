//! `jni_git.rs` — a repository, reached from Kotlin.
//!
//! History
//!   2026-08-09  A. Sigdel  Created.
//!
//! `jni_memory.rs`'s shape and its reasoning: these go through
//! `wattrouter_git_*` rather than `crate::git` directly, so both phones reach a
//! repository by one path and a change to one is a change to both. Every call
//! frees the Rust string before returning, as that file does — a Kotlin `String`
//! is a copy by the time `new_string` returns.
//!
//! One structural difference decides what the Kotlin looks like. A repository is
//! a path rather than a handle: `git::open` runs inside every call and there is
//! nothing to hold between them. So `Repository.kt` is not `Memory.kt` — no
//! handle, no `AutoCloseable`, no close. That ceremony would invent a lifetime
//! which does not exist, and hand somebody a `close` to forget.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

/// Where `HEAD` points.
///
/// # Safety
/// Called from Kotlin through `Repository.head`, which passes a path it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeHead<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
) -> jstring {
    let Some(path) = owned(&mut env, &path) else {
        return std::ptr::null_mut();
    };
    answered(&mut env, unsafe {
        crate::ffi_git::wattrouter_git_head(path.as_ptr())
    })
}

/// The working tree, against the index and the head.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Repository_nativeHead`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeStatus<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
) -> jstring {
    let Some(path) = owned(&mut env, &path) else {
        return std::ptr::null_mut();
    };
    answered(&mut env, unsafe {
        crate::ffi_git::wattrouter_git_status(path.as_ptr())
    })
}

/// Stage paths, and answer with the status that results.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Repository_nativeHead`], for both arguments.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeAdd<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    paths_json: JString<'a>,
) -> jstring {
    // JSON across this boundary too, and for the C ABI's reason: the model
    // writes an array, the tool decodes one, and rebuilding it as a Kotlin
    // Array<String> only to encode it again is three shapes for one value.
    let (Some(path), Some(paths_json)) = (owned(&mut env, &path), owned(&mut env, &paths_json))
    else {
        return std::ptr::null_mut();
    };
    answered(&mut env, unsafe {
        crate::ffi_git::wattrouter_git_add(path.as_ptr(), paths_json.as_ptr())
    })
}

/// Commit what is staged.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Repository_nativeHead`], for both arguments.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeCommit<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    message: JString<'a>,
) -> jstring {
    let (Some(path), Some(message)) = (owned(&mut env, &path), owned(&mut env, &message)) else {
        return std::ptr::null_mut();
    };
    answered(&mut env, unsafe {
        crate::ffi_git::wattrouter_git_commit(path.as_ptr(), message.as_ptr())
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
