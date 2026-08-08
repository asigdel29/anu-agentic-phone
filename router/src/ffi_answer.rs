//! `ffi_answer.rs` — the envelope every allocating entry point answers with.
//!
//! History
//!   2026-08-08  A. Sigdel  Created, from ffi_git.rs, when memory became the
//!                          second caller.
//!
//! Contents
//!   `Answer`                  Ok, or a refusal in words the model reads.
//!   `rendered`, `refused`     Building one.
//!   `guarded`, `borrowed`     The panic guard and a borrowed C string.
//!   `wattrouter_string_free`  Release what any of them returned.
//!
//! This lived in `ffi_git.rs` because git was the first entry point that had to
//! allocate and there was nothing to share it with. Memory is the second.
//!
//! Duplicating it would be two envelopes that agree today, and a Swift side
//! unwrapping two shapes the day one changes. Reaching into `ffi_git` from
//! `ffi_memory` would make the memory feature require the git feature, which is
//! untrue and would put libgit2 into a build that wanted a database. So it is
//! here, gated on either feature being on.
//!
//! `ffi.rs` returns by value and frees nothing, which is right for three
//! fixed-width fields and wrong for anything with a list in it. That is the line
//! this module is on the other side of.

#![allow(clippy::doc_markdown)]

use serde::Serialize;
use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};

/// What every entry point here answers with.
///
/// Externally tagged, which is serde's default and is also the point: the tag is
/// what a caller switches on, and a flattened shape would need a separate success
/// flag that could disagree with the payload.
#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
enum Answer<T: Serialize> {
    /// The operation's own result.
    Ok(T),
    /// Why it could not be done, in the words the model reads.
    Error(String),
}

/// Serialise an outcome into an owned C string.
pub(crate) fn rendered<T: Serialize, E: std::fmt::Display>(outcome: Result<T, E>) -> *mut c_char {
    emit(&match outcome {
        Ok(value) => Answer::Ok(value),
        Err(why) => Answer::Error(why.to_string()),
    })
}

/// A refusal this boundary produced rather than the module behind it.
///
/// An argument that will not decode is not a failure of the thing being asked
/// about, and dressing it as one sends the model looking in the wrong place. It
/// is still something the model can fix, so it crosses in the same envelope.
pub(crate) fn refused(why: &str) -> *mut c_char {
    emit(&Answer::<()>::Error(why.to_owned()))
}

/// Write an answer out as an owned C string.
///
/// Returns null IF it could not be rendered, which needs a serialisation failure
/// or an interior NUL — neither reachable from what the callers return. Written
/// as a value rather than an `expect`, which would abort the host application
/// over an arm nothing reaches.
fn emit<T: Serialize>(answer: &Answer<T>) -> *mut c_char {
    serde_json::to_string(answer)
        .ok()
        .and_then(|json| CString::new(json).ok())
        .map_or(std::ptr::null_mut(), CString::into_raw)
}

/// Run `body`, turning a panic into null rather than into undefined behaviour.
pub(crate) fn guarded(body: impl FnOnce() -> *mut c_char) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(std::ptr::null_mut())
}

/// What a caller passed, or `None` IF it is null or not UTF-8.
///
/// # Safety
/// `text` must be null or a valid NUL-terminated string outliving the call.
pub(crate) unsafe fn borrowed<'a>(text: *const c_char) -> Option<&'a str> {
    if text.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(text) }.to_str().ok()
}

/// Release a string any entry point in this crate returned.
///
/// # Safety
/// `text` must come from an entry point in this crate and not already be freed.
/// Null is accepted and ignored, so a caller that checked for it need not check
/// again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_string_free(text: *mut c_char) {
    if text.is_null() {
        return;
    }
    // Discarded deliberately: a caller freeing a string has nowhere to report a
    // failure to, and there is nothing it could do about one.
    let _ = catch_unwind(AssertUnwindSafe(|| {
        drop(unsafe { CString::from_raw(text) });
    }));
}
