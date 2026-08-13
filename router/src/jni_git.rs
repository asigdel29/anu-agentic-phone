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
//! What is left here is ten translations and nothing else. The panic guard is
//! `with_env`'s since jni 0.22: it wraps the closure in a `catch_unwind` and
//! the error policy answers with the return type's default, which is the null
//! this used to hand back by hand. #482 added that guard as a helper here and
//! #468 removed the second copy of it; the crate now gets both from the
//! library. `read` is `jni.rs`'s, for the
//! same reason: it clears a pending exception, which `owned` also did and which
//! nothing here should be reimplementing.

use crate::jni::read;
use crate::jni_answer::handed;
use jni::Env;
use jni::EnvUnowned;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::path::Path;

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
fn optional(
    env: &mut Env<'_>,
    key: &JString<'_>,
    pins: &JString<'_>,
) -> Option<(String, std::path::PathBuf)> {
    let key = read(env, key)?;
    let pins = read(env, pins)?;
    Some((key, std::path::PathBuf::from(pins)))
}

/// Make a directory into a repository, or say it already was one.
///
/// # Safety
/// Called from Kotlin through `Repository.init`, which passes a path it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeInit<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let Some(path) = read(env, &path) else {
                return Ok(std::ptr::null_mut());
            };
            Ok(handed(env, crate::core_git::init(Path::new(&path))))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Where `HEAD` points.
///
/// # Safety
/// Called from Kotlin through `Repository.head`, which passes a path it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeHead<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let Some(path) = read(env, &path) else {
                return Ok(std::ptr::null_mut());
            };
            Ok(handed(env, crate::core_git::head(Path::new(&path))))
        })
        .resolve::<LogErrorAndDefault>()
}

/// The working tree, against the index and the head.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Repository_nativeHead`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeStatus<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let Some(path) = read(env, &path) else {
                return Ok(std::ptr::null_mut());
            };
            Ok(handed(env, crate::core_git::status(Path::new(&path))))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Stage paths, and answer with the status that results.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Repository_nativeHead`], for both arguments.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeAdd<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    paths_json: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            // JSON across this boundary too, and for the C ABI's reason: the
            // model writes an array, the tool decodes one, and rebuilding it as
            // a Kotlin Array<String> only to encode it again is three shapes
            // for one value.
            let (Some(path), Some(paths_json)) = (read(env, &path), read(env, &paths_json)) else {
                return Ok(std::ptr::null_mut());
            };
            Ok(handed(
                env,
                crate::core_git::add(Path::new(&path), &paths_json),
            ))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Commit what is staged.
///
/// # Safety
/// As [`Java_com_getlora_wattrouter_Repository_nativeHead`], for both arguments.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeCommit<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    message: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let (Some(path), Some(message)) = (read(env, &path), read(env, &message)) else {
                return Ok(std::ptr::null_mut());
            };
            Ok(handed(
                env,
                crate::core_git::commit(Path::new(&path), &message),
            ))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Say who commits from here.
///
/// # Safety
/// Called from Kotlin through `Repository.identify`, which passes three strings
/// it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeIdentify<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    name: JString<'a>,
    email: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let (Some(path), Some(name), Some(email)) =
                (read(env, &path), read(env, &name), read(env, &email))
            else {
                return Ok(std::ptr::null_mut());
            };
            Ok(handed(
                env,
                crate::core_git::identify(Path::new(&path), &name, &email),
            ))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Point a remote at a URL, and say whether that added or moved one.
///
/// # Safety
/// Called from Kotlin through `Repository.remoteSet`, which passes three strings
/// it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeRemoteSet<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    name: JString<'a>,
    url: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let (Some(path), Some(name), Some(url)) =
                (read(env, &path), read(env, &name), read(env, &url))
            else {
                return Ok(std::ptr::null_mut());
            };
            Ok(handed(
                env,
                crate::core_git::remote_set(Path::new(&path), &name, &url),
            ))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Bring back what a remote has, merging nothing.
///
/// # Safety
/// Called from Kotlin through `Repository.fetch`, which passes two strings it
/// owns and two it may pass as null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativeFetch<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    name: JString<'a>,
    key: JString<'a>,
    pins: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let (Some(path), Some(name)) = (read(env, &path), read(env, &name)) else {
                return Ok(std::ptr::null_mut());
            };
            let held = optional(env, &key, &pins);
            let reach = held
                .as_ref()
                .map(|(key, pins)| crate::git::Reach::new(key, pins));
            Ok(handed(
                env,
                crate::core_git::fetch(Path::new(&path), &name, reach.as_ref()),
            ))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Send a branch to a remote, and refuse rather than overwrite.
///
/// # Safety
/// Called from Kotlin through `Repository.push`, which passes three strings it
/// owns and two it may pass as null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativePush<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    remote: JString<'a>,
    branch: JString<'a>,
    key: JString<'a>,
    pins: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let (Some(path), Some(remote), Some(branch)) =
                (read(env, &path), read(env, &remote), read(env, &branch))
            else {
                return Ok(std::ptr::null_mut());
            };
            let held = optional(env, &key, &pins);
            let reach = held
                .as_ref()
                .map(|(key, pins)| crate::git::Reach::new(key, pins));
            Ok(handed(
                env,
                crate::core_git::push(Path::new(&path), &remote, &branch, reach.as_ref()),
            ))
        })
        .resolve::<LogErrorAndDefault>()
}

/// Take what a remote has, if that can be done without merging.
///
/// # Safety
/// Called from Kotlin through `Repository.pull`, which passes three strings it
/// owns and two it may pass as null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Repository_nativePull<'a>(
    mut unowned: EnvUnowned<'a>,
    _class: JClass<'a>,
    path: JString<'a>,
    remote: JString<'a>,
    branch: JString<'a>,
    key: JString<'a>,
    pins: JString<'a>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let (Some(path), Some(remote), Some(branch)) =
                (read(env, &path), read(env, &remote), read(env, &branch))
            else {
                return Ok(std::ptr::null_mut());
            };
            let held = optional(env, &key, &pins);
            let reach = held
                .as_ref()
                .map(|(key, pins)| crate::git::Reach::new(key, pins));
            Ok(handed(
                env,
                crate::core_git::pull(Path::new(&path), &remote, &branch, reach.as_ref()),
            ))
        })
        .resolve::<LogErrorAndDefault>()
}
