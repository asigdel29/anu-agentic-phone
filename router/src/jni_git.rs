//! `jni_git.rs`: a repository, reached from Kotlin.
//!
//! History
//!   2026-08-09  A. Sigdel  Created.
//!   2026-08-09  A. Sigdel  Moved the two helpers to `jni_answer.rs`.
//!   2026-08-09  A. Sigdel  Guarded the four entry points.
//!   2026-08-10  A. Sigdel  Reads a `String` and answers one with #565. There is
//!                          no Rust allocation crossing to free.
//!   2026-08-11  A. Sigdel  Took in identify with #636, so a commit on a phone
//!                          has somebody to sign it.
//!   2026-08-12  A. Sigdel  Took in the first two network calls with #668, so
//!                          the work has somewhere to go.
//!   2026-08-12  A. Sigdel  Took in the two that can lose work, with #671.
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
//! What is left here is ten translations and nothing else. `guarded` is
//! `jni_answer.rs`'s, because the copy that used to be at the bottom of this file
//! was identical to the one at the bottom of `jni_memory.rs` and neither applied
//! the rules `jni.rs` states; see #468 and #482. `read` is `jni.rs`'s, for the
//! same reason: it clears a pending exception, which `owned` also did and which
//! nothing here should be reimplementing.

use crate::jni::read;
use crate::jni_answer::{guarded, handed};

/// The key and the pin file, when Kotlin passed both.
///
/// Owned rather than borrowed, and handed back for the caller to build a
/// [`crate::git::Reach`] from: that type borrows both halves, so something has
/// to hold them for the length of the call and a helper answering the borrow
/// would be answering one to its own local.
///
/// Either half absent is no reach at all, which is a repository reaching a path
/// remote. [`read`] answers `None` for a null argument as well as for a string
/// it cannot decode, and both mean the same thing here: nothing to reach with.
fn optional<'a>(
    env: &mut JNIEnv<'a>,
    key: &JString<'a>,
    pins: &JString<'a>,
) -> Option<(String, std::path::PathBuf)> {
    let key = read(env, key)?;
    let pins = read(env, pins)?;
    Some((key, std::path::PathBuf::from(pins)))
}
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

/// Say who commits from here.
///
/// Three arguments rather than two, and the only entry point in this file that
/// takes what a person typed rather than what a model wrote. It is called by
/// the app when it sets a repository up, never from a tool: whose name goes on
/// the work is not a decision to hand a model.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Repository_nativeHead`], for all three.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeIdentify<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    name: JString<'a>,
    email: JString<'a>,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let (Some(path), Some(name), Some(email)) = (
            read(&mut env, &path),
            read(&mut env, &name),
            read(&mut env, &email),
        ) else {
            return std::ptr::null_mut();
        };
        handed(
            &mut env,
            crate::core_git::identify(Path::new(&path), &name, &email),
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

/// Point a remote somewhere, and say what that changed.
///
/// # Safety
/// Called from Kotlin through `Repository.remoteSet`, which passes three
/// strings it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeRemoteSet<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    name: JString<'a>,
    url: JString<'a>,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        // All three before any of them is used, so a null or a string that is
        // not UTF-8 is refused rather than half applied. `identify` reads its
        // three the same way and for the same reason.
        let Some(path) = read(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        let Some(name) = read(&mut env, &name) else {
            return std::ptr::null_mut();
        };
        let Some(url) = read(&mut env, &url) else {
            return std::ptr::null_mut();
        };
        handed(
            &mut env,
            crate::core_git::remote_set(Path::new(&path), &name, &url),
        )
    })
}

/// Bring back what a remote has, merging nothing.
///
/// # Safety
/// Called from Kotlin through `Repository.fetch`, which passes two strings it
/// owns and two it may pass as null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeFetch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    name: JString<'a>,
    key: JString<'a>,
    pins: JString<'a>,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let Some(path) = read(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        let Some(name) = read(&mut env, &name) else {
            return std::ptr::null_mut();
        };
        let held = optional(&mut env, &key, &pins);
        let reach = held
            .as_ref()
            .map(|(key, pins)| crate::git::Reach::new(key, pins));
        handed(
            &mut env,
            crate::core_git::fetch(Path::new(&path), &name, reach.as_ref()),
        )
    })
}

/// Send a branch to a remote, and refuse rather than overwrite.
///
/// # Safety
/// Called from Kotlin through `Repository.push`, which passes three strings it
/// owns and two it may pass as null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativePush<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    remote: JString<'a>,
    branch: JString<'a>,
    key: JString<'a>,
    pins: JString<'a>,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let Some(path) = read(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        let Some(remote) = read(&mut env, &remote) else {
            return std::ptr::null_mut();
        };
        let Some(branch) = read(&mut env, &branch) else {
            return std::ptr::null_mut();
        };
        let held = optional(&mut env, &key, &pins);
        let reach = held
            .as_ref()
            .map(|(key, pins)| crate::git::Reach::new(key, pins));
        handed(
            &mut env,
            crate::core_git::push(Path::new(&path), &remote, &branch, reach.as_ref()),
        )
    })
}

/// Take what a remote has, if that can be done without merging.
///
/// # Safety
/// Called from Kotlin through `Repository.pull`, which passes three strings it
/// owns and two it may pass as null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativePull<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    remote: JString<'a>,
    branch: JString<'a>,
    key: JString<'a>,
    pins: JString<'a>,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let Some(path) = read(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        let Some(remote) = read(&mut env, &remote) else {
            return std::ptr::null_mut();
        };
        let Some(branch) = read(&mut env, &branch) else {
            return std::ptr::null_mut();
        };
        let held = optional(&mut env, &key, &pins);
        let reach = held
            .as_ref()
            .map(|(key, pins)| crate::git::Reach::new(key, pins));
        handed(
            &mut env,
            crate::core_git::pull(Path::new(&path), &remote, &branch, reach.as_ref()),
        )
    })
}
