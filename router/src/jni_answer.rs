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
//! `ffi_answer.rs` beside this is the same module for the C ABI, and it exists
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
//! way `ffi_answer` is gated on either alone.
//!
//! `jni.rs` does not use this and that is deliberate. It answers with a
//! `Decided` it serialises itself rather than with a C string somebody else
//! allocated, and its `read` already does what `owned` does.
//!
//! `guarded` arrived after the other two, in #482. It was the third rule those
//! files did not follow and it was left out of #469 because wrapping eight
//! bodies in a closure re-indents both, which put that change over the limit.

use jni::JNIEnv;
use jni::objects::JString;
use jni::sys::jstring;
use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};

/// A Java string as a C string.
///
/// # Returns
/// `None` IF the reference was null, IF the JVM would not give the text back,
/// or IF the text contains an interior NUL and so cannot cross as a C string.
///
/// # Rely
/// Clears any pending exception before returning `None`. Leaving one set is not
/// a tidiness question: the caller goes on to read its other arguments and to
/// allocate a string, and the specification forbids both while an exception is
/// pending. `jni.rs`'s `read` makes the same guarantee for the same reason.
///
/// The reachable failure — a null reference — is refused by the crate before
/// the JVM is asked, so it raises nothing and there is nothing to clear. The
/// clear is for the arm where the JVM itself refuses, which no test can reach.
pub(crate) fn owned(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<CString> {
    // Nullable in Kotlin compiles to a null jstring without complaint, and
    // `get_string` on one raises rather than answering.
    if value.is_null() {
        return None;
    }

    let Ok(read) = env.get_string(value) else {
        // Discarded deliberately: there is nothing to do about a failed clear,
        // and the caller is already returning the absence.
        let _ = env.exception_clear();
        return None;
    };

    // `into` rather than `to_string_lossy`, which is what the two copies of
    // this did. `JavaStr` derefs through `JNIStr` to `CStr`, so
    // `to_string_lossy` reads the bytes as standard UTF-8 — and a JVM is
    // permitted to write modified UTF-8, where a supplementary character is a
    // surrogate pair that decode replaces with U+FFFD.
    //
    // Measured before changing it: ART does not exercise that permission, and
    // an emoji survived the old route intact on a device. So this is not a
    // corruption being fixed, it is one runtime's behaviour being stopped from
    // being load-bearing. The conversion below tries modified UTF-8 and falls
    // back to the standard kind, so it is right under either.
    let text: String = read.into();
    CString::new(text).ok()
}

/// Hand an envelope to Kotlin and free the Rust copy.
///
/// # Returns
/// The envelope as a `jstring`, or null IF `raw` was null or the JVM would not
/// allocate. A JVM that will not allocate a string is out of memory and there is
/// nothing useful to say to it.
///
/// # Rely
/// Frees `raw` on every path, so no caller keeps it. A Kotlin `String` is a copy
/// by the time `new_string` returns, and leaving the Rust allocation would leak
/// once per call.
pub(crate) fn answered(env: &mut JNIEnv<'_>, raw: *mut c_char) -> jstring {
    if raw.is_null() {
        return std::ptr::null_mut();
    }
    let read = unsafe { CStr::from_ptr(raw) }
        .to_str()
        .ok()
        .and_then(|json| env.new_string(json).ok());

    // Before returning, and whether or not the conversion worked.
    unsafe { crate::ffi_answer::wattrouter_string_free(raw) };

    read.map_or(std::ptr::null_mut(), jni::objects::JString::into_raw)
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
