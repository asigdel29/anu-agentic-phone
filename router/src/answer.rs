//! `answer.rs`: the envelope git and memory answer with.
//!
//! History
//!   2026-08-08  A. Sigdel  Created, from core_git.rs, when memory became the
//!                          second caller.
//!   2026-08-10  A. Sigdel  Builds JSON rather than C strings with #565, then
//!                          stopped making them at all. The panic guard, the
//!                          borrow, the no-answer case and the free went with
//!                          the last caller that needed a pointer.
//!
//! Contents
//!   `Answer`               Ok, or a refusal in words the model reads.
//!   `rendered`, `refused`  Building one.
//!
//! This lived in `core_git.rs` because git was the first thing here that had to
//! allocate and there was nothing to share it with. Memory is the second.
//!
//! Duplicating it would be two envelopes that agree today and disagree the day
//! one changes. Reaching into `core_git` from `core_memory` would make the memory
//! feature require the git feature, which is untrue and would put libgit2 into a
//! build that wanted a database. So it is here, gated on either feature.
//!
//! `core.rs` answers a `Decision`, which is three fields and needs no envelope.
//! That is the line this module is on the other side of, and it is now the only
//! difference between them: nothing here allocates on a caller's behalf.

#![allow(clippy::doc_markdown)]

use serde::Serialize;

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
