//! git.rs: git operations, without a subprocess.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!   2026-08-11  A. Sigdel  Took a remote, which is where anything leaving this
//!                          repository has to be told to go.
//!   2026-08-11  A. Sigdel  Took a fetch, which is the one network operation
//!                          that cannot lose anything.
//!   2026-08-11  A. Sigdel  Took a push, and the refusal that is the reason it
//!                          was worth writing carefully.
//!   2026-08-11  A. Sigdel  Took a pull that fast-forwards or says why it
//!                          cannot, which is the last of these needing no key.
//!   2026-08-11  A. Sigdel  Remembers a host's key the first time and refuses a
//!                          changed one, before anything can reach a host.
//!
//! Contents
//!   `Error`       Why an operation could not be done.
//!   `Head`        Which branch, or which commit, or neither.
//!   `identify`    Saying who commits from here.
//!   `head`        Opening a repository, and reading where it is.
//!   `Change`      One path, and what happened to it.
//!   `Status`      The working tree, against the index and the head.
//!   `add`         Staging paths.
//!   `commit`      Writing what is staged, and refusing to write nothing.
//!   `Pointed`     What pointing a remote turned out to be.
//!   `remote_set`  Where a repository sends and receives.
//!   `fetch`       Bringing back what a remote has, merging nothing.
//!   `push`        Sending a branch, and refusing to overwrite anybody.
//!   `Pulled`      What taking the remote's work turned out to be.
//!   `pull`        Fast-forwarding, or saying it cannot.
//!   `Trusted`     Whether a host's key was known already.
//!   `trust`       Pinning a host on first sight, and refusing a changed one.
//!
//! Everything on the board shells out to `git`. A phone has no shell, so these
//! come from libgit2, reached as a Rust dependency of this crate, which already
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
//! on it, and reported as a failure, that is wrong about the moment the agent is
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
    /// A path was named that is not there.
    #[error("nothing at {0} to stage")]
    NoSuchPath(String),
    /// Nothing is staged, so there is nothing to commit.
    #[error("nothing is staged, so there is nothing to commit. Stage what should go in first")]
    NothingStaged,
    /// No identity to sign a commit with.
    ///
    /// It still names the two keys, which is what a person reading a transcript
    /// needs. What it stops doing is telling the reader to set them: "Set
    /// user.name and user.email" is an instruction for a shell, and the caller
    /// that meets this most often is a model on a phone which has none and
    /// should not be looking for one. Whose job it is, is the part that is true
    /// everywhere.
    #[error(
        "no user.name and user.email are set for this repository, so a commit \
         cannot be signed. They are a claim about who somebody is, so whoever \
         is using this has to set them, and there is nothing here to do about it"
    )]
    NoIdentity,
    /// A remote this build has no transport for.
    ///
    /// Named with the form that would work, because somebody who copied a URL
    /// out of a forge has the https one in front of them and the ssh one two
    /// clicks away.
    #[error(
        "{0} is an https remote, and the only transport linked here is ssh. \
         Use the ssh form of the same remote, which on most forges is \
         git@host:owner/repository.git"
    )]
    HttpsRemote(String),
    /// No remote by that name.
    ///
    /// The library reports this as a message about a URL, which reads as a
    /// network failure for what is almost always a typed name that does not
    /// match one that is there.
    #[error("no remote called {0} is configured, so there is nowhere to reach")]
    NoSuchRemote(String),
    /// No branch by that name in this repository.
    #[error("no branch called {0} in this repository, so there is nothing to send")]
    NoSuchBranch(String),
    /// The remote refused the reference, which means somebody else is on it.
    ///
    /// Deliberately terminal. `docs/decisions/pushing-from-a-phone.md` argues
    /// that a non-fast-forward is not a transient failure and not something to
    /// retry: it is somebody else's work on the branch, and the only ways past
    /// it destroy one side or the other. Whoever is holding the phone decides
    /// that, so the message says what happened and offers nothing to try.
    #[error(
        "{reference} was refused by the remote: {detail}. That means the remote \
         has work this repository does not, so sending would overwrite it. \
         Fetch and look at what is there before deciding whose work survives"
    )]
    NotFastForward {
        /// The reference the remote refused.
        reference: String,
        /// What the remote said, which is the only account of its reasoning.
        detail: String,
    },
    /// The branch and the remote have both moved, so taking one needs a merge.
    ///
    /// The mirror of [`Error::NotFastForward`], and refused for the reason the
    /// decision record gives: a merge needs conflict resolution, that needs a
    /// diff surface, and there is no diff surface anywhere in this product. A
    /// conflicted index on a phone with no way to look at it is worse than
    /// being told no.
    #[error(
        "{branch} and the remote have both moved on, so taking the remote's \
         work would need a merge. There is nothing here to resolve a conflict \
         with, so nothing was changed"
    )]
    WouldMerge {
        /// The branch that has diverged.
        branch: String,
    },
    /// A host answered with a different key than the one pinned for it.
    ///
    /// Refused in words and never as a prompt.
    /// `docs/decisions/pushing-from-a-phone.md`: a dialog at that moment is a
    /// dialog somebody dismisses on a train, and the one time it matters is the
    /// one time it is indistinguishable from every other time.
    #[error(
        "{host} answered with a different key than the one remembered for it. \
         That is what a machine in the middle looks like, and it is also what a \
         rebuilt server looks like, so nothing was sent and nothing was \
         changed. Somebody who knows which it is has to say so"
    )]
    HostKeyChanged {
        /// The host whose key no longer matches.
        host: String,
    },
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

/// What `init` found, which is not always what it did.
#[derive(Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum Made {
    /// There was no repository and now there is.
    Created,
    /// There already was one, and nothing was changed.
    ///
    /// `git init` is idempotent and re-running it is harmless, so this could
    /// have been an `Ok(())` like any other. It is not, and #393 says why: a
    /// model that cannot tell "made you one" from "there already was one" will
    /// report having started work it is in the middle of.
    AlreadyThere,
}

/// What pointing a remote turned out to be.
///
/// [`Made`]'s reasoning, applied to the one operation where getting it wrong is
/// worse. Adding a remote and repointing one are both ordinary, and a caller
/// told only "done" cannot tell which happened. The repointing case carries
/// where it used to point, because that is the only record of it left anywhere.
#[derive(Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum Pointed {
    /// There was no remote by that name and now there is.
    Added,
    /// There was one, pointing somewhere else.
    Moved {
        /// Where it pointed until now.
        from: String,
    },
    /// There was one, already pointing there, and nothing was written.
    Unchanged,
}

/// What taking the remote's work turned out to be.
///
/// Three answers rather than one success, for the reason [`Made`] and
/// [`Pointed`] already carry: a caller told only "done" cannot tell whether
/// anything arrived.
#[derive(Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum Pulled {
    /// The remote had nothing this branch did not already have.
    AlreadyHere,
    /// The branch moved forward to what the remote had.
    FastForwarded {
        /// Where it moved to, abbreviated as git would abbreviate it.
        commit: String,
    },
    /// There was no such branch here, so it was created from the remote.
    ///
    /// Not an edge case. A repository made by `init_repository` and then
    /// pointed at a remote has no branch at all, and this is the first pull
    /// anybody does.
    Started {
        /// Where the new branch points.
        commit: String,
    },
}

/// Whether a host's key was already known.
///
/// Two answers rather than one success, and the difference is the whole
/// security posture: [`Trusted::Pinned`] is a connection nobody has checked and
/// [`Trusted::Known`] is one that matches what was checked before.
#[derive(Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum Trusted {
    /// Nothing was known about this host, so its key is now what is expected.
    ///
    /// The weakness stated rather than buried: this connection is trusted
    /// blind. On a phone with no shell there is nothing else to trust, and
    /// moving the decision to first use is what makes every later connection
    /// checkable at all.
    Pinned,
    /// The host answered with the key already remembered for it.
    Known,
}

/// Remember a host's key the first time, and refuse a changed one.
///
/// The file is one host per line, the host and a hash of its key. It is not a
/// `known_hosts` file and does not pretend to be one: that format carries
/// hashed hostnames, markers, revocation and certificate authorities, and
/// something that looks like it while implementing a tenth of it is a trap for
/// whoever reads it next.
///
/// A line that cannot be read is treated as a line that is not there. A
/// preferences file somebody edited by hand should leave the phone working,
/// which is the reasoning `modeFrom` already carries on the Kotlin side.
///
/// # Errors
/// [`Error::HostKeyChanged`] IF a key is remembered for this host and is not
/// this one, in which case **nothing is written**. [`Error::Refused`] IF the
/// file cannot be read or written.
pub fn trust(pins: &Path, host: &str, key: &[u8]) -> Result<Trusted, Error> {
    use sha2::{Digest, Sha256};

    let seen = format!("{:x}", Sha256::digest(key));
    let existing = match std::fs::read_to_string(pins) {
        Ok(text) => text,
        // Absent is empty. The first host pinned on a phone is the first time
        // this file exists at all.
        Err(why) if why.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(why) => return Err(Error::Refused(why.to_string())),
    };

    for line in existing.lines() {
        let mut fields = line.split_whitespace();
        let (Some(known_host), Some(known_key), None) =
            (fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        if known_host != host {
            continue;
        }
        return if known_key == seen {
            Ok(Trusted::Known)
        } else {
            Err(Error::HostKeyChanged {
                host: host.to_owned(),
            })
        };
    }

    let mut text = existing;
    if !text.is_empty() && !text.ends_with('\n') {
        text.push('\n');
    }
    text.push_str(host);
    text.push(' ');
    text.push_str(&seen);
    text.push('\n');
    std::fs::write(pins, text).map_err(|why| Error::Refused(why.to_string()))?;
    Ok(Trusted::Pinned)
}

/// What a call over the network needs: a key to offer, and what to check against.
///
/// One argument rather than two on each of [`fetch`], [`push`] and [`pull`],
/// because neither half is useful alone. A key with nothing to check the host
/// against authenticates the phone to whoever answered, and a pin file with no
/// key gets as far as being asked for one.
///
/// Passed as `Option`, and `None` is a call that reaches a path remote or an
/// ssh remote wanting no key. Every test in this file passes `None`, which is
/// the point: none of them has a forge to reach.
pub struct Reach<'a> {
    key: &'a str,
    pins: &'a Path,
}

impl<'a> Reach<'a> {
    /// # Arguments
    /// `key` an OpenSSH private key WHERE it is the text of one rather than a
    /// path to one. In memory on purpose: `docs/decisions/pushing-from-a-phone.md`
    /// keeps it out of the filesystem, so the only copy at rest is the sealed
    /// one the Keystore holds.
    ///
    /// `pins` the file host keys are remembered in, as [`trust`] writes it.
    #[must_use]
    pub fn new(key: &'a str, pins: &'a Path) -> Self {
        Self { key, pins }
    }
}

/// Put a [`Reach`]'s two callbacks on a set of them.
///
/// `changed` is where a refused host is left. A callback answers libgit2 rather
/// than this crate, so it cannot return [`Error::HostKeyChanged`]; it records
/// the host here and fails the transfer, and the caller reads this afterwards
/// to say which failure it was rather than reporting a network problem.
fn reaching<'a>(
    callbacks: &mut git2::RemoteCallbacks<'a>,
    reach: &'a Reach<'a>,
    changed: &'a std::cell::RefCell<Option<String>>,
) {
    let key = reach.key;
    callbacks.credentials(move |_url, username, _allowed| {
        // `git` when the URL named nobody, which is what the ssh form of every
        // forge remote uses and what a user@host URL overrides.
        git2::Cred::ssh_key_from_memory(username.unwrap_or("git"), None, key, None)
    });

    let pins = reach.pins;
    callbacks.certificate_check(move |certificate, host| {
        let Some(key) = certificate
            .as_hostkey()
            .and_then(git2::cert::CertHostkey::hostkey)
        else {
            // Not an ssh host key, which over this transport means libgit2 has
            // handed something there is no pin format for. Passed through to
            // its own check rather than accepted here.
            return Ok(git2::CertificateCheckStatus::CertificatePassthrough);
        };

        match trust(pins, host, key) {
            // Ok rather than Passthrough, which is where this diverges from the
            // sketch in #467. Passthrough means "no opinion, use the default",
            // and the default is a known_hosts lookup: there is no known_hosts
            // on a phone, so deferring to it refuses every host and the pin
            // decides nothing. `trust` is the check, so it answers.
            Ok(_) => Ok(git2::CertificateCheckStatus::CertificateOk),
            Err(Error::HostKeyChanged { host }) => {
                *changed.borrow_mut() = Some(host);
                Err(git2::Error::from_str("the host key is not the pinned one"))
            }
            Err(why) => Err(git2::Error::from_str(&why.to_string())),
        }
    });
}

/// Make a directory into a repository, or say it already was one.
///
/// The directory is created if it is not there. `git init` does that too, and a
/// phone has no shell to make one with first.
///
/// # Returns
/// [`Made::Created`] or [`Made::AlreadyThere`], which are different answers
/// rather than one success.
///
/// # Errors
/// [`Error::Refused`] IF the directory cannot be made or the repository cannot
/// be initialised there: a path inside a file, or one nothing may write to.
pub fn init(path: &Path) -> Result<Made, Error> {
    // Asked before anything is created, so an existing repository is reported
    // rather than re-initialised. `Repository::init` on one is harmless and
    // would answer Created, which is the distinction this function exists for.
    if git2::Repository::open(path).is_ok() {
        return Ok(Made::AlreadyThere);
    }

    std::fs::create_dir_all(path).map_err(|why| Error::Refused(why.to_string()))?;
    git2::Repository::init(path).map_err(|why| Error::Refused(why.message().to_owned()))?;
    Ok(Made::Created)
}

/// Say who commits from here.
///
/// Written into the repository's own configuration, which is where git keeps
/// this, so a commit made afterwards looks like one anything else wrote. The
/// alternative was carrying a name and an email down to [`commit`] on every
/// call; that ignores an identity a repository already has, which is the
/// surprising way for the ambiguity to fall.
///
/// A phone has no `~/.gitconfig` and no shell to write one with, so without
/// this every [`commit`] on one fails. That is not a hypothetical: nothing
/// outside this file's tests has ever set either value.
///
/// Deliberately not reachable as a tool. An identity is a claim about who
/// somebody is, and a model choosing one is a model deciding whose name goes
/// on the work.
///
/// # Errors
/// [`Error::NoIdentity`] IF either value is blank once trimmed, which would
/// otherwise be written and then read back as configured, giving commits an
/// author of nobody. [`Error::NotARepository`] IF nothing at `path` opens as
/// one, and [`Error::Refused`] IF the configuration cannot be written.
pub fn identify(path: &Path, name: &str, email: &str) -> Result<(), Error> {
    let (name, email) = (name.trim(), email.trim());
    if name.is_empty() || email.is_empty() {
        return Err(Error::NoIdentity);
    }

    let repo = open(path)?;
    let mut config = repo.config()?;
    // Both, or neither. A repository with a name and no email reads as
    // configured to everything except the signature, which fails at the commit
    // rather than here.
    config.set_str("user.name", name)?;
    config.set_str("user.email", email)?;
    Ok(())
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
        .and_then(|head| head.symbolic_target().ok().flatten().map(str::to_owned))
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

/// Stage paths.
///
/// # Arguments
/// * `paths`: relative to the repository root, WHERE a directory stages what is
///   under it.
///
/// # Errors
/// [`Error::NoSuchPath`] naming the one that is missing, IF any is. Four paths
/// with one misspelt otherwise fail as a whole with the library's message about
/// an unspecified pathspec, and the model has to guess which.
///
/// # Atomic
/// Not atomic across paths. The check runs over all of them first, so the common
/// failure stages nothing, but a path removed between the check and the write
/// leaves the earlier ones staged.
pub fn add(path: &Path, paths: &[String]) -> Result<Status, Error> {
    let repo = open(path)?;
    let root = repo
        .workdir()
        .ok_or_else(|| Error::Refused("bare repository".to_owned()))?;

    for relative in paths {
        if !root.join(relative).exists() {
            return Err(Error::NoSuchPath(relative.clone()));
        }
    }

    let mut index = repo.index()?;
    for relative in paths {
        if root.join(relative).is_dir() {
            index.add_all([relative], git2::IndexAddOption::DEFAULT, None)?;
        } else {
            index.add_path(Path::new(relative))?;
        }
    }
    index.write()?;
    drop(index);

    status(path)
}

/// Commit what is staged.
///
/// # Returns
/// The short id of the commit written.
///
/// # Errors
/// [`Error::NothingStaged`] IF the tree matches the parent, [`Error::NoIdentity`]
/// IF nothing configures a name and an email, and [`Error::NotARepository`] as
/// elsewhere.
pub fn commit(path: &Path, message: &str) -> Result<String, Error> {
    let repo = open(path)?;
    let who = git2::Signature::now(&name(&repo)?, &email(&repo)?)?;

    let tree = {
        let mut index = repo.index()?;
        let oid = index.write_tree()?;
        repo.find_tree(oid)?
    };

    // The first commit has no parent, which is the unborn state `head` models.
    let parent = repo.head().ok().and_then(|head| head.peel_to_commit().ok());

    // libgit2 writes a commit whose tree matches its parent without complaint,
    // and a model doing that in a loop produces a history of identical trees
    // while believing it is making progress. `git commit` stops here and says so.
    if let Some(parent) = &parent {
        if parent.tree_id() == tree.id() {
            return Err(Error::NothingStaged);
        }
    } else if tree.iter().count() == 0 {
        return Err(Error::NothingStaged);
    }

    let parents: Vec<&git2::Commit> = parent.iter().collect();
    let oid = repo.commit(Some("HEAD"), &who, &who, message, &tree, &parents)?;
    let commit = repo.find_commit(oid)?;
    Ok(commit
        .as_object()
        .short_id()?
        .as_str()
        .unwrap_or_default()
        .to_owned())
}

/// Point a remote somewhere, and say what that changed.
///
/// Creates the remote if there is none by that name, and repoints it if there
/// is. Both are ordinary things to want and neither is a failure, so they are
/// told apart in the answer rather than collapsed: a model that asked to add a
/// remote and silently moved an existing one has changed where somebody's work
/// goes.
///
/// An `https://` URL is refused here rather than at the fetch, because this is
/// the moment somebody can still be told the ssh form. See
/// `docs/decisions/pushing-from-a-phone.md`: the transport this build links is
/// ssh, and turning `git2/https` on as well is a scope line rather than a
/// permanent one.
///
/// A filesystem path is a remote too, and deliberately still allowed. It needs
/// no transport at all, which is what lets every test below run with no
/// network and no key.
///
/// # Errors
/// [`Error::HttpsRemote`] IF the URL is http or https. [`Error::NotARepository`]
/// IF nothing at `path` opens as one, and [`Error::Refused`] IF the remote
/// cannot be written.
pub fn remote_set(path: &Path, name: &str, url: &str) -> Result<Pointed, Error> {
    let url = url.trim();
    let lowered = url.to_ascii_lowercase();
    if lowered.starts_with("https://") || lowered.starts_with("http://") {
        return Err(Error::HttpsRemote(url.to_owned()));
    }

    let repo = open(path)?;
    let was = repo
        .find_remote(name)
        .ok()
        .and_then(|remote| remote.url().ok().map(ToOwned::to_owned));

    match was {
        Some(was) if was == url => Ok(Pointed::Unchanged),
        Some(was) => {
            repo.remote_set_url(name, url)?;
            Ok(Pointed::Moved { from: was })
        }
        None => {
            repo.remote(name, url)?;
            Ok(Pointed::Added)
        }
    }
}

/// Fetch a remote, and answer with the references that moved.
///
/// The remote's own refspecs, which for one created by [`remote_set`] means
/// every branch into `refs/remotes/<name>/`. Nothing is merged, the index is
/// not touched and no file is written into the working tree: this is the one
/// network operation that cannot lose anything, which is why it comes before
/// push and pull rather than with them.
///
/// The names rather than the transfer statistics. "Received 3 objects" is a
/// number nobody can act on, where `refs/remotes/origin/main` is the thing the
/// next question is about. An empty answer means the remote had nothing this
/// repository did not already have, which is a state rather than a failure and
/// reads as one.
///
/// `reach` is `None` for a path remote, or an ssh remote wanting no key.
///
/// # Errors
/// [`Error::NoSuchRemote`] IF nothing by that name is configured.
/// [`Error::HostKeyChanged`] IF a `reach` was given and the host answered with
/// a key other than the pinned one. [`Error::NotARepository`] IF nothing at
/// `path` opens as one, and [`Error::Refused`] IF the fetch itself is refused.
pub fn fetch(path: &Path, name: &str, reach: Option<&Reach>) -> Result<Vec<String>, Error> {
    let repo = open(path)?;
    let mut remote = repo
        .find_remote(name)
        .map_err(|_| Error::NoSuchRemote(name.to_owned()))?;

    let mut moved = Vec::new();
    let changed = std::cell::RefCell::new(None);
    let taken = {
        let mut callbacks = git2::RemoteCallbacks::new();
        // update_tips rather than the transfer statistics: it fires once per
        // reference that actually changed, which is the question being asked.
        callbacks.update_tips(|reference, _from, _to| {
            moved.push(reference.to_owned());
            true
        });
        if let Some(reach) = reach {
            reaching(&mut callbacks, reach, &changed);
        }

        let mut options = git2::FetchOptions::new();
        options.remote_callbacks(callbacks);
        // An empty refspec list means the remote's configured ones, which is
        // what `git fetch <remote>` with no further argument does.
        remote.fetch::<&str>(&[], Some(&mut options), None)
    };

    // Read before the transfer's own error, which for a refused host key is a
    // message about the transport rather than about who answered.
    if let Some(host) = changed.into_inner() {
        return Err(Error::HostKeyChanged { host });
    }
    taken?;
    Ok(moved)
}

/// Send a branch to a remote, and refuse rather than overwrite.
///
/// There is no `force` parameter, and its absence is the decision rather than
/// an omission. `docs/decisions/pushing-from-a-phone.md`: an argument a model
/// can set is one it will set, eventually, on the turn where setting it makes
/// the error go away, and the cost of setting it wrongly here is the only one
/// on the list that nobody can undo.
///
/// A refusal arrives by **two different routes**, and catching only one was the
/// first thing this got wrong. Against a real forge the remote rejects the
/// reference and libgit2 reports it through a `push_update_reference` callback
/// while `push` itself answers `Ok`, because the transport succeeded. Against a
/// path remote libgit2 works out that the reference is not fast-forwardable
/// before sending anything and returns the error from `push` directly. Both are
/// the same event and both must read as [`Error::NotFastForward`].
///
/// That second route is matched on the library's message, which is the sort of
/// thing that goes stale silently. What stops it is that
/// `a_push_that_is_not_a_fast_forward_is_refused` exercises exactly that path:
/// if the wording changes, the test fails rather than the refusal disappearing.
///
/// The callback route has to be caught rather than inferred from the return,
/// and that is the whole reason this function is more than three lines. libgit2
/// answers `Ok` when the *transport* succeeded, so a remote that rejected the
/// reference reads as a successful push that changed nothing, which is the
/// worst failure available here. A `push_update_reference` callback fires once
/// per reference with an optional status message, and a message means refused.
///
/// # Errors
/// [`Error::NotFastForward`] IF the remote refused the reference, which means
/// it has work this repository does not. [`Error::NoSuchBranch`] IF there is no
/// such branch here, [`Error::NoSuchRemote`] IF no such remote is configured,
/// [`Error::HostKeyChanged`] IF a `reach` was given and the host answered with
/// a key other than the pinned one, [`Error::NotARepository`] IF nothing at
/// `path` opens as one, and [`Error::Refused`] IF the push fails for any other
/// reason.
pub fn push(path: &Path, remote: &str, branch: &str, reach: Option<&Reach>) -> Result<(), Error> {
    let repo = open(path)?;
    // Asked here so a typed branch name is answered as one, rather than as
    // whatever the refspec parser makes of it further down.
    repo.find_branch(branch, git2::BranchType::Local)
        .map_err(|_| Error::NoSuchBranch(branch.to_owned()))?;
    let mut remote = repo
        .find_remote(remote)
        .map_err(|_| Error::NoSuchRemote(remote.to_owned()))?;

    let reference = format!("refs/heads/{branch}");
    let mut refused = None;
    let changed = std::cell::RefCell::new(None);
    let sent = {
        let mut callbacks = git2::RemoteCallbacks::new();
        callbacks.push_update_reference(|reference, status| {
            if let Some(detail) = status {
                refused = Some(Error::NotFastForward {
                    reference: reference.to_owned(),
                    detail: detail.to_owned(),
                });
            }
            Ok(())
        });
        if let Some(reach) = reach {
            reaching(&mut callbacks, reach, &changed);
        }

        let mut options = git2::PushOptions::new();
        options.remote_callbacks(callbacks);
        // No leading `+`, which is what a forced refspec would be. There is no
        // argument that could put one here.
        let refspec = format!("{reference}:{reference}");
        remote.push(&[refspec.as_str()], Some(&mut options))
    };

    // Before the reference refusal and before the transport's own error: a host
    // that is not the pinned one means nothing was sent at all, so reporting it
    // as a rejected push would name the wrong problem.
    if let Some(host) = changed.into_inner() {
        return Err(Error::HostKeyChanged { host });
    }
    if let Some(why) = refused {
        return Err(why);
    }
    match sent {
        Ok(()) => Ok(()),
        // The path-remote route described above. `fastforward` rather than
        // `fast-forward`: libgit2 spells it as one word here and hyphenates it
        // elsewhere, and matching the hyphenated form silently catches nothing.
        Err(why) if why.message().contains("fastforward") => Err(Error::NotFastForward {
            reference,
            detail: why.message().to_owned(),
        }),
        Err(why) => Err(why.into()),
    }
}

/// Take what a remote has, if that can be done without merging.
///
/// Fetches, then moves the branch to what the remote has when that is a
/// fast-forward, and refuses when it is not.
///
/// The analysis is asked for rather than inferred. Moving the reference without
/// it is a force-pull: it discards local commits silently, which is exactly
/// what [`push`] refuses to do in the other direction, and doing it here
/// because the code is shorter would be the same mistake facing the other way.
///
/// The checkout is safe rather than forced. Uncommitted work in the way makes
/// this fail, and that is the intended behaviour rather than a limitation to
/// route around: `force()` is a one-word change that would make a red test
/// green by throwing away whatever was in the tree.
///
/// # Errors
/// [`Error::WouldMerge`] IF the branch and the remote have both moved on.
/// [`Error::HostKeyChanged`] IF a `reach` was given and the host answered with
/// a key other than the pinned one, [`Error::NoSuchRemote`] IF no such remote is
/// configured,
/// [`Error::NotARepository`] IF nothing at `path` opens as one, and
/// [`Error::Refused`] IF the fetch, the analysis or the checkout fails.
pub fn pull(
    path: &Path,
    remote: &str,
    branch: &str,
    reach: Option<&Reach>,
) -> Result<Pulled, Error> {
    // Forwarded rather than dropped: this is the only call here that reaches the
    // network, so a pull with a key that did not pass it on would ask for one.
    fetch(path, remote, reach)?;

    let repo = open(path)?;
    let tracking = format!("refs/remotes/{remote}/{branch}");
    let target = repo
        .find_reference(&tracking)
        .map_err(|_| Error::NoSuchBranch(format!("{branch} on {remote}")))?
        .peel_to_commit()?;
    let onto = repo.find_annotated_commit(target.id())?;
    let short = target
        .as_object()
        .short_id()?
        .as_str()
        .unwrap_or_default()
        .to_owned();

    let (analysis, _) = repo.merge_analysis(&[&onto])?;
    if analysis.is_up_to_date() {
        return Ok(Pulled::AlreadyHere);
    }
    if !analysis.is_fast_forward() && !analysis.is_unborn() {
        return Err(Error::WouldMerge {
            branch: branch.to_owned(),
        });
    }

    let here = format!("refs/heads/{branch}");
    let started = analysis.is_unborn();
    match repo.find_reference(&here) {
        Ok(mut reference) => {
            reference.set_target(target.id(), "pull: fast-forward")?;
        }
        // Unborn, so there is no reference to move and one is made instead.
        Err(_) => {
            repo.reference(&here, target.id(), false, "pull: started")?;
        }
    }
    repo.set_head(&here)?;
    repo.checkout_head(Some(git2::build::CheckoutBuilder::default().safe()))?;

    if started {
        Ok(Pulled::Started { commit: short })
    } else {
        Ok(Pulled::FastForwarded { commit: short })
    }
}

/// The committer name, or a refusal naming what is missing.
///
/// The library reports an absent identity as "config value not found", which
/// says nothing about which value or where it goes.
fn name(repo: &git2::Repository) -> Result<String, Error> {
    configured(repo, "user.name")
}

/// The committer email, on the same terms.
fn email(repo: &git2::Repository) -> Result<String, Error> {
    configured(repo, "user.email")
}

fn configured(repo: &git2::Repository, key: &str) -> Result<String, Error> {
    repo.config()
        .and_then(|config| config.get_string(key))
        .map_err(|_| Error::NoIdentity)
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
    use crate::testenv::Scratch;

    /// Give the repository an identity to sign with, as a phone has to.
    ///
    /// Through the entry point rather than beside it, now that there is one.
    /// The helper wrote the same two keys by hand, which meant every commit
    /// test proved a phone could commit only if the phone did it this way, and
    /// nothing made it. Shadows [`super::identify`] by name on purpose: the
    /// tests below read better calling it with a repository they already hold.
    fn identify(repo: &git2::Repository) {
        super::identify(repo.workdir().unwrap(), "Test", "test@example.com").unwrap();
    }

    /// What this repository alone says, with no global gitconfig behind it.
    fn local(repo: &git2::Repository) -> git2::Config {
        repo.config()
            .unwrap()
            .open_level(git2::ConfigLevel::Local)
            .unwrap()
    }

    /// Commit whatever is in the index, with no parent unless there is one.
    fn commit_directly(repo: &git2::Repository) -> git2::Oid {
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
    fn init_makes_a_repository_where_there_was_none() {
        let scratch = Scratch::new("init-fresh");
        let made = init(scratch.path()).expect("init refused a writable directory");

        assert_eq!(Made::Created, made);
        assert!(
            open(scratch.path()).is_ok(),
            "init did not leave a repository"
        );
    }

    #[test]
    fn init_over_an_existing_repository_says_it_was_already_there() {
        // The decision #393 asked for. `git init` is idempotent and this could
        // have answered Created twice; a model that cannot tell the two apart
        // reports having started work it is in the middle of.
        let scratch = Scratch::new("init-twice");
        assert_eq!(Made::Created, init(scratch.path()).unwrap());
        assert_eq!(Made::AlreadyThere, init(scratch.path()).unwrap());
    }

    #[test]
    fn init_makes_the_directory_it_was_pointed_at() {
        // A phone has no shell to make one with first, and on a fresh install
        // the working directory does not exist.
        let scratch = Scratch::new("init-missing");
        let nested = scratch.path().join("work");
        assert!(!nested.exists());

        assert_eq!(Made::Created, init(&nested).unwrap());
        assert!(open(&nested).is_ok());
    }

    #[test]
    fn init_where_nothing_can_be_written_is_refused() {
        // A path inside a file. Reported rather than panicking, because the
        // model chose the path and can choose another.
        let scratch = Scratch::new("init-blocked");
        let blocking = scratch.path().join("afile");
        std::fs::create_dir_all(scratch.path()).unwrap();
        std::fs::write(&blocking, "x").unwrap();

        assert!(init(&blocking.join("under")).is_err());
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
        commit_directly(&repo);

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
        let oid = commit_directly(&repo);
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
        commit_directly(&repo);
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
        commit_directly(&repo);

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

    #[test]
    fn staging_a_path_that_is_not_there_names_it() {
        // Four paths with one misspelt otherwise fail as a whole with the
        // library's message about an unspecified pathspec, and the model has to
        // guess which one it got wrong.
        let scratch = Scratch::new("add-missing");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        write(&repo, "here.txt", "x");

        let paths = ["here.txt".to_owned(), "not-here.txt".to_owned()];
        let Err(why) = add(scratch.path(), &paths) else {
            panic!("staged a path that does not exist")
        };

        assert!(why.to_string().contains("not-here.txt"), "{why}");
        assert!(
            status(scratch.path()).unwrap().staged.is_empty(),
            "the check ran after the write"
        );
    }

    #[test]
    fn staging_a_directory_stages_what_is_under_it() {
        let scratch = Scratch::new("add-dir");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        write(&repo, "src/one.txt", "x");
        write(&repo, "src/two.txt", "y");

        let staged = add(scratch.path(), &["src".to_owned()]).unwrap().staged;

        assert_eq!(staged.len(), 2, "{staged:?}");
        assert!(staged.iter().all(|change| change.kind == "added"));
    }

    #[test]
    fn committing_nothing_is_refused_rather_than_written() {
        // libgit2 writes a commit whose tree matches its parent without
        // complaint, and a model doing that in a loop produces a history of
        // identical trees while believing it is making progress.
        let scratch = Scratch::new("empty-commit");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        identify(&repo);
        write(&repo, "notes.txt", "hello");
        add(scratch.path(), &["notes.txt".to_owned()]).unwrap();
        commit(scratch.path(), "the first").unwrap();

        let Err(why) = commit(scratch.path(), "the same again") else {
            panic!("wrote a commit with nothing in it")
        };
        assert!(matches!(why, Error::NothingStaged), "{why}");
    }

    #[test]
    fn the_first_commit_has_no_parent_and_ends_the_unborn_state() {
        let scratch = Scratch::new("first-commit");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        identify(&repo);
        assert!(matches!(head(&repo).unwrap(), Head::Unborn { .. }));

        write(&repo, "notes.txt", "hello");
        add(scratch.path(), &["notes.txt".to_owned()]).unwrap();
        let short = commit(scratch.path(), "the first").unwrap();

        assert!(!short.is_empty());
        assert!(matches!(head(&repo).unwrap(), Head::Branch { .. }));
        assert!(status(scratch.path()).unwrap().staged.is_empty());
    }

    #[test]
    fn a_missing_setting_says_which_settings_are_missing() {
        // Through `configured` rather than through `commit`. Isolating a
        // repository from a global gitconfig means moving libgit2's search path,
        // which is process-wide, and the machine running these tests very likely
        // has an identity while the phone this is for does not. The mapping is
        // what is under test either way: the library reports an absent key as
        // "config value not found", which says nothing about which key or where
        // it goes.
        let scratch = Scratch::new("no-identity");
        let repo = git2::Repository::init(scratch.path()).unwrap();

        let Err(why) = configured(&repo, "wattrouter.definitely-not-set") else {
            panic!("read a setting that was never written")
        };
        assert!(matches!(why, Error::NoIdentity), "{why}");
        assert!(why.to_string().contains("user.email"), "{why}");
    }

    #[test]
    fn the_ssh_transport_is_linked_in() {
        // What #644 measured, kept so it cannot be switched off by accident.
        // Cargo.toml turns `ssh` on for git2, which pulls libssh2 and OpenSSL
        // into the phone's build; dropping the feature compiles everything in
        // this crate and fails on a phone the first time somebody pushes.
        //
        // The discriminator is which failure arrives rather than whether one
        // does. With no transport linked, libgit2 refuses the scheme before it
        // touches the network. With one linked it gets as far as the socket.
        // Port 1 on the loopback is refused immediately either way, so this
        // needs no network and waits for nothing.
        let scratch = Scratch::new("ssh-linked");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        let mut remote = repo.remote_anonymous("ssh://127.0.0.1:1/x.git").unwrap();

        let Err(why) = remote.connect(git2::Direction::Fetch) else {
            panic!("something answered on port 1 of the loopback")
        };
        assert!(
            !why.message().contains("unsupported"),
            "libgit2 has no ssh transport: {why}",
        );
    }

    #[test]
    fn an_identity_is_written_where_git_keeps_it() {
        let scratch = Scratch::new("identify");
        let repo = git2::Repository::init(scratch.path()).unwrap();

        identify(&repo);

        // Read back through `configured`, which is what `commit` reads through.
        // Asserting on the config object directly would prove the write and not
        // that the thing that signs can find it.
        assert_eq!(configured(&repo, "user.name").unwrap(), "Test");
        assert_eq!(configured(&repo, "user.email").unwrap(), "test@example.com");
        // And at the repository's own level, which is the claim in the name.
        // `configured` reads every level, so on a machine with a gitconfig it
        // answers whether or not anything was written here.
        assert_eq!(local(&repo).get_string("user.name").unwrap(), "Test");
    }

    #[test]
    fn a_commit_carries_the_identity_that_was_set() {
        // The round trip, and the whole point: nothing on a phone had ever put
        // a name on a commit before this.
        let scratch = Scratch::new("signed");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        super::identify(scratch.path(), "  Ada  ", " ada@example.com ").unwrap();

        write(&repo, "notes.txt", "hello");
        add(scratch.path(), &["notes.txt".to_owned()]).unwrap();
        commit(scratch.path(), "the first").unwrap();

        let who = repo.head().unwrap().peel_to_commit().unwrap();
        // Trimmed on the way in. A name pasted with a trailing space is in
        // every commit forever otherwise, and it is not a distinction anybody
        // meant to draw.
        assert_eq!(who.author().name().unwrap(), "Ada");
        assert_eq!(who.author().email().unwrap(), "ada@example.com");
    }

    #[test]
    fn half_an_identity_is_refused_rather_than_written() {
        // Written, a blank reads back as configured and the failure moves to
        // the signature, where the message is the library's and says nothing
        // about which half is missing.
        let scratch = Scratch::new("half-identity");
        let repo = git2::Repository::init(scratch.path()).unwrap();

        for (name, email) in [("", "ada@example.com"), ("Ada", "   "), ("", "")] {
            let Err(why) = super::identify(scratch.path(), name, email) else {
                panic!("accepted {name:?} and {email:?}")
            };
            assert!(matches!(why, Error::NoIdentity), "{why}");
        }
        // The repository's own level, not `configured`. The machine running
        // these tests very likely has a gitconfig with a name in it, and
        // `configured` would answer with that and pass over a bug here. It is
        // the same trap a_missing_setting_says_which_settings_are_missing
        // documents, met from the other side.
        assert!(local(&repo).get_string("user.name").is_err());
    }

    #[test]
    fn identifying_something_that_is_not_a_repository_names_the_path() {
        let scratch = Scratch::new("identify-nothing");

        let Err(why) = super::identify(scratch.path(), "Ada", "ada@example.com") else {
            panic!("identified a directory that is not a repository")
        };
        assert!(
            why.to_string()
                .contains(&scratch.path().display().to_string()),
            "{why}"
        );
    }

    /// A repository with one commit on it, to be fetched from over a path.
    ///
    /// A path remote needs no transport, no key and no network, which is what
    /// lets the tests below run everywhere CI does.
    fn somewhere_to_fetch_from(scratch: &Scratch) -> (git2::Repository, String) {
        let repo = git2::Repository::init(scratch.path()).unwrap();
        std::fs::write(scratch.path().join("a.txt"), "first").unwrap();
        {
            let mut index = repo.index().unwrap();
            index.add_path(Path::new("a.txt")).unwrap();
            index.write().unwrap();
        }
        commit_directly(&repo);

        // Read rather than assumed: init.defaultBranch is a global setting, so
        // the branch this lands on is whatever the machine running the tests
        // says, and asserting "master" passes here and fails on somebody else.
        let Head::Branch { name, .. } = head(&repo).unwrap() else {
            panic!("the source repository has no branch")
        };
        (repo, name)
    }

    #[test]
    fn a_remote_that_was_not_there_is_added() {
        let scratch = Scratch::new("remote-add");
        let repo = git2::Repository::init(scratch.path()).unwrap();

        let pointed = remote_set(scratch.path(), "origin", "/somewhere/else.git").unwrap();

        assert_eq!(Pointed::Added, pointed);
        assert_eq!(
            "/somewhere/else.git",
            repo.find_remote("origin").unwrap().url().unwrap()
        );
    }

    #[test]
    fn repointing_a_remote_says_where_it_used_to_point() {
        // The answer this enum exists for. Somebody who meant to add a remote
        // and moved one instead has changed where the work goes, and the old
        // URL is the only record of it left anywhere.
        let scratch = Scratch::new("remote-move");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        remote_set(scratch.path(), "origin", "/first.git").unwrap();

        let pointed = remote_set(scratch.path(), "origin", "/second.git").unwrap();

        assert_eq!(
            Pointed::Moved {
                from: "/first.git".to_owned()
            },
            pointed
        );
        assert_eq!(
            "/second.git",
            repo.find_remote("origin").unwrap().url().unwrap()
        );
    }

    #[test]
    fn pointing_a_remote_where_it_already_points_changes_nothing() {
        let scratch = Scratch::new("remote-same");
        git2::Repository::init(scratch.path()).unwrap();
        remote_set(scratch.path(), "origin", "/first.git").unwrap();

        assert_eq!(
            Pointed::Unchanged,
            remote_set(scratch.path(), "origin", "/first.git").unwrap()
        );
    }

    #[test]
    fn an_https_remote_is_refused_and_told_the_form_that_works() {
        // Refused here rather than at the fetch, which is the moment somebody
        // still has the forge's page open to copy the other URL from.
        let scratch = Scratch::new("remote-https");
        git2::Repository::init(scratch.path()).unwrap();

        for url in [
            "https://github.com/owner/repository.git",
            "HTTPS://github.com/owner/repository.git",
            "http://insecure.example/repository.git",
        ] {
            let Err(why) = remote_set(scratch.path(), "origin", url) else {
                panic!("accepted {url}")
            };
            assert!(matches!(why, Error::HttpsRemote(_)), "{why}");
            assert!(why.to_string().contains("git@host:"), "{why}");
        }
    }

    #[test]
    fn an_ssh_remote_is_accepted_even_though_nothing_here_can_reach_one() {
        // The refusal above is about the scheme this build has no transport
        // for, not about every remote that is not a path.
        let scratch = Scratch::new("remote-ssh");
        git2::Repository::init(scratch.path()).unwrap();

        assert_eq!(
            Pointed::Added,
            remote_set(
                scratch.path(),
                "origin",
                "git@github.com:owner/repository.git"
            )
            .unwrap()
        );
    }

    #[test]
    fn fetching_a_remote_that_is_not_configured_names_it() {
        let scratch = Scratch::new("fetch-missing");
        git2::Repository::init(scratch.path()).unwrap();

        let Err(why) = fetch(scratch.path(), "upstream", None) else {
            panic!("fetched a remote that is not there")
        };
        assert!(matches!(why, Error::NoSuchRemote(_)), "{why}");
        assert!(why.to_string().contains("upstream"), "{why}");
    }

    #[test]
    fn a_fetch_brings_back_what_the_other_repository_has() {
        let source = Scratch::new("fetch-source");
        let (origin, branch) = somewhere_to_fetch_from(&source);
        let wanted = origin.head().unwrap().peel_to_commit().unwrap().id();

        let scratch = Scratch::new("fetch-into");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        remote_set(
            scratch.path(),
            "origin",
            &source.path().display().to_string(),
        )
        .unwrap();

        let moved = fetch(scratch.path(), "origin", None).unwrap();

        assert!(
            moved.contains(&format!("refs/remotes/origin/{branch}")),
            "{moved:?}"
        );
        assert!(
            repo.find_commit(wanted).is_ok(),
            "the commit was named as fetched and is not here"
        );
    }

    #[test]
    fn a_fetch_with_nothing_new_says_nothing_moved() {
        // A state rather than a failure, and the one a model meets most: it
        // fetches, is told nothing changed, and stops asking.
        let source = Scratch::new("fetch-twice-source");
        somewhere_to_fetch_from(&source);

        let scratch = Scratch::new("fetch-twice");
        git2::Repository::init(scratch.path()).unwrap();
        remote_set(
            scratch.path(),
            "origin",
            &source.path().display().to_string(),
        )
        .unwrap();
        assert!(!fetch(scratch.path(), "origin", None).unwrap().is_empty());

        assert!(fetch(scratch.path(), "origin", None).unwrap().is_empty());
    }

    #[test]
    fn a_fetch_merges_nothing_into_the_working_tree() {
        // The property that makes this the half of a pull that cannot lose
        // anything, and the reason pull is not in the same change.
        let source = Scratch::new("fetch-nomerge-source");
        somewhere_to_fetch_from(&source);

        let scratch = Scratch::new("fetch-nomerge");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        remote_set(
            scratch.path(),
            "origin",
            &source.path().display().to_string(),
        )
        .unwrap();
        fetch(scratch.path(), "origin", None).unwrap();

        assert!(
            !scratch.path().join("a.txt").exists(),
            "a fetch wrote a file into the working tree"
        );
        assert!(matches!(head(&repo).unwrap(), Head::Unborn { .. }));
    }

    /// A bare repository, which is a complete remote over a path.
    fn somewhere_to_push_to(scratch: &Scratch) -> git2::Repository {
        git2::Repository::init_bare(scratch.path()).unwrap()
    }

    /// Commit a file, so there is something to send.
    fn commit_a_file(repo: &git2::Repository, name: &str, body: &str) -> git2::Oid {
        let root = repo.workdir().unwrap().to_owned();
        std::fs::write(root.join(name), body).unwrap();
        {
            let mut index = repo.index().unwrap();
            index.add_path(Path::new(name)).unwrap();
            index.write().unwrap();
        }
        commit_directly(repo)
    }

    /// A repository with one commit, pointed at `remote`.
    fn ready_to_push(scratch: &Scratch, remote: &Scratch) -> (git2::Repository, String) {
        let repo = git2::Repository::init(scratch.path()).unwrap();
        commit_a_file(&repo, "a.txt", "first");
        remote_set(
            scratch.path(),
            "origin",
            &remote.path().display().to_string(),
        )
        .unwrap();

        let Head::Branch { name, .. } = head(&repo).unwrap() else {
            panic!("no branch to push")
        };
        (repo, name)
    }

    #[test]
    fn a_push_puts_the_branch_on_the_remote() {
        let bare = Scratch::new("push-bare");
        let origin = somewhere_to_push_to(&bare);
        let scratch = Scratch::new("push-from");
        let (repo, branch) = ready_to_push(&scratch, &bare);
        let sent = repo.head().unwrap().peel_to_commit().unwrap().id();

        push(scratch.path(), "origin", &branch, None).unwrap();

        let there = origin
            .find_reference(&format!("refs/heads/{branch}"))
            .unwrap();
        assert_eq!(sent, there.peel_to_commit().unwrap().id());
    }

    #[test]
    fn a_reach_offered_to_a_transport_that_asks_for_nothing_changes_nothing() {
        // What a harness with no forge in it can say, stated as exactly that.
        // The callbacks are installed and a path remote asks for neither, so
        // this proves the plumbing does not break the path that works today and
        // says nothing whatever about ssh. What no test here can prove is that
        // any of it reaches a real host: that needs a network, an account and a
        // key, and there is none of the three.
        let scratch_pins = Scratch::new("reach-pins");
        let pins = scratch_pins.path().join("hosts");
        let reach = Reach::new("not a key, and never read here", &pins);

        let bare = Scratch::new("reach-bare");
        let origin = somewhere_to_push_to(&bare);
        let scratch = Scratch::new("reach-from");
        let (repo, branch) = ready_to_push(&scratch, &bare);
        let sent = repo.head().unwrap().peel_to_commit().unwrap().id();

        push(scratch.path(), "origin", &branch, Some(&reach)).unwrap();
        fetch(scratch.path(), "origin", Some(&reach)).unwrap();
        let taken = pull(scratch.path(), "origin", &branch, Some(&reach)).unwrap();

        assert_eq!(
            sent,
            origin
                .find_reference(&format!("refs/heads/{branch}"))
                .unwrap()
                .peel_to_commit()
                .unwrap()
                .id()
        );
        assert_eq!(Pulled::AlreadyHere, taken);
        // Nothing was pinned, because nothing was asked. A file here would mean
        // the certificate callback had run against a path remote.
        assert!(!pins.exists());
    }

    #[test]
    fn a_push_that_is_not_a_fast_forward_is_refused() {
        // The decision this whole function exists for. libgit2 answers Ok when
        // the transport succeeded, so without the callback this reads as a
        // push that worked and changed nothing.
        let bare = Scratch::new("push-diverge-bare");
        let origin = somewhere_to_push_to(&bare);
        let scratch = Scratch::new("push-diverge");
        let (repo, branch) = ready_to_push(&scratch, &bare);
        let shared = repo.head().unwrap().peel_to_commit().unwrap().id();

        // The commit somebody else made. It reaches the remote, and then this
        // repository goes back before it and off in another direction, which is
        // what having missed a push looks like from here.
        commit_a_file(&repo, "b.txt", "second");
        push(scratch.path(), "origin", &branch, None).unwrap();
        let theirs = repo.head().unwrap().peel_to_commit().unwrap().id();

        let before = repo.find_commit(shared).unwrap();
        repo.reset(before.as_object(), git2::ResetType::Hard, None)
            .unwrap();
        commit_a_file(&repo, "c.txt", "third");

        let Err(why) = push(scratch.path(), "origin", &branch, None) else {
            panic!("a non-fast-forward push was accepted")
        };
        assert!(matches!(why, Error::NotFastForward { .. }), "{why}");

        // The half that matters more than the refusal: nothing moved.
        let there = origin
            .find_reference(&format!("refs/heads/{branch}"))
            .unwrap();
        assert_eq!(
            theirs,
            there.peel_to_commit().unwrap().id(),
            "a refused push moved the remote anyway"
        );
    }

    #[test]
    fn a_refused_push_says_what_to_do_and_offers_no_way_round_it() {
        // The message is the whole interface here: there is no force argument
        // to reach for, so the words have to carry what happens next.
        let why = Error::NotFastForward {
            reference: "refs/heads/main".to_owned(),
            detail: "non-fast-forward".to_owned(),
        };

        let said = why.to_string();
        assert!(said.contains("refs/heads/main"), "{said}");
        assert!(said.contains("Fetch"), "{said}");
        assert!(!said.to_lowercase().contains("force"), "{said}");
    }

    #[test]
    fn pushing_a_branch_that_is_not_here_names_it() {
        let bare = Scratch::new("push-nobranch-bare");
        somewhere_to_push_to(&bare);
        let scratch = Scratch::new("push-nobranch");
        ready_to_push(&scratch, &bare);

        let Err(why) = push(scratch.path(), "origin", "not-a-branch", None) else {
            panic!("pushed a branch that is not here")
        };
        assert!(matches!(why, Error::NoSuchBranch(_)), "{why}");
        assert!(why.to_string().contains("not-a-branch"), "{why}");
    }

    #[test]
    fn pushing_to_a_remote_that_is_not_configured_names_it() {
        let scratch = Scratch::new("push-noremote");
        let repo = git2::Repository::init(scratch.path()).unwrap();
        commit_a_file(&repo, "a.txt", "first");
        let Head::Branch { name, .. } = head(&repo).unwrap() else {
            panic!("no branch")
        };

        let Err(why) = push(scratch.path(), "upstream", &name, None) else {
            panic!("pushed to a remote that is not there")
        };
        assert!(matches!(why, Error::NoSuchRemote(_)), "{why}");
    }

    /// A bare remote, and a repository that has pushed one commit to it.
    fn pushed_once(scratch: &Scratch, bare: &Scratch) -> (git2::Repository, String) {
        somewhere_to_push_to(bare);
        let (repo, branch) = ready_to_push(scratch, bare);
        push(scratch.path(), "origin", &branch, None).unwrap();
        (repo, branch)
    }

    #[test]
    fn a_pull_with_nothing_new_says_the_work_is_already_here() {
        let bare = Scratch::new("pull-same-bare");
        let scratch = Scratch::new("pull-same");
        let (_repo, branch) = pushed_once(&scratch, &bare);

        assert_eq!(
            Pulled::AlreadyHere,
            pull(scratch.path(), "origin", &branch, None).unwrap()
        );
    }

    #[test]
    fn a_pull_that_can_fast_forward_does_and_writes_the_file() {
        let bare = Scratch::new("pull-ff-bare");
        let theirs = Scratch::new("pull-ff-theirs");
        let (repo, branch) = pushed_once(&theirs, &bare);
        commit_a_file(&repo, "b.txt", "second");
        push(theirs.path(), "origin", &branch, None).unwrap();
        let wanted = repo.head().unwrap().peel_to_commit().unwrap().id();

        // A second repository that has the first commit and not the second.
        let mine = Scratch::new("pull-ff-mine");
        let here = git2::Repository::init(mine.path()).unwrap();
        remote_set(mine.path(), "origin", &bare.path().display().to_string()).unwrap();
        pull(mine.path(), "origin", &branch, None).unwrap();

        assert_eq!(wanted, here.head().unwrap().peel_to_commit().unwrap().id());
        assert!(
            mine.path().join("b.txt").exists(),
            "a fast-forward left the working tree behind"
        );
    }

    #[test]
    fn the_first_pull_into_an_empty_repository_starts_the_branch() {
        // The case that is not an edge case: init_repository leaves a
        // repository with no branch at all, and this is the first pull anybody
        // does on one.
        let bare = Scratch::new("pull-unborn-bare");
        let theirs = Scratch::new("pull-unborn-theirs");
        let (_repo, branch) = pushed_once(&theirs, &bare);

        let mine = Scratch::new("pull-unborn-mine");
        git2::Repository::init(mine.path()).unwrap();
        remote_set(mine.path(), "origin", &bare.path().display().to_string()).unwrap();

        let pulled = pull(mine.path(), "origin", &branch, None).unwrap();

        assert!(matches!(pulled, Pulled::Started { .. }), "{pulled:?}");
        assert!(mine.path().join("a.txt").exists());
    }

    #[test]
    fn a_pull_that_would_merge_is_refused_and_changes_nothing() {
        let bare = Scratch::new("pull-diverge-bare");
        let theirs = Scratch::new("pull-diverge-theirs");
        let (repo, branch) = pushed_once(&theirs, &bare);

        // Mine takes the first commit, then both sides move.
        let mine = Scratch::new("pull-diverge-mine");
        let here = git2::Repository::init(mine.path()).unwrap();
        remote_set(mine.path(), "origin", &bare.path().display().to_string()).unwrap();
        pull(mine.path(), "origin", &branch, None).unwrap();
        commit_a_file(&here, "mine.txt", "mine");
        let unmoved = here.head().unwrap().peel_to_commit().unwrap().id();

        commit_a_file(&repo, "theirs.txt", "theirs");
        push(theirs.path(), "origin", &branch, None).unwrap();

        let Err(why) = pull(mine.path(), "origin", &branch, None) else {
            panic!("a pull that needed a merge went ahead")
        };
        assert!(matches!(why, Error::WouldMerge { .. }), "{why}");
        assert_eq!(
            unmoved,
            here.head().unwrap().peel_to_commit().unwrap().id(),
            "a refused pull moved the branch anyway"
        );
        assert!(
            !mine.path().join("theirs.txt").exists(),
            "a refused pull wrote their file into the tree"
        );
    }

    #[test]
    fn a_host_nobody_knew_is_pinned() {
        let scratch = Scratch::new("pin-first");
        let pins = scratch.path().join("hosts");

        assert_eq!(
            Trusted::Pinned,
            trust(&pins, "github.com", b"a key").unwrap()
        );
        assert!(
            std::fs::read_to_string(&pins)
                .unwrap()
                .contains("github.com"),
            "the host was reported pinned and is not in the file"
        );
    }

    #[test]
    fn the_same_host_answering_the_same_key_is_known() {
        let scratch = Scratch::new("pin-same");
        let pins = scratch.path().join("hosts");
        trust(&pins, "github.com", b"a key").unwrap();

        assert_eq!(
            Trusted::Known,
            trust(&pins, "github.com", b"a key").unwrap()
        );
    }

    #[test]
    fn a_changed_key_is_refused_and_nothing_is_written() {
        // The case the whole file exists for, and the assertion that matters
        // more than the refusal: a refused check must not quietly re-pin.
        let scratch = Scratch::new("pin-changed");
        let pins = scratch.path().join("hosts");
        trust(&pins, "github.com", b"a key").unwrap();
        let before = std::fs::read_to_string(&pins).unwrap();

        let Err(why) = trust(&pins, "github.com", b"another key") else {
            panic!("a changed host key was accepted")
        };
        assert!(matches!(why, Error::HostKeyChanged { .. }), "{why}");
        assert!(why.to_string().contains("github.com"), "{why}");
        assert_eq!(before, std::fs::read_to_string(&pins).unwrap());
    }

    #[test]
    fn one_host_does_not_disturb_another() {
        let scratch = Scratch::new("pin-two");
        let pins = scratch.path().join("hosts");
        trust(&pins, "github.com", b"one").unwrap();

        assert_eq!(Trusted::Pinned, trust(&pins, "gitlab.com", b"two").unwrap());
        assert_eq!(Trusted::Known, trust(&pins, "github.com", b"one").unwrap());
        assert_eq!(Trusted::Known, trust(&pins, "gitlab.com", b"two").unwrap());
    }

    #[test]
    fn a_line_that_cannot_be_read_is_a_line_that_is_not_there() {
        // A file somebody edited by hand should leave the phone working, which
        // is the reasoning modeFrom carries on the Kotlin side. The absent
        // trailing newline is the likeliest way an edit leaves it.
        let scratch = Scratch::new("pin-garbage");
        let pins = scratch.path().join("hosts");
        std::fs::write(
            &pins,
            "nonsense\n\ntoo many fields here now\ngithub.com abc",
        )
        .unwrap();

        assert_eq!(Trusted::Pinned, trust(&pins, "gitlab.com", b"two").unwrap());

        let after = std::fs::read_to_string(&pins).unwrap();
        assert!(after.contains("gitlab.com"), "{after}");
        assert!(
            after.contains("github.com abc"),
            "a line it could not use was thrown away: {after}"
        );
    }

    #[test]
    fn a_refusal_says_both_things_it_could_be() {
        // There is no prompt to offer, so the words are the whole interface.
        // Naming only the attack teaches somebody to ignore it the first time
        // a server is rebuilt, which is the commoner of the two by far.
        let said = Error::HostKeyChanged {
            host: "github.com".to_owned(),
        }
        .to_string();

        assert!(said.contains("in the middle"), "{said}");
        assert!(said.contains("rebuilt"), "{said}");
    }
}
