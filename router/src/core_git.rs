//! `core_git.rs`: the git operations, as an envelope a model can read.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!   2026-08-09  A. Sigdel  Took in init, so a phone can make a repository.
//!   2026-08-10  A. Sigdel  Answers JSON rather than C strings with #565. The
//!                          name is the last of the ABI here and goes with the
//!                          others.
//!   2026-08-11  A. Sigdel  Corrected the five doc comments still describing a
//!                          returned pointer, a null and a safety contract.
//!                          #565 removed all three and left the prose claiming
//!                          them, which the paragraph below already denies.
//!
//! Contents
//!   `init`    Make a directory into a repository.
//!   `head`    Where `HEAD` points.
//!   `status`  The working tree, against the index and the head.
//!   `add`     Staging paths.
//!   `commit`  Writing what is staged.
//!
//! Separate from `core.rs` because what these answer is a different shape, not
//! because that file was full. A decision is three fields; a status is a list of
//! paths, and lists are what the envelope is for.
//!
//! One envelope for every call here, `{"ok": …}` or `{"error": "…"}`, so a caller
//! writes the unwrapping once. The refusals in [`crate::git`] are written for the
//! model to act on, and a boolean return would discard them at the one place they
//! cannot be recovered.
//!
//! **The no-answer case is gone, and that is a real change.** Null used to mean a
//! path that was null or not UTF-8. These take a `&Path` and a `&str`, so neither
//! is representable; `jni_git` rejected both before calling anyway, which is what
//! made the arm safe to remove rather than merely tidy. Every remaining outcome
//! is an envelope the model reads.
//!
//! Nothing here guards a panic. There is no boundary in this file to guard, and
//! `jni_git` catches its own.

use crate::answer::{refused, rendered};
use crate::git;
use std::path::Path;

/// Make a directory into a repository, or say it already was one.
///
/// # Returns
/// `ok` with a `kind` of `created` or `already_there`, or `error` with the
/// reason.
///
/// The two successes are named separately on purpose. `git init` is idempotent
/// and this could have answered one success for both; a model that cannot tell
/// "made you one" from "there already was one" reports having started work it
/// is in the middle of.
pub(crate) fn init(path: &Path) -> String {
    rendered(git::init(path))
}

/// Where `HEAD` points: a branch, a commit, or a branch with no commits yet.
///
/// # Returns
/// `ok` with a `kind` of `branch`, `detached` or `unborn`, or `error` with the
/// reason.
pub(crate) fn head(path: &Path) -> String {
    rendered(git::open(path).and_then(|repo| git::head(&repo)))
}

/// The working tree, against the index and the head.
///
/// # Returns
/// `ok` with the head, `staged` and `unstaged` as `{path, kind}`, and
/// `untracked` and `conflicted` as paths, or `error` with the reason.
pub(crate) fn status(path: &Path) -> String {
    rendered(git::status(path))
}

/// Stage paths, and answer with the status that results.
///
/// # Arguments
/// * `paths_json`: a JSON array of strings relative to the repository root,
///   WHERE a directory stages what is under it. JSON rather than a C array
///   because the model writes these as JSON and the tool decodes them there;
///   rebuilding that as `char **` only to parse it back is three shapes for one
///   value.
///
/// # Returns
/// `ok` with the status after staging, or `error`, which names the missing path
/// IF one is missing, so a model that misspelt one of four is told which.
pub(crate) fn add(path: &Path, paths_json: &str) -> String {
    match serde_json::from_str::<Vec<String>>(paths_json) {
        Ok(paths) => rendered(git::add(path, &paths)),
        Err(_) => {
            refused("paths must be a JSON array of strings, such as [\"src/main.rs\", \"docs\"]")
        }
    }
}

/// Commit what is staged.
///
/// # Returns
/// `ok` with the short id of the commit written, or `error`. Committing nothing
/// is an error: libgit2 writes a commit whose tree matches its parent without
/// complaint, and a model doing that in a loop believes it is making progress
/// while producing a history of identical trees.
pub(crate) fn commit(path: &Path, message: &str) -> String {
    rendered(git::commit(path, message))
}

#[cfg(test)]
mod tests {
    use crate::testenv::Scratch;
    use serde_json::Value;
    use std::path::Path;

    /// Decode what an entry point answered.
    ///
    /// This used to build a `CString`, call through a function pointer, read the
    /// result back and free it. All four steps existed because the answer was a
    /// C string; none of them tested anything about git.
    fn answer(entry: fn(&Path) -> String, path: &Path) -> Value {
        serde_json::from_str(&entry(path)).expect("the envelope is JSON")
    }

    /// The same, for an entry point taking a second argument.
    fn answer2(entry: fn(&Path, &str) -> String, path: &Path, second: &str) -> Value {
        serde_json::from_str(&entry(path, second)).expect("the envelope is JSON")
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
        let scratch = Scratch::new("git-unborn");
        git2::Repository::init(scratch.path()).unwrap();

        let head = answer(super::head, scratch.path());
        assert_eq!(head["ok"]["kind"], "unborn", "crossed as {head}");
        assert!(head["ok"]["name"].is_string());
    }

    #[test]
    fn a_status_crosses_with_each_of_its_lists_whole() {
        let scratch = Scratch::new("git-status");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        std::fs::write(scratch.path().join("staged.txt"), "one").unwrap();
        std::fs::write(scratch.path().join("loose.txt"), "two").unwrap();

        let mut index = repo.index().unwrap();
        index.add_path(Path::new("staged.txt")).unwrap();
        index.write().unwrap();

        // Whole lists rather than lengths: a crossing that dropped the path or the
        // kind would still count one entry and pass.
        let status = answer(super::status, scratch.path());
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
        let (scratch, _repo) = repository("git-commit");
        std::fs::write(scratch.path().join("a.txt"), "one").unwrap();

        let staged = answer2(super::add, scratch.path(), r#"["a.txt"]"#);
        assert_eq!(
            staged["ok"]["staged"],
            serde_json::json!([{"path": "a.txt", "kind": "added"}]),
            "crossed as {staged}"
        );

        let written = answer2(super::commit, scratch.path(), "a commit");
        let id = written["ok"].as_str().unwrap_or_default();
        assert!(!id.is_empty(), "no commit id came back: {written}");

        // The repository agrees, so the id is a commit and not a rendered string.
        let head = answer(super::head, scratch.path());
        assert_eq!(head["ok"]["kind"], "branch", "crossed as {head}");
    }

    #[test]
    fn staging_something_absent_names_that_path_and_not_the_others() {
        // The library's own message is about an unspecified pathspec, which a model
        // holding three paths cannot act on.
        let (scratch, _repo) = repository("git-add-missing");
        std::fs::write(scratch.path().join("here.txt"), "one").unwrap();

        let refusal = answer2(super::add, scratch.path(), r#"["here.txt", "gone.txt"]"#);
        let text = refusal["error"].as_str().unwrap_or_default();
        assert!(text.contains("gone.txt"), "did not name it: {refusal}");
        assert!(!text.contains("here.txt"), "named the wrong one: {refusal}");
    }

    #[test]
    fn committing_nothing_is_refused_rather_than_written() {
        // libgit2 writes a commit whose tree matches its parent without complaint,
        // and a model doing that in a loop believes it is making progress.
        let (scratch, _repo) = repository("git-commit-nothing");

        let refusal = answer2(super::commit, scratch.path(), "nothing");
        let text = refusal["error"].as_str().unwrap_or_default();
        assert!(text.contains("staged"), "refused unhelpfully: {refusal}");
    }

    #[test]
    fn paths_that_are_not_a_json_array_say_what_was_expected() {
        // Not a git failure, so it must not read as one: a model told "git:"
        // goes looking at the repository rather than at what it wrote.
        let (scratch, _repo) = repository("git-add-bad-json");

        let refusal = answer2(super::add, scratch.path(), "a.txt");
        let text = refusal["error"].as_str().unwrap_or_default();
        assert!(text.contains("JSON array"), "unhelpful: {refusal}");
    }

    #[test]
    fn a_refusal_crosses_as_the_message_rather_than_as_an_absence() {
        // The message is what the model acts on. Null here would leave the caller
        // to invent one, and the invented one would not name the path.
        let scratch = Scratch::new("git-not-a-repo");

        for entry in [super::head, super::status] {
            let refusal = answer(entry, scratch.path());
            let text = refusal["error"].as_str().unwrap_or_default();
            assert!(
                text.contains(&scratch.path().display().to_string()),
                "the refusal did not say where it looked: {refusal}"
            );
        }
    }
}
