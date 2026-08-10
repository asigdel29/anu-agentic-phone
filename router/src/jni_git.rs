//! `jni_git.rs`: a repository, reached from Kotlin.
//!
//! History
//!   2026-08-09  A. Sigdel  Created.
//!   2026-08-09  A. Sigdel  Moved the two helpers to `jni_answer.rs`.
//!   2026-08-09  A. Sigdel  Guarded the four entry points.
//!   2026-08-10  A. Sigdel  Reads a `String` and answers one with #565. There is
//!                          no Rust allocation crossing to free.
//!
//! These go through `core_git` rather than `crate::git` directly, so the envelope
//! a model reads is built in one place. That used to be phrased as both phones
//! reaching a repository by one path; there is one phone since #545, and the
//! reason survives it: the envelope is the thing being kept single, not the
//! number of callers.
//!
//! One structural difference decides what the Kotlin looks like. A repository is
//! a path rather than a handle: `git::open` runs inside every call and there is
//! nothing to hold between them. So `Repository.kt` is not `Memory.kt`: no
//! handle, no `AutoCloseable`, no close. That ceremony would invent a lifetime
//! which does not exist, and hand somebody a `close` to forget.
//!
//! What is left here is five translations and nothing else. `guarded` is
//! `jni_answer.rs`'s, because the copy that used to be at the bottom of this file
//! was identical to the one at the bottom of `jni_memory.rs` and neither applied
//! the rules `jni.rs` states; see #468 and #482. `read` is `jni.rs`'s, for the
//! same reason: it clears a pending exception, which `owned` also did and which
//! nothing here should be reimplementing.

use crate::jni::read;
use crate::jni_answer::{guarded, handed};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::path::Path;

/// Make a directory into a repository, or say it already was one.
///
/// # Safety
/// Called from Kotlin through `Repository.init`, which passes a path it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeInit<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let Some(path) = read(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        handed(&mut env, crate::core_git::init(Path::new(&path)))
    })
}

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
    guarded(std::ptr::null_mut(), || {
        let Some(path) = read(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        handed(&mut env, crate::core_git::head(Path::new(&path)))
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
    guarded(std::ptr::null_mut(), || {
        let Some(path) = read(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        handed(&mut env, crate::core_git::status(Path::new(&path)))
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
    guarded(std::ptr::null_mut(), || {
        // JSON across this boundary too, and for the C ABI's reason: the model
        // writes an array, the tool decodes one, and rebuilding it as a Kotlin
        // Array<String> only to encode it again is three shapes for one value.
        let (Some(path), Some(paths_json)) = (read(&mut env, &path), read(&mut env, &paths_json))
        else {
            return std::ptr::null_mut();
        };
        handed(
            &mut env,
            crate::core_git::add(Path::new(&path), &paths_json),
        )
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
    guarded(std::ptr::null_mut(), || {
        let (Some(path), Some(message)) = (read(&mut env, &path), read(&mut env, &message)) else {
            return std::ptr::null_mut();
        };
        handed(
            &mut env,
            crate::core_git::commit(Path::new(&path), &message),
        )
    })
}
