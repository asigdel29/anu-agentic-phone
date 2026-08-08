//! `ffi_git.rs` — the git operations, across the C ABI.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!
//! Contents
//!   `wattrouter_git_head`     Where `HEAD` points.
//!   `wattrouter_string_free`  Release what an entry point here returned.
//!
//! Separate from `ffi.rs` because what crosses is a different shape, not because
//! that file was full. A decision is three fixed-width fields and crosses by
//! value, so the caller frees nothing; a status is a list of paths and cannot, so
//! these allocate and the caller hands the allocation back.
//!
//! One envelope for every call here, `{"ok": …}` or `{"error": "…"}`, so a caller
//! writes the unwrapping once. The refusals in [`crate::git`] are written for the
//! model to act on, and a boolean return would discard them at the one boundary
//! where they cannot be recovered. Null is kept for the case with no answer at
//! all: a path that is null or not UTF-8, or the panic guard firing.
//!
//! No panic may cross this boundary — that is undefined behaviour, so every entry
//! point catches and reports failure as a value, as `ffi.rs` does.

use crate::git;
use serde::Serialize;
use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;

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
///
/// Returns null IF the answer could not be rendered, which needs a serialisation
/// failure or an interior NUL — neither reachable from what [`crate::git`]
/// returns. Written as a value rather than an `expect`, which would abort the
/// host application over an arm nothing reaches.
fn rendered<T: Serialize>(outcome: Result<T, git::Error>) -> *mut c_char {
    let answer = match outcome {
        Ok(value) => Answer::Ok(value),
        Err(why) => Answer::Error(why.to_string()),
    };
    serde_json::to_string(&answer)
        .ok()
        .and_then(|json| CString::new(json).ok())
        .map_or(std::ptr::null_mut(), CString::into_raw)
}

/// Run `body`, turning a panic into null rather than into undefined behaviour.
fn guarded(body: impl FnOnce() -> *mut c_char) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(std::ptr::null_mut())
}

/// The path a caller named, or `None` IF it is null or not UTF-8.
///
/// # Safety
/// `text` must be null or a valid NUL-terminated string outliving the call.
unsafe fn borrowed_path<'a>(text: *const c_char) -> Option<&'a Path> {
    if text.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(text) }.to_str().ok().map(Path::new)
}

/// Where `HEAD` points: a branch, a commit, or a branch with no commits yet.
///
/// # Returns
/// An owned JSON string to pass to [`wattrouter_string_free`], carrying `ok` with
/// a `kind` of `branch`, `detached` or `unborn`, or `error` with the reason.
/// Null IF `path` was null or not UTF-8, or the call panicked.
///
/// # Safety
/// `path` must be null or a valid NUL-terminated string outliving the call. The
/// returned pointer must be released with [`wattrouter_string_free`] and not with
/// `free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_git_head(path: *const c_char) -> *mut c_char {
    guarded(|| match unsafe { borrowed_path(path) } {
        None => std::ptr::null_mut(),
        Some(path) => rendered(git::open(path).and_then(|repo| git::head(&repo))),
    })
}

/// Release a string this module returned.
///
/// # Safety
/// `text` must come from an entry point in this module and not already be freed.
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

#[cfg(test)]
mod tests {
    use crate::testenv::Scratch;
    use serde_json::Value;
    use std::ffi::{CStr, CString, c_char};
    use std::path::Path;

    /// Call an entry point the way C does, freeing the answer here so that a case
    /// failing its assertion still gives the allocation back.
    fn answer(entry: unsafe extern "C" fn(*const c_char) -> *mut c_char, path: &Path) -> Value {
        let argument = CString::new(path.to_str().expect("a UTF-8 scratch path")).unwrap();
        let returned = unsafe { entry(argument.as_ptr()) };
        assert!(!returned.is_null(), "the call produced no answer at all");

        let json = unsafe { CStr::from_ptr(returned) }
            .to_str()
            .expect("the envelope is UTF-8")
            .to_owned();
        unsafe { super::wattrouter_string_free(returned) };

        serde_json::from_str(&json).expect("the envelope is JSON")
    }

    #[test]
    fn a_fresh_repository_crosses_as_unborn_rather_than_as_a_failure() {
        // The state an agent most often finds, and the one libgit2 calls an error.
        let scratch = Scratch::new("ffi-git-unborn");
        git2::Repository::init(scratch.path()).unwrap();

        let head = answer(super::wattrouter_git_head, scratch.path());
        assert_eq!(head["ok"]["kind"], "unborn", "crossed as {head}");
        assert!(head["ok"]["name"].is_string());
    }

    #[test]
    fn a_refusal_crosses_as_the_message_rather_than_as_an_absence() {
        // The message is what the model acts on. Null here would leave the caller
        // to invent one, and the invented one would not name the path.
        let scratch = Scratch::new("ffi-git-not-a-repo");

        let refusal = answer(super::wattrouter_git_head, scratch.path());
        let text = refusal["error"].as_str().unwrap_or_default();
        assert!(
            text.contains(&scratch.path().display().to_string()),
            "the refusal did not say where it looked: {refusal}"
        );
    }

    #[test]
    fn hostile_input_returns_a_value_rather_than_unwinding() {
        // A panic across the boundary is undefined behaviour, and so is a bad free.
        assert!(unsafe { super::wattrouter_git_head(std::ptr::null()) }.is_null());
        unsafe { super::wattrouter_string_free(std::ptr::null_mut()) };
    }
}
