//! metrics.rs: counting what the router did.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `Metrics`  Counters, and their Prometheus rendering.
//!
//! The thresholds the policy ships with are a starting point, not a claim. This
//! is the evidence for retuning them; without it the only record of a decision is
//! a log line nobody aggregates.
//!
//! The distribution of *reasons* is the most useful thing here and the least
//! obvious. Cost tells you the bill; reasons tell you why. Mostly `unscored`
//! means the scorer is not working; much `context-too-large` means the threshold
//! is wrong; frequent fallbacks mean a tier's first choice is unhealthy. None of
//! that is visible from spend alone, where anyone would otherwise look first.
//!
//! Counters are atomic rather than locked: written every request, read almost
//! never, so the cost belongs on the read side.

use std::fmt::Write as _;
use std::sync::atomic::{AtomicU64, Ordering};

use crate::policy::Reason;
use crate::tier::Tier;

/// Every counter the router keeps.
///
/// # Atomic
/// Independent relaxed fetch-adds. A reader may see one counter incremented and
/// another not yet, which is correct here: these are rates over time, not an
/// invariant, and ordering them would tax every request for nothing.
#[derive(Debug, Default)]
pub struct Metrics {
    by_tier: [AtomicU64; Tier::ALL.len()],
    by_reason: [AtomicU64; Reason::ALL.len()],
    embeddings: AtomicU64,
    cache_hits: AtomicU64,
    fallbacks: AtomicU64,
    upstream_failures: AtomicU64,
    with_session: AtomicU64,
}

impl Metrics {
    /// A fresh set of counters, all zero.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Record a routing decision.
    ///
    /// # Atomic
    /// Two independent relaxed increments.
    pub fn record(&self, tier: Tier, reason: Reason) {
        self.by_tier[tier as usize].fetch_add(1, Ordering::Relaxed);
        self.by_reason[reason_index(reason)].fetch_add(1, Ordering::Relaxed);
    }

    /// Record that a prompt had to be embedded, having missed the cache.
    pub fn record_embedding(&self) {
        self.embeddings.fetch_add(1, Ordering::Relaxed);
    }

    /// Record a cached score reused. Paired with [`Self::record_embedding`] so
    /// the hit ratio, the argument for keeping the cache, is readable.
    pub fn record_cache_hit(&self) {
        self.cache_hits.fetch_add(1, Ordering::Relaxed);
    }

    /// Record that the first model in a chain was not the one that answered.
    pub fn record_fallback(&self) {
        self.fallbacks.fetch_add(1, Ordering::Relaxed);
    }

    /// Record that a request named the session it belongs to.
    ///
    /// Stickiness is the cache's larger win and it needs that header. Nothing in
    /// this repository sends one yet, so a zero here is the finding rather than
    /// the absence of one, and no response can report it, since a request with
    /// no session and one with a session never seen before route identically.
    pub fn record_session(&self) {
        self.with_session.fetch_add(1, Ordering::Relaxed);
    }

    /// Record that every model in a chain failed.
    pub fn record_upstream_failure(&self) {
        self.upstream_failures.fetch_add(1, Ordering::Relaxed);
    }

    /// Render in the Prometheus text format: the one thing every scraper reads,
    /// and legible with `curl` alone, which is how it will be read on a board.
    ///
    /// # Returns
    /// A complete exposition, valid even with every counter at zero: a series
    /// that appears only after its first event looks broken exactly when someone
    /// is checking whether it works.
    #[must_use]
    pub fn render(&self) -> String {
        let mut out = String::with_capacity(1024);

        out.push_str("# HELP wattrouter_requests_by_tier Requests routed to each tier.\n");
        out.push_str("# TYPE wattrouter_requests_by_tier counter\n");
        for tier in Tier::ALL {
            let n = self.by_tier[tier as usize].load(Ordering::Relaxed);
            let name = tier.name();
            let _ = writeln!(out, "wattrouter_requests_by_tier{{tier=\"{name}\"}} {n}");
        }

        out.push_str("# HELP wattrouter_requests_by_reason Why each tier was chosen.\n");
        out.push_str("# TYPE wattrouter_requests_by_reason counter\n");
        for reason in Reason::ALL {
            let n = self.by_reason[reason_index(reason)].load(Ordering::Relaxed);
            let label = reason.label();
            let _ = writeln!(
                out,
                "wattrouter_requests_by_reason{{reason=\"{label}\"}} {n}"
            );
        }

        for (name, help, value) in [
            (
                "wattrouter_embeddings_total",
                "Prompts embedded, having missed the score cache.",
                &self.embeddings,
            ),
            (
                "wattrouter_cache_hits_total",
                "Prompts served from the score cache.",
                &self.cache_hits,
            ),
            (
                "wattrouter_fallbacks_total",
                "Requests a tier's first-choice model did not serve.",
                &self.fallbacks,
            ),
            (
                "wattrouter_upstream_failures_total",
                "Requests for which every model in the chain failed.",
                &self.upstream_failures,
            ),
            // Its own series rather than a label on the two families above.
            // Carrying a session is a property of the request, not of the tier
            // it reached or the reason it got there, and widening either family
            // would change its cardinality for every scraper already reading it.
            (
                "wattrouter_requests_with_session_total",
                "Requests that named a session, and so could be made sticky.",
                &self.with_session,
            ),
        ] {
            let _ = writeln!(out, "# HELP {name} {help}");
            let _ = writeln!(out, "# TYPE {name} counter");
            let _ = writeln!(out, "{name} {}", value.load(Ordering::Relaxed));
        }

        out
    }
}

/// A stable array index for a reason. Written out rather than taken from the
/// discriminant, so reordering the enum cannot silently reassign a counter; the
/// numbers would keep flowing, into the wrong series.
const fn reason_index(reason: Reason) -> usize {
    match reason {
        Reason::Pinned => 0,
        Reason::Background => 1,
        Reason::ContextTooLarge => 2,
        Reason::Scored => 3,
        Reason::CodeShaped => 4,
        Reason::Unscored => 5,
        Reason::Sticky => 6,
    }
}

#[cfg(test)]
mod tests {
    use super::{Metrics, reason_index};
    use crate::policy::Reason;
    use crate::tier::Tier;

    #[test]
    fn every_series_is_present_before_anything_happens() {
        // A series that only appears after its first event looks broken exactly
        // when someone is checking whether it works.
        let rendered = Metrics::new().render();
        for tier in Tier::ALL {
            assert!(rendered.contains(&format!("tier=\"{}\"", tier.name())));
        }
        for reason in Reason::ALL {
            assert!(rendered.contains(&format!("reason=\"{}\"", reason.label())));
        }
        assert!(rendered.contains("wattrouter_cache_hits_total 0"));
        // The one that starts at zero and is supposed to: today nothing sends a
        // session, so this reads zero until that is fixed. It has to be present
        // to say so.
        assert!(rendered.contains("wattrouter_requests_with_session_total 0"));
    }

    #[test]
    fn a_session_is_counted_apart_from_the_tier_it_reached() {
        // Its own series, so a scraper already reading the two labelled families
        // does not see their cardinality change under it.
        let metrics = Metrics::new();
        metrics.record(Tier::Mid, Reason::Unscored);
        metrics.record(Tier::Mid, Reason::Unscored);
        metrics.record_session();

        let rendered = metrics.render();
        assert!(
            rendered.contains("wattrouter_requests_with_session_total 1"),
            "{rendered}"
        );
        assert!(rendered.contains("tier=\"mid\"} 2"), "{rendered}");
        assert!(
            !rendered.contains("session=\""),
            "session is a series, not a label: {rendered}"
        );
    }

    #[test]
    fn decisions_are_counted_against_both_dimensions() {
        let metrics = Metrics::new();
        metrics.record(Tier::Heavy, Reason::Scored);
        metrics.record(Tier::Heavy, Reason::Sticky);
        metrics.record(Tier::Cheap, Reason::Scored);

        let rendered = metrics.render();
        assert!(rendered.contains("tier=\"heavy\"} 2"), "{rendered}");
        assert!(rendered.contains("tier=\"cheap\"} 1"), "{rendered}");
        assert!(rendered.contains("reason=\"scored\"} 2"), "{rendered}");
        assert!(rendered.contains("reason=\"sticky\"} 1"), "{rendered}");
    }

    #[test]
    fn reason_indices_are_distinct() {
        // Two reasons sharing an index would merge two populations into one
        // series, and the numbers would keep flowing while meaning something
        // else entirely.
        let mut seen = std::collections::HashSet::new();
        for reason in Reason::ALL {
            assert!(
                seen.insert(reason_index(reason)),
                "duplicate for {reason:?}"
            );
        }
    }

    #[test]
    fn all_reasons_covers_the_enum() {
        // A variant added to Reason without extending ALL_REASONS would have its
        // series silently never exposed. reason_index is exhaustive, so its
        // highest index plus one is the true variant count.
        let highest = Reason::ALL.iter().map(|r| reason_index(*r)).max().unwrap();
        assert_eq!(Reason::ALL.len(), highest + 1);
    }

    #[test]
    fn counters_survive_concurrent_writers() {
        let metrics = std::sync::Arc::new(Metrics::new());
        let mut handles = Vec::new();
        for _ in 0..4 {
            let metrics = std::sync::Arc::clone(&metrics);
            handles.push(std::thread::spawn(move || {
                for _ in 0..500 {
                    metrics.record(Tier::Mid, Reason::Scored);
                    metrics.record_cache_hit();
                }
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        let rendered = metrics.render();
        assert!(rendered.contains("tier=\"mid\"} 2000"), "{rendered}");
        assert!(
            rendered.contains("wattrouter_cache_hits_total 2000"),
            "{rendered}"
        );
    }
}
