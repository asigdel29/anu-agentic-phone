//! git.rs — git operations, without a subprocess.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!
//! Contents
//!   `Error`  Why an operation could not be done.
//!   `Head`   Which branch, or which commit, or neither.
//!   `head`   Opening a repository, and reading where it is.
//!
//! Everything on the board shells out to `git`. A phone has no shell, so these
//! come from libgit2 — reached as a Rust dependency of this crate, which already
//! crosses to both iOS slices. `docs/decisions/git-without-a-subprocess.md` has
//! why, and what libgit2 does not do.
//!
//! The operations live here rather than behind the FFI so `cargo test` can drive
//! them against a real repository it creates and commits to with the same
//! library. Nothing about that needs a simulator, and CI runs it on every change.
//!
//! Two states are not errors and read exactly like them, which is why this is
//! the first thing written. A freshly initialised repository has a `HEAD`
//! pointing at a branch that does not exist yet, and `Repository::head()` fails
//! on it — reported as a failure, that is wrong about the moment the agent is
//! most likely to be looking. And a detached `HEAD` has no branch at all, so
//! naming one is a lie the model would act on.

use serde::Serialize;
use std::path::Path;

/// Why an operation could not be done.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// No repository at that path, or it could not be opened.
    #[error("no git repository at {path}: {detail}")]
    NotARepository {
        /// Where it looked. A model told only "not a repository" tries the same
        /// path again.
        path: String,
        /// What the library said.
        detail: String,
    },
    /// The library refused for some other reason.
    #[error("git: {0}")]
    Refused(String),
}

/// Where `HEAD` points.
#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Head {
    /// On a branch, with at least one commit.
    Branch {
        /// The branch, as `git status` would print it rather than as a ref path.
        name: String,
    },
    /// On a commit rather than a branch. Named by its short id, because there is
    /// no branch to name and inventing one is worse than saying so.
    Detached {
        /// The commit, abbreviated as git would abbreviate it.
        commit: String,
    },
    /// On a branch that does not exist yet: what `git init` leaves behind, and
    /// the state the agent is most likely to find on a repository it just made.
    Unborn {
        /// The branch the first commit will create.
        name: String,
    },
}

/// Open a repository.
///
/// # Errors
/// [`Error::NotARepository`] IF nothing at `path` can be opened as one. The
/// message carries the path, because a model told only "not a repository" tries
/// the same path again.
pub fn open(path: &Path) -> Result<git2::Repository, Error> {
    git2::Repository::open(path).map_err(|why| Error::NotARepository {
        path: path.display().to_string(),
        detail: why.message().to_owned(),
    })
}

/// Where `HEAD` points, with the two states that are not failures told apart.
///
/// # Errors
/// [`Error::Refused`] IF the reference exists and cannot be read, which is a
/// fault rather than a state.
pub fn head(repo: &git2::Repository) -> Result<Head, Error> {
    match repo.head() {
        Ok(reference) => {
            // Checked before the shorthand: a detached `HEAD` still yields a
            // reference, and its shorthand is "HEAD", which reads as a branch
            // called HEAD rather than as no branch at all.
            if repo.head_detached().unwrap_or(false) {
                return Ok(Head::Detached {
                    commit: short_id(&reference)?,
                });
            }
            Ok(Head::Branch {
                name: reference.shorthand().unwrap_or("HEAD").to_owned(),
            })
        }

        // The one failure that is a state rather than a fault.
        Err(why) if why.code() == git2::ErrorCode::UnbornBranch => Ok(Head::Unborn {
            name: unborn_name(repo),
        }),
        Err(why) => Err(why.into()),
    }
}

/// The commit a reference is on, abbreviated as git would.
fn short_id(reference: &git2::Reference) -> Result<String, Error> {
    let commit = reference.peel_to_commit()?;
    let short = commit.as_object().short_id()?;
    Ok(short.as_str().unwrap_or_default().to_owned())
}

/// The branch an unborn `HEAD` is waiting to become.
///
/// It is a symbolic reference to something that does not exist, so the name has
/// to come out of the target rather than out of a resolved reference. Falls back
/// to "HEAD" rather than failing: the branch name is the least important thing
/// about a repository with no commits in it.
fn unborn_name(repo: &git2::Repository) -> String {
    repo.find_reference("HEAD")
        .ok()
        .and_then(|head| head.symbolic_target().map(str::to_owned))
        .map_or_else(
            || "HEAD".to_owned(),
            |target| target.trim_start_matches("refs/heads/").to_owned(),
        )
}

/// Anything the library refused that is not a state this models.
///
/// A conversion rather than a helper, so the call sites are `?` and a new one
/// cannot forget to wrap.
impl From<git2::Error> for Error {
    fn from(why: git2::Error) -> Self {
        Self::Refused(why.message().to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A directory that removes itself, so a failing case leaves nothing behind.
    ///
    /// Named per case as well as per process: the lib tests share one process and
    /// run in parallel, so a shared path would have two repositories in it.
    struct Scratch(std::path::PathBuf);

    impl Scratch {
        fn new(name: &str) -> Self {
            let path =
                std::env::temp_dir().join(format!("wattrouter-git-{}-{name}", std::process::id()));
            let _ = std::fs::remove_dir_all(&path);
            std::fs::create_dir_all(&path).expect("could not make a scratch directory");
            Self(path)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// Commit whatever is in the index, with no parent unless there is one.
    fn commit(repo: &git2::Repository) -> git2::Oid {
        let who = git2::Signature::now("Test", "test@example.com").unwrap();
        let tree = {
            let mut index = repo.index().unwrap();
            let oid = index.write_tree().unwrap();
            repo.find_tree(oid).unwrap()
        };
        let parents: Vec<git2::Commit> = repo
            .head()
            .ok()
            .and_then(|head| head.peel_to_commit().ok())
            .into_iter()
            .collect();
        let borrowed: Vec<&git2::Commit> = parents.iter().collect();
        repo.commit(Some("HEAD"), &who, &who, "a commit", &tree, &borrowed)
            .unwrap()
    }

    #[test]
    fn opening_something_that_is_not_a_repository_names_the_path() {
        // A model told only "not a repository" tries the same path again.
        let scratch = Scratch::new("not-a-repo");
        // `unwrap_err` needs the success type to be Debug, and a Repository is
        // not one.
        let Err(why) = open(scratch.path()) else {
            panic!("opened something that is not a repository")
        };

        assert!(
            why.to_string()
                .contains(&scratch.path().display().to_string()),
            "the refusal did not say where it looked: {why}"
        );
    }

    #[test]
    fn a_fresh_repository_is_unborn_rather_than_broken() {
        // `Repository::head()` fails here, and reporting that as a failure would
        // be wrong about the moment the agent is most likely to be looking.
        let scratch = Scratch::new("unborn");
        let repo = git2::Repository::init(scratch.path()).unwrap();

        match head(&repo).unwrap() {
            Head::Unborn { name } => {
                assert!(!name.is_empty());
                assert!(
                    !name.starts_with("refs/"),
                    "the whole ref path leaked out: {name}"
                );
            }
            other => panic!("a repository with no commits read as {other:?}"),
        }
    }

    #[test]
    fn a_repository_with_a_commit_is_on_its_branch() {
        let scratch = Scratch::new("on-a-branch");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        commit(&repo);

        match head(&repo).unwrap() {
            Head::Branch { name } => assert!(!name.is_empty() && !name.starts_with("refs/")),
            other => panic!("a committed repository read as {other:?}"),
        }
    }

    #[test]
    fn a_detached_head_names_the_commit_rather_than_a_branch() {
        // A detached HEAD still yields a reference whose shorthand is "HEAD",
        // which reads as a branch called HEAD rather than as no branch at all.
        let scratch = Scratch::new("detached");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        let oid = commit(&repo);
        repo.set_head_detached(oid).unwrap();

        match head(&repo).unwrap() {
            Head::Detached { commit } => {
                assert_ne!(commit, "HEAD", "a detached head reported a branch");
                assert!(
                    oid.to_string().starts_with(&commit),
                    "{commit} is not the start of {oid}"
                );
            }
            other => panic!("a detached head read as {other:?}"),
        }
    }
}
