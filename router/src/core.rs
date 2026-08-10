//! core.rs — the decision core, as anything in this process calls it.
//!
//! History
//!   2026-08-06  A. Sigdel  Created.
//!   2026-08-10  A. Sigdel  Stopped naming iOS with #564. The header and the
//!                          module map went with it; the shape here did not,
//!                          and #565 is where that is argued.
//!   2026-08-10  A. Sigdel  Nothing here is a C interface any more with #565.
//!   2026-08-10  A. Sigdel  Was `ffi.rs`, which by then named the one thing it
//!                          no longer was.
//!
//! Contents
//!   `Router`   Build one, decide with it, read a tier's chain off it.
//!   `Decision` Which tier, why, and how hard the prompt looked.
//!
//! **The name shadows the `core` crate inside this one.** Nothing here reaches
//! for it, so nothing broke; a later `core::mem::swap` would resolve to this
//! module and fail in a way that names neither. Write `::core` where the crate
//! is meant. The alternative was a name that describes the layer worse in order
//! to avoid a collision the compiler reports.
//!
//! In a process with the agent there is one address space, so the proxy the
//! server binary is would be the wrong shape here: no socket, no TLS, no
//! connection pool.
//!
//! **There is no boundary in this file.** There was, and everything about the
//! shape was owed to it: pointers a caller had to free, a struct returned by
//! value with a reserved failure value inside it, numeric codes for names, and
//! a `catch_unwind` on every entry because a panic crossing into C is undefined
//! behaviour. The caller those were for left in #545 and the last of the
//! machinery in #565.
//!
//! What guards the real boundary is `jni.rs`, which owns the handle it hands
//! Kotlin and catches its own panics. This is the ordinary Rust behind it.

use crate::backend::Backend;
use crate::cache::DecisionCache;
use crate::chain::chain_for;
use crate::classify::classify;
use crate::config::Config;
use crate::embed::{Embedder, HashEmbedder};
use crate::head::Head;
use crate::policy::{Reason, Thresholds, decide};
use crate::tier::Tier;
use std::path::Path;

/// Everything a decision needs; opaque to C.
pub struct Router {
    /// Every tier's chain, resolved once at construction.
    ///
    /// A decision names a tier, which is a role; what answers is the chain
    /// behind it. Resolved here rather than per call because a chain outlives
    /// the configuration it was read from, and because rebuilding one per
    /// decision would read the environment from whichever thread asked, which
    /// `config.rs` forbids and #476 removed. Six tiers of at most three entries,
    /// built once.
    chains: [Vec<ChainEntry>; Tier::ALL.len()],
    thresholds: Thresholds,
    embedder: HashEmbedder,
    cache: DecisionCache,
    head: Option<Head>,
}

/// One attempt: a model to ask, and where to run it.
///
/// [`crate::chain::Step`] borrows its name from the configuration it was read
/// from; this owns a copy, because the chains outlive that configuration.
pub(crate) struct ChainEntry {
    /// Where this one runs: in this process, or over the network.
    pub(crate) backend: Backend,
    /// The model to ask, as configuration spells it.
    pub(crate) model: String,
}

/// A decision: which tier will serve a request, why, and how hard the prompt
/// looked.
///
/// Absence is [`Option`] rather than a `tier` of `255`, and the fields are the
/// enums rather than numbers standing for them.
///
/// The numbers existed so that a caller compiled against one could not be broken
/// by an enum being reordered here. No such caller exists. What they cost while
/// they lasted was a second spelling of every name, in a table beside the one
/// the enum already carried, kept in step by a test.
#[derive(Debug, Clone, Copy)]
pub struct Decision {
    /// Which tier serves the request.
    pub tier: Tier,
    /// Why that tier.
    pub reason: Reason,
    /// Difficulty in `[0, 1]`, or [`None`] where nothing scored it.
    ///
    /// [`None`] rather than `-1.0`. A number meaning "no number" is one a caller
    /// compares against a threshold by accident.
    pub score: Option<f32>,
}

impl Router {
    /// Build a router, configured from the environment as the server is: one way
    /// to configure the core rather than two that can disagree.
    ///
    /// # Arguments
    /// * `head_path` — where to load the scoring head from. [`None`] takes the
    ///   configured default. The app passes a path because it keeps weights in
    ///   its own sandbox, which that default cannot reach.
    ///
    /// # Returns
    /// [`None`] IF configuration was rejected. A missing head is not that: the
    /// policy has an unscored path and a phone has no head to load.
    pub(crate) fn new(head_path: Option<&Path>) -> Option<Self> {
        let config = Config::from_env().ok()?;
        let embedder = HashEmbedder::new();

        let path = head_path.map_or_else(
            || config.head_path().to_path_buf(),
            std::path::Path::to_path_buf,
        );

        let head = Head::load(&path, &embedder.id()).ok();
        let thresholds = head
            .as_ref()
            .and_then(Head::thresholds)
            .and_then(|(cheap, mid)| Thresholds::new(cheap, mid))
            .unwrap_or_default();

        let chains = Tier::ALL.map(|tier| {
            chain_for(&config, tier)
                .into_iter()
                .map(|step| ChainEntry {
                    backend: step.backend(),
                    model: step.model().to_owned(),
                })
                .collect()
        });

        Some(Self {
            chains,
            thresholds,
            embedder,
            cache: DecisionCache::new(),
            head,
        })
    }

    /// Decide which tier serves a request: classify, score, apply policy, apply
    /// session stickiness. The server's ordering, because it is the server's
    /// code.
    ///
    /// One entry point, not one per stage: exposing `classify`, `score` and
    /// `decide` separately would move their ordering into the caller, where it
    /// would drift from the server's.
    ///
    /// # Arguments
    /// * `body_json` — an OpenAI-shaped chat completion request.
    /// * `pin` — a tier name to force. Nothing on a phone supplies one; the
    ///   server does, from a header, and the parameter stays so that both reach
    ///   the policy the same way.
    /// * `session` — a session identifier for stickiness.
    ///
    /// # Returns
    /// [`None`] IF the body is not an OpenAI-shaped chat completion. That was
    /// one of four conditions behind a single sentinel; the other three were
    /// a null router and a body that was null or not UTF-8, none of which a
    /// `&str` and a `&self` can be, and a panic, which now reaches the caller's
    /// own guard instead of being reported here as an undecidable request.
    pub(crate) fn decide(
        &self,
        body_json: &str,
        pin: Option<&str>,
        session: &str,
    ) -> Option<Decision> {
        let body = serde_json::from_str::<serde_json::Value>(body_json).ok()?;
        let classified = classify(&body, pin);

        let score = self.head.as_ref().and_then(|head| {
            if classified.text.is_empty() {
                return None;
            }
            if let Some(cached) = self.cache.score_for(&classified.text) {
                return Some(cached);
            }
            let vector = self.embedder.embed(&classified.text).ok()?;
            let score = head.score(&vector);
            self.cache.remember_score(&classified.text, score);
            Some(score)
        });

        let mut decision = decide(&classified.signals, score, &self.thresholds);
        if decision.reason != Reason::Pinned {
            let effective = self.cache.escalate(session, decision.tier);
            if effective > decision.tier {
                decision = crate::policy::Decision::new(effective, Reason::Sticky);
            }
        }
        Some(Decision {
            tier: decision.tier,
            reason: decision.reason,
            score,
        })
    }

    /// `tier`'s chain, in the order it will be tried, or an empty slice IF
    /// `tier` names none.
    ///
    /// One slice rather than a length and two indexed lookups. Those existed so
    /// a C caller could walk a chain without owning anything; the only caller
    /// left is in this process and holds a borrow of the router already, so the
    /// indirection bought nothing and cost three entry points and a walk that
    /// could disagree with itself between calls.
    pub(crate) fn chain(&self, tier: Tier) -> &[ChainEntry] {
        self.chains.get(tier as usize).map_or(&[], Vec::as_slice)
    }
}

#[cfg(test)]
mod tests {
    use super::{Decision, Router};
    use crate::policy::Reason;
    use crate::testenv::with_env;
    use crate::tier::Tier;

    /// One router may be shared across threads, and nothing but this decides
    /// that claim: the cache is behind a mutex and everything else is read-only
    /// after construction.
    ///
    /// The header used to be what rested on it. `Core.kt` is what rests on it
    /// now: `decide` runs under `@Synchronized` on whatever dispatcher
    /// `Core.routing` moved it to, while `close` may run on another, which is
    /// what #474 was about. If that stops being true this stops compiling.
    const _: fn() = || {
        fn assert_shareable<T: Sync + Send>() {}
        assert_shareable::<Router>();
    };

    /// Build a router as the app would. Dropping it is the whole of releasing it
    /// now, so nothing here has to remember to.
    ///
    /// Holds the crate-wide environment lock for the whole of `body`, not just
    /// the construction. `Router::new` reads the environment, and so does any
    /// `Config::from_env` a body makes to compare against — the credential
    /// vanishing between the two is what failed CI, and a stray
    /// `WATTROUTER_MODEL_*` from a concurrent test would have been worse, since
    /// that compares two different configurations and reports a wrong answer
    /// rather than a missing one.
    fn with_router<T>(body: impl FnOnce(&Router) -> T) -> T {
        with_env(&[("NEURALWATT_API_KEY", Some("ffi-test"))], || {
            let router = Router::new(None).expect("router builds without a head");
            body(&router)
        })
    }

    fn decide(router: &Router, json: &str, pin: Option<&str>, sess: &str) -> Decision {
        router.decide(json, pin, sess).expect("a decidable request")
    }

    #[test]
    fn a_decision_crosses_the_boundary() {
        with_router(|router| {
            let d = decide(
                router,
                r#"{"messages":[{"role":"user","content":"hello there"}]}"#,
                None,
                "",
            );
            assert_eq!(d.tier, Tier::Mid, "unscored lands in the middle");
        });
    }

    #[test]
    fn the_policy_rules_survive_the_boundary() {
        with_router(|router| {
            // Rules the server tests already cover, asserted again through C to
            // prove the crossing preserves them.
            let pinned = decide(
                router,
                r#"{"messages":[{"role":"user","content":"x"}]}"#,
                Some("cheap"),
                "",
            );
            assert_eq!(pinned.tier, Tier::Cheap);
            assert_eq!(pinned.reason, Reason::Pinned);

            let background = decide(
                router,
                r#"{"messages":[{"role":"user","content":"t"}],"max_tokens":16}"#,
                None,
                "",
            );
            assert_eq!(background.tier, Tier::Aux);
            assert_eq!(background.reason, Reason::Background);
        });
    }

    #[test]
    fn a_body_that_is_not_a_request_is_absence_rather_than_a_fault() {
        // These had to answer with a sentinel, because a panic across a C
        // boundary is undefined behaviour and there was no other way to say
        // "no decision" in a struct returned by value. The answer is `None`
        // now, which a caller cannot mistake for a tier.
        //
        // A null router is no longer one of the cases: `decide` takes `&self`,
        // so the condition is unrepresentable rather than handled. Nor is
        // freeing one twice, which this used to assert was survivable: the
        // handle and its `Box` live in `jni.rs` now, and `Core.kt` is what may
        // clear a field twice.
        with_router(|router| {
            for bad in ["not json", ""] {
                assert!(router.decide(bad, None, "").is_none(), "for {bad:?}");
            }
        });
    }

    #[test]
    fn the_vocabulary_a_decision_crosses_with_is_usable_as_a_key() {
        // What the three deleted tests defended, replaced rather than dropped.
        //
        // They held two hand-written tables of names in step with the enums, and
        // held the codes indexing them distinct. There are no tables and no
        // codes, so "reordering the enum must not change what a number means" is
        // no longer something anything can break.
        //
        // What took its place is that the wire carries the names themselves, so
        // the property that matters moved: a name must exist for every variant,
        // and no two variants may spell theirs the same way. `Decision.kt`
        // matches these as strings, and two tiers sharing a name would be a
        // decision it silently mis-read rather than one it rejected.
        let mut names = std::collections::HashSet::new();
        for tier in Tier::ALL {
            assert!(!tier.name().is_empty(), "{tier:?} has no name");
            assert!(
                names.insert(tier.name()),
                "two tiers spell it {}",
                tier.name()
            );
        }

        let mut labels = std::collections::HashSet::new();
        for reason in Reason::ALL {
            assert!(!reason.label().is_empty(), "{reason:?} has no label");
            assert!(
                labels.insert(reason.label()),
                "two reasons spell it {}",
                reason.label()
            );
        }
    }

    #[test]
    fn a_chain_is_the_one_the_server_derived() {
        with_router(|router| {
            // `with_router` has already set the credential the config needs.
            let config = crate::config::Config::from_env().expect("valid");

            // Every tier, not one: a chain is reached by tier code, and a code
            // mishandled in particular would be invisible in a check of a single
            // tier. Compared against `chain_for` rather than against written-out
            // names, so a change to the catalogue moves this with it instead of
            // breaking it.
            for tier in Tier::ALL {
                let expected = crate::chain::chain_for(&config, tier);
                assert!(expected.len() > 1, "{} has no fallback", tier.name());

                let got = router.chain(tier);
                assert_eq!(got.len(), expected.len(), "for {}", tier.name());

                for (index, step) in expected.iter().enumerate() {
                    assert_eq!(
                        got[index].model,
                        step.model(),
                        "{} at index {index}",
                        tier.name()
                    );
                    assert_eq!(
                        got[index].backend,
                        step.backend(),
                        "{} at index {index}",
                        tier.name()
                    );
                }
            }
        });
    }
}
