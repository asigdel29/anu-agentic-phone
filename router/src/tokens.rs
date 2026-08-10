//! tokens.rs: who is allowed to ask.
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
//! it over the internet: the application ships to other people, so the provider
//! key cannot be in the APK, so this holds it, so this is reachable.
//!
//! A trait rather than a set, and the reason is the next unit rather than
//! taste: accounts arrive in Postgres, and a lookup behind a seam becomes one
//! more implementation instead of a change to every handler. `Listed` is the
//! implementation that makes the server deployable today, and #534 is where
//! the server started refusing anything without one.
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
    /// noisy and safe. The alternative, no tokens meaning no checking, is the
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

/// A token as it was issued, ready to be cached.
///
/// The hash rather than the token, because that is what the database holds and
/// what arrives here. A token is shown once, when it is issued, and nothing can
/// recover it afterwards, so a leaked dump is not a leaked account.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Issued {
    /// Lowercase hex of the SHA-256 of the token.
    pub hashed: String,
    /// Who it belongs to.
    pub label: String,
}

/// What a refresh did, for a caller that wants to log it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refreshed {
    /// The cache now holds this many tokens.
    Replaced(usize),
    /// The load failed and this many were kept.
    Kept(usize),
}

/// Tokens held in memory and replaced on a timer.
///
/// The shape [`Tokens`] asks for. `caller` is on the request path before a body
/// is read, and its documentation says an implementation that blocks belongs
/// behind a cache, so this is that cache, and a lookup is a hash and a map read
/// with no I/O at all.
///
/// **Revocation takes up to one refresh interval.** That is the price of the
/// paragraph above and it is stated here rather than discovered: somebody
/// revoking a leaked token needs to know whether it is off now or soon.
///
/// # Atomic
/// One lock, taken for reading in `caller` and for writing in `refresh`. No path
/// takes it twice and there is no second lock to order against.
#[derive(Debug, Default)]
pub struct Cached {
    live: std::sync::RwLock<HashMap<String, String>>,
}

impl Cached {
    /// A cache holding nothing, which refuses everything.
    #[must_use]
    pub fn empty() -> Self {
        Self::default()
    }

    /// Take the result of a load, and decide what the cache holds now.
    ///
    /// A failed refresh **keeps what it had**. A database blip should not sign
    /// everybody out; the cache is still the best information available, and the
    /// alternative is an outage caused by a network that recovered on its own.
    ///
    /// A first load that fails therefore leaves it empty, and empty is nobody:
    /// the rule [`Listed`] follows for an absent variable, and for the same
    /// reason: a server that answers nothing is noisy and safe, where one that
    /// cannot tell whether it checked is not.
    ///
    /// # Atomic
    /// Takes the write lock for the swap only. A reader either sees every new
    /// token or every old one.
    ///
    /// # Panics
    /// IF the lock is poisoned, which means a thread panicked while holding it.
    /// Carrying on would mean serving from a cache nobody can describe, and the
    /// only thing this lock protects is who may spend the provider credit.
    pub fn refresh<E>(&self, loaded: Result<Vec<Issued>, E>) -> Refreshed {
        match loaded {
            Ok(issued) => {
                let replacement: HashMap<String, String> = issued
                    .into_iter()
                    .map(|one| (one.hashed.to_lowercase(), one.label))
                    .collect();
                let count = replacement.len();
                *self
                    .live
                    .write()
                    .expect("the token cache lock is not poisoned") = replacement;
                Refreshed::Replaced(count)
            }
            Err(_) => Refreshed::Kept(self.len()),
        }
    }

    /// How many tokens are live.
    ///
    /// # Atomic
    /// Takes the read lock.
    ///
    /// # Panics
    /// As [`Self::refresh`].
    #[must_use]
    pub fn len(&self) -> usize {
        self.live
            .read()
            .expect("the token cache lock is not poisoned")
            .len()
    }

    /// Whether none are.
    ///
    /// # Atomic
    /// As [`Self::len`].
    ///
    /// # Panics
    /// As [`Self::refresh`].
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

impl Tokens for Cached {
    fn caller(&self, presented: &str) -> Option<Caller> {
        // No constant-time comparison here, and its absence is the design
        // rather than an omission: the key is a hash of the secret, so what
        // leaks through timing is which hash was looked up, and an attacker who
        // has the hash already has everything the map holds.
        let label = self
            .live
            .read()
            .expect("the token cache lock is not poisoned")
            .get(&hashed(presented))?
            .clone();
        Some(Caller { label })
    }
}

/// Lowercase hex of the SHA-256 of a token.
///
/// Not a password KDF. A token is thirty-two random bytes this server issued, so
/// there is no dictionary to run against it and nothing a work factor would
/// buy: it would cost every request the time it is meant to cost an attacker.
#[must_use]
pub fn hashed(token: &str) -> String {
    use sha2::Digest as _;
    use std::fmt::Write as _;

    let digest = sha2::Sha256::digest(token.as_bytes());
    digest
        .iter()
        .fold(String::with_capacity(64), |mut hex, byte| {
            // write! into a String cannot fail, and the result is discarded for
            // that reason rather than out of carelessness.
            let _ = write!(hex, "{byte:02x}");
            hex
        })
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
    /// A token, and the entry a database would hold for it.
    fn issued(token: &str, label: &str) -> Issued {
        Issued {
            hashed: hashed(token),
            label: label.to_owned(),
        }
    }

    #[test]
    fn a_cached_token_names_its_caller() {
        let cached = Cached::empty();

        cached.refresh(Ok::<_, ()>(vec![issued("secret", "phone")]));

        assert_eq!(
            cached.caller("secret"),
            Some(Caller {
                label: "phone".to_owned()
            })
        );
        assert_eq!(cached.caller("other"), None);
    }

    #[test]
    fn an_empty_cache_is_nobody_rather_than_everybody() {
        // The rule Listed follows for an absent variable, and the state a first
        // load that failed leaves behind.
        let cached = Cached::empty();

        assert!(cached.is_empty());
        assert_eq!(cached.caller("anything"), None);
    }

    #[test]
    fn a_failed_refresh_keeps_what_it_had() {
        // A database blip should not sign everybody out. The cache is still the
        // best information available, and the alternative is an outage caused by
        // a network that recovered on its own.
        let cached = Cached::empty();
        cached.refresh(Ok::<_, ()>(vec![issued("secret", "phone")]));

        let what = cached.refresh(Err::<Vec<Issued>, _>("the database is unreachable"));

        assert_eq!(what, Refreshed::Kept(1));
        assert!(cached.caller("secret").is_some());
    }

    #[test]
    fn a_successful_refresh_removes_what_is_gone() {
        // The half revocation depends on: a token absent from a load stops
        // working, which is what makes the refresh interval the revocation
        // delay rather than a detail.
        let cached = Cached::empty();
        cached.refresh(Ok::<_, ()>(vec![
            issued("old", "phone"),
            issued("new", "laptop"),
        ]));

        let what = cached.refresh(Ok::<_, ()>(vec![issued("new", "laptop")]));

        assert_eq!(what, Refreshed::Replaced(1));
        assert_eq!(cached.caller("old"), None);
        assert!(cached.caller("new").is_some());
    }

    #[test]
    fn a_hash_is_not_the_token_and_does_not_carry_it() {
        // What the database holds. A leaked dump is not a leaked account.
        let token = "a-token-somebody-was-issued";

        let digest = hashed(token);

        assert_eq!(digest.len(), 64);
        assert!(!digest.contains(token));
        assert_eq!(digest, hashed(token));
        assert_ne!(digest, hashed("a-token-somebody-was-issued "));
    }

    #[test]
    fn a_stored_hash_in_either_case_is_the_same_hash() {
        // A dump, a migration or a person may have upper-cased it. Refusing a
        // valid token over the case of its hex is a support conversation.
        let cached = Cached::empty();
        cached.refresh(Ok::<_, ()>(vec![Issued {
            hashed: hashed("secret").to_uppercase(),
            label: "phone".to_owned(),
        }]));

        assert!(cached.caller("secret").is_some());
    }
}
