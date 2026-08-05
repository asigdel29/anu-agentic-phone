//! policy.rs — choosing a tier.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `Signals`          What the request told us, apart from its difficulty.
//!   `Thresholds`       Score boundaries between tiers.
//!   `Reason`           Why a tier was chosen.
//!   `Decision`         A tier and its reason.
//!   `decide`           The whole policy, in one function.
//!
//! Pure logic: no I/O, no scoring, no HTTP. Everything deciding where a request
//! goes is here and readable in one sitting, because a routing policy spread
//! across a request handler is one nobody can reason about.
//!
//! Every decision carries its reason: one that cannot be explained cannot be
//! debugged, and the reason is what reaches metrics and the response header.

use crate::tier::Tier;

/// Context size beyond which only the long tier can serve a request.
///
/// Below the smaller tiers' real limits on purpose: being early costs a little
/// money, being late means the upstream refuses the request and costs the turn.
pub const LONG_CONTEXT_TOKENS: usize = 190_000;

/// What the request told us, apart from how hard it looks.
///
/// Kept separate from the score so the capability rules still apply when no
/// score could be produced.
#[derive(Debug, Clone, Copy, Default)]
pub struct Signals {
    /// Approximate size of the whole conversation.
    pub estimated_tokens: usize,
    /// The prompt contains code, a diff, or a stack trace.
    pub has_code: bool,
    /// Housekeeping issued by the agent rather than asked for by a person.
    pub is_background: bool,
    /// A tier named explicitly by the caller.
    pub pinned: Option<Tier>,
}

/// Score boundaries between tiers.
///
/// A score is in `[0, 1]`, higher meaning harder. Defaults aim at roughly half
/// the traffic cheap, a third middle, the rest heavy — retune from real traffic.
#[derive(Debug, Clone, Copy)]
pub struct Thresholds {
    cheap_max: f32,
    mid_max: f32,
}

impl Thresholds {
    /// Build a threshold set.
    ///
    /// # Arguments
    /// * `cheap_max` — top of the cheap band, WHERE `0 <= cheap_max < mid_max`.
    /// * `mid_max` — top of the middle band, WHERE `mid_max <= 1`.
    ///
    /// # Returns
    /// `Some` IF the bounds are ordered and in range. An unordered set is
    /// unconstructible rather than discouraged: it would silently strand a tier,
    /// which nothing downstream would notice.
    #[must_use]
    pub fn new(cheap_max: f32, mid_max: f32) -> Option<Self> {
        let ordered = cheap_max < mid_max;
        let in_range = (0.0..=1.0).contains(&cheap_max) && (0.0..=1.0).contains(&mid_max);
        (ordered && in_range).then_some(Self { cheap_max, mid_max })
    }
}

impl Default for Thresholds {
    fn default() -> Self {
        Self {
            cheap_max: 0.50,
            mid_max: 0.85,
        }
    }
}

/// Why a tier was chosen.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reason {
    /// The caller named the tier.
    Pinned,
    /// Housekeeping, not a person waiting.
    Background,
    /// Nothing else can hold the context.
    ContextTooLarge,
    /// The score selected the band.
    Scored,
    /// Code-shaped work, promoted out of the middle band.
    CodeShaped,
    /// No score was available.
    Unscored,
    /// The session had already settled on a higher tier.
    Sticky,
}

impl Reason {
    /// A stable label, for metrics and the response header.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Pinned => "pinned",
            Self::Background => "background",
            Self::ContextTooLarge => "context-too-large",
            Self::Scored => "scored",
            Self::CodeShaped => "code-shaped",
            Self::Unscored => "unscored",
            Self::Sticky => "sticky",
        }
    }
}

/// A tier and why it was chosen.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Decision {
    /// The tier that will serve the request.
    pub tier: Tier,
    /// Why this tier and not another.
    pub reason: Reason,
}

impl Decision {
    /// Pair a tier with its reason.
    #[must_use]
    pub const fn new(tier: Tier, reason: Reason) -> Self {
        Self { tier, reason }
    }
}

/// Choose a tier.
///
/// Precedence, highest first. Several rules can apply at once, so the order is
/// part of the contract rather than an artefact of how this is written:
/// pin, then background, then context size, then the score, then the no-score
/// default. Each rule's reasoning sits with it below.
///
/// # Arguments
/// * `score` — `Some(s)` WHERE `s` is in `[0, 1]`, higher meaning harder. Values
///   outside the range are clamped rather than rejected: a broken head should
///   degrade routing, not fail requests.
///
/// # Returns
/// The tier to use and the reason, always. There is no failure mode; a router
/// that cannot decide has nothing useful to do with the request.
#[must_use]
pub fn decide(signals: &Signals, score: Option<f32>, thresholds: &Thresholds) -> Decision {
    if let Some(tier) = signals.pinned {
        return Decision {
            tier,
            reason: Reason::Pinned,
        };
    }

    if signals.is_background {
        return Decision::new(Tier::Aux, Reason::Background);
    }

    // A capability limit, not a preference: no score justifies sending 400K
    // tokens somewhere that cannot hold them.
    if signals.estimated_tokens > LONG_CONTEXT_TOKENS {
        return Decision::new(Tier::Long, Reason::ContextTooLarge);
    }

    let Some(score) = score else {
        // The middle tier, not the cheapest: under-serving a hard prompt costs a
        // wrong answer, over-serving an easy one costs a fraction of a cent.
        return Decision::new(Tier::Mid, Reason::Unscored);
    };

    let score = score.clamp(0.0, 1.0);

    if score > thresholds.mid_max {
        return Decision::new(Tier::Heavy, Reason::Scored);
    }

    if score > thresholds.cheap_max {
        // Code in the middle band goes to the code tier, which is the same price
        // bracket but specialised. Below the band it does not: a one-line
        // question about a snippet is still a one-line question.
        return if signals.has_code {
            Decision::new(Tier::Code, Reason::CodeShaped)
        } else {
            Decision::new(Tier::Mid, Reason::Scored)
        };
    }

    Decision::new(Tier::Cheap, Reason::Scored)
}

#[cfg(test)]
mod tests {
    use super::{Decision, LONG_CONTEXT_TOKENS, Reason, Signals, Thresholds, decide};
    use crate::tier::Tier;

    fn at(score: f32, signals: Signals) -> Decision {
        decide(&signals, Some(score), &Thresholds::default())
    }

    #[test]
    fn score_selects_the_band() {
        assert_eq!(at(0.10, Signals::default()).tier, Tier::Cheap);
        assert_eq!(at(0.70, Signals::default()).tier, Tier::Mid);
        assert_eq!(at(0.95, Signals::default()).tier, Tier::Heavy);
    }

    #[test]
    fn code_is_promoted_only_within_the_middle_band() {
        let code = Signals {
            has_code: true,
            ..Signals::default()
        };
        assert_eq!(at(0.70, code).tier, Tier::Code);
        // A one-line question about a snippet is still a one-line question.
        assert_eq!(at(0.10, code).tier, Tier::Cheap);
        // And code does not hold a hard prompt back from the heavy tier.
        assert_eq!(at(0.95, code).tier, Tier::Heavy);
    }

    #[test]
    fn a_pin_beats_every_other_rule() {
        // Absolute, including against the capability rule: an operator who pins
        // has taken responsibility for it.
        let signals = Signals {
            pinned: Some(Tier::Cheap),
            is_background: true,
            has_code: true,
            estimated_tokens: 900_000,
        };
        let decision = decide(&signals, Some(0.99), &Thresholds::default());
        assert_eq!(decision.tier, Tier::Cheap);
        assert_eq!(decision.reason, Reason::Pinned);
    }

    #[test]
    fn background_work_beats_difficulty() {
        // Compacting a hard conversation is not itself hard.
        let signals = Signals {
            is_background: true,
            ..Signals::default()
        };
        let decision = decide(&signals, Some(0.99), &Thresholds::default());
        assert_eq!(decision.tier, Tier::Aux);
        assert_eq!(decision.reason, Reason::Background);
    }

    #[test]
    fn an_oversized_context_beats_a_low_score() {
        // Routing this cheap would have the upstream refuse it, costing the turn.
        let signals = Signals {
            estimated_tokens: LONG_CONTEXT_TOKENS + 1,
            ..Signals::default()
        };
        let decision = decide(&signals, Some(0.01), &Thresholds::default());
        assert_eq!(decision.tier, Tier::Long);
        assert_eq!(decision.reason, Reason::ContextTooLarge);
    }

    #[test]
    fn an_unscored_request_lands_in_the_middle() {
        let decision = decide(&Signals::default(), None, &Thresholds::default());
        assert_eq!(decision.tier, Tier::Mid);
        assert_eq!(decision.reason, Reason::Unscored);
    }

    #[test]
    fn a_broken_score_degrades_routing_rather_than_failing() {
        assert_eq!(at(-5.0, Signals::default()).tier, Tier::Cheap);
        assert_eq!(at(42.0, Signals::default()).tier, Tier::Heavy);
        assert_eq!(at(f32::NAN, Signals::default()).tier, Tier::Cheap);
    }

    #[test]
    fn unordered_or_out_of_range_thresholds_cannot_be_constructed() {
        assert!(Thresholds::new(0.5, 0.85).is_some());
        assert!(Thresholds::new(0.85, 0.5).is_none(), "must be ordered");
        assert!(Thresholds::new(0.5, 0.5).is_none(), "must be strict");
        assert!(Thresholds::new(-0.1, 0.5).is_none(), "must be in range");
        assert!(Thresholds::new(0.5, 1.5).is_none(), "must be in range");
    }

    #[test]
    fn every_reason_has_a_distinct_label() {
        // Labels reach metrics; a shared one silently merges two populations.
        let reasons = [
            Reason::Pinned,
            Reason::Background,
            Reason::ContextTooLarge,
            Reason::Scored,
            Reason::CodeShaped,
            Reason::Unscored,
            Reason::Sticky,
        ];
        let mut seen = std::collections::HashSet::new();
        for reason in reasons {
            assert!(seen.insert(reason.label()), "duplicate: {}", reason.label());
        }
    }
}
