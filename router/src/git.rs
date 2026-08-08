//! git.rs — git operations, without a subprocess.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!
//! Contents
//!   `Error`   Why an operation could not be done.
//!   `Head`    Which branch, or which commit, or neither.
//!   `head`    Opening a repository, and reading where it is.
//!   `Change`  One path, and what happened to it.
//!   `Status`  The working tree, against the index and the head.
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

/// What happened to one path.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Change {
    /// Relative to the repository root, as git reports it.
    pub path: String,
    /// `added`, `modified`, `deleted`, `renamed` or `typechange`.
    pub kind: &'static str,
}

/// The working tree, against the index and the head.
#[derive(Debug, Serialize, Default, PartialEq, Eq)]
pub struct Status {
    /// Where `HEAD` points.
    pub head: Option<Head>,
    /// The index against the head.
    pub staged: Vec<Change>,
    /// The working tree against the index.
    pub unstaged: Vec<Change>,
    /// Present and not in the index. Directories are listed as directories.
    pub untracked: Vec<String>,
    /// Listed on their own rather than among the changes. A conflicted path is
    /// not something to commit, and a model told it is "modified" commits it.
    pub conflicted: Vec<String>,
}

/// The index against the head, and what to call it.
const STAGED: [(git2::Status, &str); 5] = [
    (git2::Status::INDEX_NEW, "added"),
    (git2::Status::INDEX_MODIFIED, "modified"),
    (git2::Status::INDEX_DELETED, "deleted"),
    (git2::Status::INDEX_RENAMED, "renamed"),
    (git2::Status::INDEX_TYPECHANGE, "typechange"),
];

/// The working tree against the index. `WT_NEW` is absent deliberately: an
/// untracked file is reported as untracked rather than as an unstaged addition,
/// which is what git says and what a model expects to act on.
const UNSTAGED: [(git2::Status, &str); 4] = [
    (git2::Status::WT_MODIFIED, "modified"),
    (git2::Status::WT_DELETED, "deleted"),
    (git2::Status::WT_RENAMED, "renamed"),
    (git2::Status::WT_TYPECHANGE, "typechange"),
];

/// The working tree, against the index and the head.
///
/// # Errors
/// [`Error::NotARepository`] IF nothing at `path` can be opened as one, and
/// [`Error::Refused`] IF the tree cannot be walked.
pub fn status(path: &Path) -> Result<Status, Error> {
    let repo = open(path)?;

    let mut options = git2::StatusOptions::new();
    // Untracked directories are named rather than walked. A fresh clone of
    // anything large otherwise answers with every file in it, which is a context
    // window rather than an answer.
    options
        .include_untracked(true)
        .recurse_untracked_dirs(false);

    let entries = repo.statuses(Some(&mut options))?;
    let mut status = Status {
        head: Some(head(&repo)?),
        ..Status::default()
    };

    for entry in entries.iter() {
        let path = entry.path().unwrap_or_default().to_owned();
        let flags = entry.status();

        // Before anything else. A conflicted path also carries change flags, and
        // reporting it as both is reporting it as committable.
        if flags.is_conflicted() {
            status.conflicted.push(path);
            continue;
        }
        if flags.contains(git2::Status::WT_NEW) {
            status.untracked.push(path.clone());
        }
        if let Some(kind) = first_of(flags, &STAGED) {
            status.staged.push(Change {
                path: path.clone(),
                kind,
            });
        }
        if let Some(kind) = first_of(flags, &UNSTAGED) {
            status.unstaged.push(Change { path, kind });
        }
    }
    Ok(status)
}

/// The first flag in the table that is set, and what it is called.
fn first_of(flags: git2::Status, table: &[(git2::Status, &'static str)]) -> Option<&'static str> {
    table
        .iter()
        .find(|(flag, _)| flags.contains(*flag))
        .map(|(_, name)| *name)
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

    /// Write a file into the repository, creating parents.
    fn write(repo: &git2::Repository, relative: &str, body: &str) {
        let path = repo.workdir().unwrap().join(relative);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(path, body).unwrap();
    }

    /// Stage one path.
    fn stage(repo: &git2::Repository, relative: &str) {
        let mut index = repo.index().unwrap();
        index.add_path(Path::new(relative)).unwrap();
        index.write().unwrap();
    }

    #[test]
    fn an_untracked_file_is_untracked_rather_than_an_unstaged_addition() {
        // git reports it as untracked, and a model told "unstaged: added" will
        // try to unstage something that was never staged.
        let scratch = Scratch::new("untracked");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        write(&repo, "notes.txt", "hello");

        let status = status(scratch.path()).unwrap();

        assert_eq!(status.untracked, vec!["notes.txt".to_owned()]);
        assert!(status.unstaged.is_empty(), "{:?}", status.unstaged);
        assert!(status.staged.is_empty(), "{:?}", status.staged);
    }

    #[test]
    fn an_untracked_directory_is_named_rather_than_walked() {
        // A fresh clone of anything large otherwise answers with every file in
        // it, which is a context window rather than an answer.
        let scratch = Scratch::new("untracked-dir");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        for name in ["one", "two", "three"] {
            write(&repo, &format!("build/{name}.o"), "x");
        }

        let status = status(scratch.path()).unwrap();

        assert_eq!(status.untracked, vec!["build/".to_owned()]);
    }

    #[test]
    fn staging_moves_a_file_from_untracked_to_added() {
        let scratch = Scratch::new("staged");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        write(&repo, "notes.txt", "hello");
        stage(&repo, "notes.txt");

        let status = status(scratch.path()).unwrap();

        assert_eq!(
            status.staged,
            vec![Change {
                path: "notes.txt".to_owned(),
                kind: "added"
            }]
        );
        assert!(status.untracked.is_empty(), "{:?}", status.untracked);
    }

    #[test]
    fn editing_a_committed_file_is_unstaged_rather_than_staged() {
        let scratch = Scratch::new("modified");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        write(&repo, "notes.txt", "hello");
        stage(&repo, "notes.txt");
        commit(&repo);
        write(&repo, "notes.txt", "hello again");

        let status = status(scratch.path()).unwrap();

        assert_eq!(
            status.unstaged,
            vec![Change {
                path: "notes.txt".to_owned(),
                kind: "modified"
            }]
        );
        assert!(status.staged.is_empty(), "{:?}", status.staged);
    }

    #[test]
    fn a_status_carries_where_head_is() {
        // Without it the answer describes changes against something unnamed, and
        // "two files modified" on a detached head means something else entirely.
        let scratch = Scratch::new("status-head");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        write(&repo, "notes.txt", "hello");
        stage(&repo, "notes.txt");
        commit(&repo);

        let status = status(scratch.path()).unwrap();

        assert!(
            matches!(status.head, Some(Head::Branch { .. })),
            "{:?}",
            status.head
        );
    }

    #[test]
    fn a_status_of_something_that_is_not_a_repository_says_where_it_looked() {
        let scratch = Scratch::new("status-not-a-repo");
        let Err(why) = status(scratch.path()) else {
            panic!("read a status out of a directory that is not a repository")
        };

        assert!(
            why.to_string()
                .contains(&scratch.path().display().to_string())
        );
    }
}
