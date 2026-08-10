//! `jni_answer.rs` — what the JNI bindings share across the boundary.
//!
//! History
//!   2026-08-09  A. Sigdel  Created, from `jni_git.rs` and `jni_memory.rs`,
//!                          which held identical copies of the first two.
//!
//! Contents
//!   `owned`     A Java string as a C string.
//!   `answered`  Hand an envelope to Kotlin and free the Rust copy.
//!   `guarded`   Turn a panic into a refusal rather than undefined behaviour.
//!
//! `answer.rs` beside this is the same module for the C ABI, and it exists
//! for the reason recorded in its header: two envelopes that agree today, and a
//! caller unwrapping two shapes the day one changes. The JNI half had that
//! duplication — `owned` and `answered` were byte-identical in two files — and
//! neither copy applied the three rules `jni.rs` states for this boundary.
//!
//! None of the three was a live failure, and #468 says so with the measurement.
//! They are rules the file next door follows and these two did not.
//!
//! Not a reach into `jni_git` from `jni_memory`, which would make the memory
//! feature require the git feature. Gated on `android` with either of them, the
//! way `answer` is gated on either alone.
//!
//! `jni.rs` does not use this and that is deliberate. It answers with a
//! `Decided` it serialises itself rather than with a C string somebody else
//! allocated, and its `read` already does what `owned` does.
//!
//! `guarded` arrived after the other two, in #482. It was the third rule those
//! files did not follow and it was left out of #469 because wrapping eight
//! bodies in a closure re-indents both, which put that change over the limit.

use jni::JNIEnv;
use jni::sys::jstring;
use std::panic::{AssertUnwindSafe, catch_unwind};

/// Hand JSON to Kotlin.
///
/// `answered` was the same idea over a `*mut c_char` that it also freed. Nothing
/// crossing owns a Rust allocation now, so this is that helper minus the free.
/// It is here rather than in either caller because both need it, and a second
/// copy is what #468 and #482 were about.
///
/// # Returns
/// Null IF the JVM would not allocate, which is an out-of-memory condition with
/// nothing useful to say to it.
pub(crate) fn handed(env: &mut JNIEnv<'_>, json: String) -> jstring {
    env.new_string(json)
        .map_or(std::ptr::null_mut(), jni::objects::JString::into_raw)
}

/// Run `body`, answering `refusal` rather than unwinding into the JVM.
///
/// # Arguments
/// * `refusal` — what the entry point answers on a panic, WHERE it is the value
///   that entry point already uses to mean it could not do the thing: a null
///   `jstring`, a zero handle, or nothing at all.
///
/// # Rely
/// A panic crossing into the JVM is undefined behaviour, exactly as into C.
/// `jni.rs` states that and guards all four of its own entry points; the C ABI
/// states it and satisfies it through `ffi_answer::guarded`. The eight here
/// were the only foreign entry points in the crate with no guard at all.
///
/// Takes the refusal rather than using `Default`, because the three return
/// types are `jstring`, `jlong` and `()`, and a raw pointer has no `Default`.
pub(crate) fn guarded<T>(refusal: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(refusal)
}
