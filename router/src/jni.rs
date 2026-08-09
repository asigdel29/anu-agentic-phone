//! `jni.rs` — the decision core, as Kotlin calls it.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!
//! Contents
//!   `Java_..._nativeConfigure`  The credential the core reads from.
//!   `Java_..._nativeNew`     Build a router.
//!   `Java_..._nativeFree`    Release one.
//!   `Java_..._nativeDecide`  The whole decision path, as one envelope.
//!
//! One envelope rather than the four accessors `ffi.rs` offers. Reading a tier
//! and then walking its chain is four crossings from Kotlin where it is four
//! function calls from Swift, and a decision arriving in pieces is one a caller
//! can assemble wrongly. The shape is `ffi_answer.rs`'s, so Android decodes
//! exactly what Swift decodes.
//!
//! A null `jstring` is reachable from Kotlin in a way a null `const char *`
//! mostly is not, because a nullable Kotlin type compiles to one without
//! complaint.
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

use crate::backend::Backend;
use crate::chain::chain_for;
use crate::config::Config;
use crate::ffi::Router;
use crate::tier::Tier;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use serde::Serialize;
use std::panic::{AssertUnwindSafe, catch_unwind};

/// A decision and the chain behind it, in one crossing.
#[derive(Serialize)]
struct Decided {
    /// The tier's name, as configuration and metrics spell it.
    tier: String,
    /// Why that tier.
    reason: String,
    /// Difficulty in `[0, 1]`, or absent where nothing scored it.
    ///
    /// Absent rather than null. `null` is a value a caller has to know to skip,
    /// and the rule everywhere else in this boundary — `Message`'s empty
    /// `tool_calls`, `ffi_answer`'s envelope — is that a key that means nothing
    /// is not written. A test on the emulator is what noticed the difference.
    #[serde(skip_serializing_if = "Option::is_none")]
    score: Option<f32>,
    /// What will actually be asked, in order.
    chain: Vec<Attempt>,
}

/// One model, and where it runs.
#[derive(Serialize)]
struct Attempt {
    model: String,
    /// `local` or `remote`.
    backend: String,
}

/// Put the provider credential where the core reads it.
///
/// `Config::from_env` reads the environment, and Kotlin has no `setenv` — so
/// without this `nativeNew` returns zero on every Android device and the reason
/// is invisible. iOS solves it the same way, in `Startup.install`.
///
/// # Safety
/// The environment is process-global and this writes it. Call before
/// [`Java_com_getlora_wattrouter_Core_nativeNew`] and from one thread, which is
/// what `Core.open` does — the same rely `config.rs` states and `Startup.swift`
/// keeps by confining every write to one actor.
///
/// # Returns
/// Whether the credential was taken. `false` for null or non-UTF-8, and for an
/// empty one — which reaches the provider as a 401 rather than as a refusal
/// here, and is the failure people spend an afternoon on.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Core_nativeConfigure<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    credential: JString<'a>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(credential) = read(&mut env, &credential) else {
            return u8::from(false);
        };
        if credential.trim().is_empty() {
            return u8::from(false);
        }
        // Safe under the rely above: one thread, before anything reads it.
        unsafe { std::env::set_var("NEURALWATT_API_KEY", credential) };
        u8::from(true)
    }))
    .unwrap_or(u8::from(false))
}

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

/// Decide which tier serves a request, and say what stands behind it.
///
/// # Returns
/// A JSON envelope: `ok` with the tier, the reason, the score and the chain, or
/// `error`. A null `jstring` IF the JVM refused to make one, which is an
/// out-of-memory condition and not something to report as a decision.
///
/// # Safety
/// `handle` must come from `nativeNew`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_getlora_wattrouter_Core_nativeDecide<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    body: JString<'a>,
    session: JString<'a>,
) -> jstring {
    let answered = catch_unwind(AssertUnwindSafe(|| {
        let Some(router) = (unsafe { (handle as *const Router).as_ref() }) else {
            return Err("no router: it was never built, or it has been freed".to_owned());
        };
        // Nullable in Kotlin compiles to a null jstring without complaint, and
        // `get_string` on one is an exception rather than an error value.
        let body = read(&mut env, &body).ok_or("the request body was null or not UTF-8")?;
        let session = read(&mut env, &session).unwrap_or_default();

        decide(router, &body, &session).ok_or_else(|| {
            "the request could not be decided: it is not an OpenAI-shaped chat completion"
                .to_owned()
        })
    }));

    let envelope = match answered {
        Ok(Ok(decided)) => serde_json::to_string(&Answer::Ok(decided)),
        Ok(Err(why)) => serde_json::to_string(&Answer::<Decided>::Error(why)),
        Err(_) => serde_json::to_string(&Answer::<Decided>::Error(
            "the routing core failed while deciding".to_owned(),
        )),
    };

    // A JVM that will not allocate a string is out of memory, and there is
    // nothing useful to say to it. Null, which Kotlin sees as null.
    envelope
        .ok()
        .and_then(|json| env.new_string(json).ok())
        .map_or(std::ptr::null_mut(), jni::objects::JString::into_raw)
}

/// The same envelope the C half uses, so both phones decode one shape.
#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
enum Answer<T: Serialize> {
    Ok(T),
    Error(String),
}

/// One `jstring`, as a Rust `String`.
///
/// `None` for null and for anything the JVM will not give back as UTF-8. Clears
/// the pending exception either way: leaving one set means the next JNI call
/// from Kotlin fails for a reason that has nothing to do with it.
fn read(env: &mut JNIEnv<'_>, text: &JString<'_>) -> Option<String> {
    if text.is_null() {
        return None;
    }
    if let Ok(got) = env.get_string(text) {
        return Some(got.into());
    }
    // Discarded deliberately: there is nothing to do about a failed clear, and
    // the caller is already returning the absence.
    let _ = env.exception_clear();
    None
}

/// The decision, and the chain standing behind it.
fn decide(router: &Router, body: &str, session: &str) -> Option<Decided> {
    let raw = std::ffi::CString::new(body).ok()?;
    let held = std::ffi::CString::new(session).ok()?;
    let answer = unsafe {
        crate::ffi::wattrouter_decide(router, raw.as_ptr(), std::ptr::null(), held.as_ptr())
    };
    if answer.tier == u8::MAX {
        return None;
    }

    let tier = Tier::ALL.get(answer.tier as usize).copied()?;
    let config = Config::from_env().ok()?;
    Some(Decided {
        tier: name(crate::ffi::wattrouter_tier_name(answer.tier))?,
        reason: name(crate::ffi::wattrouter_reason_name(answer.reason))?,
        // Absent rather than -1. A number that means "no number" is a number a
        // caller compares against a threshold.
        score: (answer.score >= 0.0).then_some(answer.score),
        chain: chain_for(&config, tier)
            .into_iter()
            .map(|step| Attempt {
                model: step.model().to_owned(),
                backend: match step.backend() {
                    Backend::Local => "local".to_owned(),
                    Backend::Remote => "remote".to_owned(),
                },
            })
            .collect(),
    })
}

/// A borrowed static name, as an owned `String`.
fn name(raw: *const std::ffi::c_char) -> Option<String> {
    if raw.is_null() {
        return None;
    }
    Some(
        unsafe { std::ffi::CStr::from_ptr(raw) }
            .to_str()
            .ok()?
            .to_owned(),
    )
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
    fn a_decision_carries_its_chain_rather_than_only_a_tier() {
        // The reason this is one call. A tier is a role; what answers is the
        // chain, and a Kotlin caller holding only the tier cannot dispatch.
        crate::testenv::with_env(&[("NEURALWATT_API_KEY", Some("jni-test"))], || {
            let router = unsafe { crate::ffi::wattrouter_new(std::ptr::null()) };
            assert!(!router.is_null(), "router builds without a head");

            let decided = super::decide(
                unsafe { &*router },
                r#"{"messages":[{"role":"user","content":"hello there"}]}"#,
                "",
            )
            .expect("a well-formed request decides");

            assert_eq!(decided.tier, "mid", "unscored lands in the middle");
            assert_eq!(decided.reason, "unscored");
            // Absent rather than -1: a number meaning "no number" is one a caller
            // compares against a threshold.
            assert!(decided.score.is_none());
            assert!(
                !decided.chain.is_empty(),
                "a tier with no chain cannot answer"
            );
            for attempt in &decided.chain {
                assert!(!attempt.model.is_empty());
                assert!(["local", "remote"].contains(&attempt.backend.as_str()));
            }

            unsafe { crate::ffi::wattrouter_free(router) };
        });
    }

    #[test]
    fn a_request_that_is_not_one_does_not_decide() {
        // Reported as an error envelope by the entry point above. Here it is the
        // absence that entry point turns into one.
        crate::testenv::with_env(&[("NEURALWATT_API_KEY", Some("jni-test"))], || {
            let router = unsafe { crate::ffi::wattrouter_new(std::ptr::null()) };
            assert!(super::decide(unsafe { &*router }, "not json", "").is_none());
            unsafe { crate::ffi::wattrouter_free(router) };
        });
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
