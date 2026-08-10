//! `ffi_git.rs` — the git operations, across the C ABI.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!   2026-08-09  A. Sigdel  Took in init, so a phone can make a repository.
//!
//! Contents
//!   `wattrouter_git_init`     Make a directory into a repository.
//!   `wattrouter_git_head`     Where `HEAD` points.
//!   `wattrouter_git_status`   The working tree, against the index and the head.
//!   `wattrouter_git_add`      Staging paths.
//!   `wattrouter_git_commit`   Writing what is staged.
//!
//! The envelope, the panic guard and `wattrouter_string_free` moved to
//! `ffi_answer.rs` when memory became the second caller of them.
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

use crate::ffi_answer::{borrowed, guarded, refused, rendered, unusable};
use crate::git;
use std::ffi::c_char;
use std::path::Path;

/// Make a directory into a repository, or say it already was one.
///
/// # Returns
/// An owned JSON string to pass to [`wattrouter_string_free`], carrying `ok`
/// with a `kind` of `created` or `already_there`, or `error` with the reason.
/// Null IF `path` was null or not UTF-8, or the call panicked.
///
/// The two successes are named separately on purpose. `git init` is idempotent
/// and this could have answered one success for both; a model that cannot tell
/// "made you one" from "there already was one" reports having started work it
/// is in the middle of.
///
/// # Safety
/// `path` must be null or a valid NUL-terminated string outliving the call. The
/// returned pointer must be released with [`wattrouter_string_free`] and not
/// with `free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_git_init(path: *const c_char) -> *mut c_char {
    guarded(|| match unsafe { borrowed(path) } {
        None => unusable(),
        Some(path) => rendered(git::init(Path::new(path))),
    })
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
    guarded(|| match unsafe { borrowed(path) } {
        None => unusable(),
        Some(path) => rendered(git::open(Path::new(path)).and_then(|repo| git::head(&repo))),
    })
}

/// The working tree, against the index and the head.
///
/// # Returns
/// An owned JSON string to pass to [`wattrouter_string_free`], carrying `ok` with
/// the head, `staged` and `unstaged` as `{path, kind}`, and `untracked` and
/// `conflicted` as paths — or `error` with the reason. Null on the terms
/// [`wattrouter_git_head`] states.
///
/// # Safety
/// As [`wattrouter_git_head`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_git_status(path: *const c_char) -> *mut c_char {
    guarded(|| match unsafe { borrowed(path) } {
        None => unusable(),
        Some(path) => rendered(git::status(Path::new(path))),
    })
}

/// Stage paths, and answer with the status that results.
///
/// # Arguments
/// * `paths_json` — a JSON array of strings relative to the repository root,
///   WHERE a directory stages what is under it. JSON rather than a C array
///   because the model writes these as JSON and the tool decodes them there;
///   rebuilding that as `char **` only to parse it back is three shapes for one
///   value.
///
/// # Returns
/// An owned JSON string to pass to [`wattrouter_string_free`], carrying `ok` with
/// the status after staging, or `error` — which names the missing path IF one is
/// missing, so a model that misspelt one of four is told which. Null on the terms
/// [`wattrouter_git_head`] states.
///
/// # Safety
/// As [`wattrouter_git_head`], for both arguments.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_git_add(
    path: *const c_char,
    paths_json: *const c_char,
) -> *mut c_char {
    guarded(|| {
        let (Some(path), Some(paths_json)) =
            (unsafe { borrowed(path) }, unsafe { borrowed(paths_json) })
        else {
            return unusable();
        };
        match serde_json::from_str::<Vec<String>>(paths_json) {
            Ok(paths) => rendered(git::add(Path::new(path), &paths)),
            Err(_) => refused(
                "paths must be a JSON array of strings, such as [\"src/main.rs\", \"docs\"]",
            ),
        }
    })
}

/// Commit what is staged.
///
/// # Returns
/// An owned JSON string to pass to [`wattrouter_string_free`], carrying `ok` with
/// the short id of the commit written, or `error`. Committing nothing is an
/// error: libgit2 writes a commit whose tree matches its parent without
/// complaint, and a model doing that in a loop believes it is making progress
/// while producing a history of identical trees. Null on the terms
/// [`wattrouter_git_head`] states.
///
/// # Safety
/// As [`wattrouter_git_head`], for both arguments.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_git_commit(
    path: *const c_char,
    message: *const c_char,
) -> *mut c_char {
    guarded(|| {
        let (Some(path), Some(message)) = (unsafe { borrowed(path) }, unsafe { borrowed(message) })
        else {
            return unusable();
        };
        rendered(git::commit(Path::new(path), message))
    })
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
        unsafe { crate::ffi_answer::wattrouter_string_free(returned) };

        serde_json::from_str(&json).expect("the envelope is JSON")
    }

    /// The same, for an entry point taking a second string.
    fn answer2(
        entry: unsafe extern "C" fn(*const c_char, *const c_char) -> *mut c_char,
        path: &Path,
        second: &str,
    ) -> Value {
        let first = CString::new(path.to_str().expect("a UTF-8 scratch path")).unwrap();
        let second = CString::new(second).unwrap();
        let returned = unsafe { entry(first.as_ptr(), second.as_ptr()) };
        assert!(!returned.is_null(), "the call produced no answer at all");

        let json = unsafe { CStr::from_ptr(returned) }
            .to_str()
            .expect("the envelope is UTF-8")
            .to_owned();
        unsafe { crate::ffi_answer::wattrouter_string_free(returned) };

        serde_json::from_str(&json).expect("the envelope is JSON")
    }

    /// A repository with an identity to sign with, as a phone would have to have.
    fn repository(name: &str) -> (Scratch, git2::Repository) {
        let scratch = Scratch::new(name);
        let repo = git2::Repository::init(scratch.path()).unwrap();
        let mut config = repo.config().unwrap();
        config.set_str("user.name", "Test").unwrap();
        config.set_str("user.email", "test@example.com").unwrap();
        (scratch, repo)
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
    fn a_status_crosses_with_each_of_its_lists_whole() {
        let scratch = Scratch::new("ffi-git-status");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        std::fs::write(scratch.path().join("staged.txt"), "one").unwrap();
        std::fs::write(scratch.path().join("loose.txt"), "two").unwrap();

        let mut index = repo.index().unwrap();
        index.add_path(Path::new("staged.txt")).unwrap();
        index.write().unwrap();

        // Whole lists rather than lengths: a crossing that dropped the path or the
        // kind would still count one entry and pass.
        let status = answer(super::wattrouter_git_status, scratch.path());
        assert_eq!(
            status["ok"]["staged"],
            serde_json::json!([{"path": "staged.txt", "kind": "added"}]),
            "crossed as {status}"
        );
        assert_eq!(status["ok"]["untracked"], serde_json::json!(["loose.txt"]));
        assert_eq!(status["ok"]["conflicted"], serde_json::json!([]));
    }

    #[test]
    fn staging_then_committing_crosses_as_a_commit() {
        let (scratch, _repo) = repository("ffi-git-commit");
        std::fs::write(scratch.path().join("a.txt"), "one").unwrap();

        let staged = answer2(super::wattrouter_git_add, scratch.path(), r#"["a.txt"]"#);
        assert_eq!(
            staged["ok"]["staged"],
            serde_json::json!([{"path": "a.txt", "kind": "added"}]),
            "crossed as {staged}"
        );

        let written = answer2(super::wattrouter_git_commit, scratch.path(), "a commit");
        let id = written["ok"].as_str().unwrap_or_default();
        assert!(!id.is_empty(), "no commit id came back: {written}");

        // The repository agrees, so the id is a commit and not a rendered string.
        let head = answer(super::wattrouter_git_head, scratch.path());
        assert_eq!(head["ok"]["kind"], "branch", "crossed as {head}");
    }

    #[test]
    fn staging_something_absent_names_that_path_and_not_the_others() {
        // The library's own message is about an unspecified pathspec, which a model
        // holding three paths cannot act on.
        let (scratch, _repo) = repository("ffi-git-add-missing");
        std::fs::write(scratch.path().join("here.txt"), "one").unwrap();

        let refusal = answer2(
            super::wattrouter_git_add,
            scratch.path(),
            r#"["here.txt", "gone.txt"]"#,
        );
        let text = refusal["error"].as_str().unwrap_or_default();
        assert!(text.contains("gone.txt"), "did not name it: {refusal}");
        assert!(!text.contains("here.txt"), "named the wrong one: {refusal}");
    }

    #[test]
    fn committing_nothing_is_refused_rather_than_written() {
        // libgit2 writes a commit whose tree matches its parent without complaint,
        // and a model doing that in a loop believes it is making progress.
        let (scratch, _repo) = repository("ffi-git-commit-nothing");

        let refusal = answer2(super::wattrouter_git_commit, scratch.path(), "nothing");
        let text = refusal["error"].as_str().unwrap_or_default();
        assert!(text.contains("staged"), "refused unhelpfully: {refusal}");
    }

    #[test]
    fn paths_that_are_not_a_json_array_say_what_was_expected() {
        // Not a git failure, so it must not read as one — a model told "git:"
        // goes looking at the repository rather than at what it wrote.
        let (scratch, _repo) = repository("ffi-git-add-bad-json");

        let refusal = answer2(super::wattrouter_git_add, scratch.path(), "a.txt");
        let text = refusal["error"].as_str().unwrap_or_default();
        assert!(text.contains("JSON array"), "unhelpful: {refusal}");
    }

    #[test]
    fn a_refusal_crosses_as_the_message_rather_than_as_an_absence() {
        // The message is what the model acts on. Null here would leave the caller
        // to invent one, and the invented one would not name the path.
        let scratch = Scratch::new("ffi-git-not-a-repo");

        for entry in [super::wattrouter_git_head, super::wattrouter_git_status] {
            let refusal = answer(entry, scratch.path());
            let text = refusal["error"].as_str().unwrap_or_default();
            assert!(
                text.contains(&scratch.path().display().to_string()),
                "the refusal did not say where it looked: {refusal}"
            );
        }
    }

    #[test]
    fn hostile_input_returns_a_value_rather_than_unwinding() {
        // A panic across the boundary is undefined behaviour, and so is a bad free.
        for entry in [super::wattrouter_git_head, super::wattrouter_git_status] {
            assert!(unsafe { entry(std::ptr::null()) }.is_null());
        }
        for entry in [super::wattrouter_git_add, super::wattrouter_git_commit] {
            let path = CString::new("/nowhere").unwrap();
            assert!(unsafe { entry(std::ptr::null(), path.as_ptr()) }.is_null());
            assert!(unsafe { entry(path.as_ptr(), std::ptr::null()) }.is_null());
        }
        unsafe { crate::ffi_answer::wattrouter_string_free(std::ptr::null_mut()) };
    }
}
