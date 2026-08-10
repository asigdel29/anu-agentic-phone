//! `ffi_answer.rs` — the envelope every allocating entry point answers with.
//!
//! History
//!   2026-08-08  A. Sigdel  Created, from ffi_git.rs, when memory became the
//!                          second caller.
//!   2026-08-10  A. Sigdel  Builds JSON rather than C strings with #565. The
//!                          one place that still makes a `CString` is `guarded`,
//!                          and it makes it on the way out.
//!
//! Contents
//!   `Answer`                  Ok, or a refusal in words the model reads.
//!   `rendered`, `refused`     Building one, as JSON.
//!   `unusable`                No answer, for arguments that were not readable.
//!   `guarded`                 The panic guard, and the last `CString` here.
//!   `borrowed`                A borrowed C string.
//!   `wattrouter_string_free`  Release what any entry point returned.
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

/// Serialise an outcome into the JSON a caller reads.
pub(crate) fn rendered<T: Serialize, E: std::fmt::Display>(outcome: Result<T, E>) -> String {
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
pub(crate) fn refused(why: &str) -> String {
    emit(&Answer::<()>::Error(why.to_owned()))
}

/// Write an answer out as JSON.
///
/// A serialisation failure needs a type that cannot be represented, which none
/// of the callers return. Written as a value rather than an `expect`, which
/// would abort the host application over an arm nothing reaches: the empty
/// string is not valid JSON, so a caller decoding it fails where it can say so.
fn emit<T: Serialize>(answer: &Answer<T>) -> String {
    serde_json::to_string(answer).unwrap_or_default()
}

/// No answer at all: what a caller gets when what it passed was unusable.
///
/// Said as a string so it can leave the same closure as an answer, and turned
/// back into null by [`guarded`].
///
/// **Not the same as [`refused`], and the difference is load-bearing.** A refusal
/// is an envelope the model reads and can act on: the argument decoded and was
/// wrong. This is a path that was null or not valid UTF-8, which is a fault in
/// the caller rather than anything to tell a model about. `ffi_git`'s header
/// makes the same distinction and a test holds it.
pub(crate) fn unusable() -> String {
    String::new()
}

/// Run `body`, and hand what it answered back as the C string the entry points
/// still return. A panic becomes null rather than undefined behaviour.
///
/// The conversion is here rather than at each of the nine places that build an
/// answer, because this already wraps every one of them: `ffi_git` and
/// `ffi_memory` still return `*mut c_char`, so exactly one `CString` is made per
/// call and it is made in one place while that lasts. It goes when those two
/// stop returning pointers, which is #565's last envelope change.
///
/// Null for three things a caller cannot tell apart and need not: a panic, an
/// answer that would not serialise, and one containing an interior NUL. JSON
/// cannot contain a NUL and none of the callers return an unserialisable type,
/// so the empty string [`emit`] yields on that unreachable arm is what marks it.
pub(crate) fn guarded(body: impl FnOnce() -> String) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(body))
        .ok()
        .filter(|json| !json.is_empty())
        .and_then(|json| CString::new(json).ok())
        .map_or(std::ptr::null_mut(), CString::into_raw)
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
