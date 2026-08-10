//! tokens.rs — who is allowed to ask.
//!
//! History
//!   2026-08-09  A. Sigdel  Created with #533.
//!
//! Contents
//!   `Caller`  Who a token belongs to.
//!   `Tokens`  What decides whether a token is one of ours.
//!   `Listed`  The set given at startup, which is what ships first.
//!
//! `SECURITY.md` called an exposed router out of scope, and that was right while
//! it ran on a board on a desk. It stops being right the moment a phone talks to
//! it over the internet — the application ships to other people, so the provider
//! key cannot be in the APK, so this holds it, so this is reachable.
//!
//! A trait rather than a set, and the reason is the next unit rather than
//! taste: accounts arrive in Postgres, and a lookup behind a seam becomes one
//! more implementation instead of a change to every handler. `Listed` is the
//! implementation that makes the server deployable today.
//!
//! Comparison is constant-time. A set lookup on a `String` compares byte by byte
//! and returns on the first difference, which over enough requests says how much
//! of a guess was right. That is a long way from practical against a random
//! token, and it costs one dependency-free function to not have the argument.

use std::collections::HashMap;

/// Who a token belongs to.
///
/// A name rather than an identifier, because the only thing this is used for
/// today is saying which caller a log line or a refusal is about. Accounts
/// bring an identity worth being careful with; this is deliberately not one.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Caller {
    /// What to call them. Never the token, which must not reach a log.
    pub label: String,
}

/// What decides whether a token is one of ours.
///
/// # Rely
/// Called on the request path, before a body is read, so an implementation that
/// blocks belongs behind a cache. `Listed` does not block.
///
/// `Debug` is required because the server's state derives it. An implementation
/// must not print a token in that impl: the whole point of this type is that
/// tokens do not reach a log, and a derived `Debug` over a map of them would.
pub trait Tokens: Send + Sync + std::fmt::Debug {
    /// Who this token belongs to, or `None` if it belongs to nobody.
    fn caller(&self, presented: &str) -> Option<Caller>;
}

/// The tokens given at startup.
#[derive(Debug, Default)]
pub struct Listed {
    /// Label by token. Not the other way round: the lookup is by what arrived.
    known: HashMap<String, String>,
}

impl Listed {
    /// Read `WATTROUTER_TOKENS`, which is `label:token` pairs separated by commas.
    ///
    /// Empty when the variable is absent, and that is a refusal rather than an
    /// opening: a server with no tokens answers nothing on `/v1`, which is
    /// noisy and safe. The alternative — no tokens meaning no checking — is the
    /// configuration mistake that puts an unmetered proxy to a paid provider on
    /// the internet.
    ///
    /// A pair without a colon, or with an empty half, is skipped rather than
    /// fatal. One malformed entry in a list of ten should not stop a deployment
    /// that has nine working ones, and `len` is what an operator checks.
    #[must_use]
    pub fn from_env() -> Self {
        Self::parse(&std::env::var("WATTROUTER_TOKENS").unwrap_or_default())
    }

    /// As [`Self::from_env`], from a string already in hand. Reachable from a test.
    #[must_use]
    pub fn parse(listed: &str) -> Self {
        let known = listed
            .split(',')
            .filter_map(|pair| {
                let (label, token) = pair.split_once(':')?;
                let (label, token) = (label.trim(), token.trim());
                if label.is_empty() || token.is_empty() {
                    return None;
                }
                Some((token.to_owned(), label.to_owned()))
            })
            .collect();
        Self { known }
    }

    /// How many were understood. What an operator compares against what they set.
    #[must_use]
    pub fn len(&self) -> usize {
        self.known.len()
    }

    /// Whether none were.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.known.is_empty()
    }
}

impl Tokens for Listed {
    fn caller(&self, presented: &str) -> Option<Caller> {
        // Every entry is compared, and the first match is not returned early.
        // A map lookup would be faster and would leak which prefix matched
        // through how long it took.
        let mut found = None;
        for (token, label) in &self.known {
            if constant_time_eq(token.as_bytes(), presented.as_bytes()) {
                found = Some(Caller {
                    label: label.clone(),
                });
            }
        }
        found
    }
}

/// Whether two byte strings are equal, in time that depends only on length.
///
/// Length is not hidden and does not need to be: a token's length is fixed by
/// whoever issues it, and knowing it reveals nothing about its content.
fn constant_time_eq(one: &[u8], two: &[u8]) -> bool {
    if one.len() != two.len() {
        return false;
    }
    let mut differing = 0u8;
    for (a, b) in one.iter().zip(two) {
        differing |= a ^ b;
    }
    differing == 0
}

/// Pull the token out of an `Authorization` header.
///
/// Case-insensitive on the scheme because clients disagree about it, and the
/// argument over whose fault that is costs more than accepting both.
#[must_use]
pub fn presented(header: Option<&str>) -> Option<&str> {
    let value = header?.trim();
    let (scheme, token) = value.split_once(' ')?;
    if !scheme.eq_ignore_ascii_case("bearer") {
        return None;
    }
    let token = token.trim();
    if token.is_empty() { None } else { Some(token) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_listed_token_names_its_caller() {
        let listed = Listed::parse("phone:abc123,laptop:def456");

        assert_eq!(listed.len(), 2);
        assert_eq!(
            listed.caller("abc123"),
            Some(Caller {
                label: "phone".to_owned()
            })
        );
        assert_eq!(
            listed.caller("def456"),
            Some(Caller {
                label: "laptop".to_owned()
            })
        );
    }

    #[test]
    fn anything_else_belongs_to_nobody() {
        let listed = Listed::parse("phone:abc123");

        assert_eq!(listed.caller("abc124"), None);
        assert_eq!(listed.caller("abc12"), None);
        assert_eq!(listed.caller("abc1234"), None);
        assert_eq!(listed.caller(""), None);
    }

    #[test]
    fn no_tokens_means_nobody_rather_than_everybody() {
        // The configuration mistake this exists to prevent: an empty list read
        // as "checking is off" puts an unmetered proxy to a paid provider on
        // the internet.
        let listed = Listed::parse("");

        assert!(listed.is_empty());
        assert_eq!(listed.caller("anything"), None);
        assert_eq!(listed.caller(""), None);
    }

    #[test]
    fn one_malformed_pair_does_not_take_the_others_with_it() {
        // Nine working tokens should survive a tenth with a typo in it.
        let listed = Listed::parse("phone:abc,nonsense,:novalue,nolabel:,laptop:def");

        assert_eq!(listed.len(), 2);
        assert!(listed.caller("abc").is_some());
        assert!(listed.caller("def").is_some());
    }

    #[test]
    fn whitespace_around_a_pair_is_not_part_of_it() {
        // An operator writing a list across lines in a dashboard field.
        let listed = Listed::parse(" phone : abc , laptop : def ");

        assert!(listed.caller("abc").is_some());
        assert!(listed.caller("def").is_some());
        assert_eq!(listed.caller(" abc "), None);
    }

    #[test]
    fn a_bearer_header_gives_up_its_token() {
        assert_eq!(presented(Some("Bearer abc123")), Some("abc123"));
        assert_eq!(presented(Some("bearer abc123")), Some("abc123"));
        assert_eq!(presented(Some("BEARER  abc123 ")), Some("abc123"));
    }

    #[test]
    fn anything_that_is_not_a_bearer_header_gives_up_nothing() {
        assert_eq!(presented(None), None);
        assert_eq!(presented(Some("")), None);
        assert_eq!(presented(Some("abc123")), None);
        assert_eq!(presented(Some("Basic abc123")), None);
        assert_eq!(presented(Some("Bearer")), None);
        assert_eq!(presented(Some("Bearer ")), None);
    }

    #[test]
    fn equality_is_decided_by_content_and_not_by_prefix() {
        assert!(constant_time_eq(b"same", b"same"));
        assert!(!constant_time_eq(b"same", b"sane"));
        assert!(!constant_time_eq(b"same", b"sam"));
        assert!(!constant_time_eq(b"", b"x"));
        assert!(constant_time_eq(b"", b""));
    }
}
