//! `jni.rs` — the decision core, as Kotlin calls it.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!
//! Contents
//!   `Java_..._nativeNew`   Build a router.
//!   `Java_..._nativeFree`  Release one.
//!
//! Deciding joins this file next; this is the lifetime it hangs off.
//!
//! Not the C ABI with different names. `ffi.rs` hands back a struct by value and
//! borrowed `const char *`; JNI deals in `jstring`, which the JVM owns on both
//! sides, so this is a translation layer and the translation is where the
//! mistakes are.
//!
//! No panic may cross into the JVM — undefined behaviour, exactly as into C, and
//! the guard belongs here for the same reason it belongs in `ffi.rs`.
//!
//! The symbol names are the contract with `Core.kt` and nothing checks them at
//! build time: getting one wrong is an `UnsatisfiedLinkError` the first time
//! somebody runs the app. `the_symbols_match_the_kotlin` holds the two in step,
//! the way the header parity test does for C.

use crate::ffi::Router;
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jlong;
use std::panic::{AssertUnwindSafe, catch_unwind};

/// Build a router.
///
/// # Returns
/// A handle to pass back, or `0` IF configuration was rejected — which is what
/// `ffi.rs` reports as null, said as a number because a `jlong` is what crosses.
///
/// # Safety
/// The returned handle must reach `nativeFree` exactly once.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Core_nativeNew(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(|| {
        // The configured default head. Kotlin has no path to hand in, because on
        // a phone there is no head to load — the policy has an unscored path and
        // that is what Android takes, as iOS does.
        let head = std::ptr::null();
        let router = unsafe { crate::ffi::wattrouter_new(head) };
        router as jlong
    })
    .unwrap_or(0)
}

/// Release a router.
///
/// # Safety
/// `handle` must come from `nativeNew` and not already be freed. Zero is
/// accepted and ignored, so a Kotlin field cleared twice is not a crash.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Core_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        crate::ffi::wattrouter_free(handle as *mut Router);
    }));
}

#[cfg(test)]
mod tests {
    /// The Kotlin the library has to satisfy.
    const KOTLIN: &str =
        include_str!("../../android/src/main/kotlin/com/getlora/wattrouter/Core.kt");

    #[test]
    fn the_symbols_match_the_kotlin() {
        // Nothing checks this at build time. A name that disagrees is an
        // UnsatisfiedLinkError the first time somebody runs the app, which is
        // the worst place to find out and the easiest thing to hold in step.
        let declared: std::collections::BTreeSet<String> = KOTLIN
            .lines()
            .filter_map(|line| {
                let at = line.find("external fun ")? + "external fun ".len();
                let rest = &line[at..];
                Some(rest[..rest.find('(')?].to_owned())
            })
            .collect();

        assert!(
            declared.contains("nativeNew"),
            "the scan found nothing, which would make agreement vacuous"
        );
        assert_eq!(declared, symbols(), "Core.kt and jni.rs disagree");
    }

    /// The same set, read out of the Rust rather than the Kotlin.
    fn symbols() -> std::collections::BTreeSet<String> {
        let source = include_str!("jni.rs");
        source
            .match_indices("pub extern \"system\" fn Java_com_getlora_wattrouter_Core_")
            .filter_map(|(at, _)| {
                let rest = &source[at..];
                let start = rest.find("Core_")? + "Core_".len();
                let tail = &rest[start..];
                let end = tail.find(|c: char| !c.is_ascii_alphanumeric() && c != '_')?;
                Some(tail[..end].to_owned())
            })
            .collect()
    }

    #[test]
    fn the_kotlin_frees_what_it_opens() {
        // A handle the Kotlin never releases is a leak nothing here can see, and
        // one it releases twice is a double free. Both are prevented by close()
        // being idempotent, which is worth asserting is still written that way.
        assert!(
            KOTLIN.contains("if (handle == 0L) return"),
            "close is not idempotent"
        );
        assert!(
            KOTLIN.contains("handle = 0L"),
            "close does not clear the handle"
        );
    }
}
