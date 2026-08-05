//! chain.rs — the models a tier may use, in order.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `MAX_CHAIN`  How many models one request may try.
//!   `chain_for`  The ordered model list backing a tier.
//!
//! A tier names a role; a chain names what will actually serve it, and what will
//! serve it when the first choice is unavailable.
//!
//! The chain is derived rather than tabulated. A hand-written table let a tier
//! fall back to a model with a smaller context window, which turns a degraded
//! answer into a rejected request — and the invariant was stated in a comment
//! that nothing enforced. Deriving it makes the invariant hold by construction.

use crate::config::Config;
use crate::tier::Tier;

/// How many models one request may try before giving up.
///
/// Each failed attempt costs a full round trip before the next begins, so a long
/// chain converts one provider's outage into a slow request rather than a fast
/// failure. Three is enough to survive a single model being down.
pub const MAX_CHAIN: usize = 3;

/// The ordered list of upstream models backing `tier`.
///
/// The tier's own model comes first. Candidates follow, restricted to tiers whose
/// context window is at least as large: a substitution should change cost or
/// speed, never whether the request can be served at all.
///
/// Candidates are ordered by how close their capability is to the original, so a
/// substitution is the smallest one available.
///
/// # Returns
/// A non-empty list of at most [`MAX_CHAIN`] models, so
/// [`crate::upstream::Upstream::forward`] always has something to try.
#[must_use]
pub fn chain_for(config: &Config, tier: Tier) -> Vec<&str> {
    let mut candidates: Vec<Tier> = Tier::ALL
        .into_iter()
        .filter(|&other| other != tier && other.context_limit() >= tier.context_limit())
        .collect();

    // Closest capability first, so a heavy request degrades to the next thing
    // down rather than straight to the cheapest that happens to fit.
    candidates.sort_by_key(|&other| {
        let distance = (other as i32 - tier as i32).abs();
        // Prefer a more capable substitute over a less capable one at equal
        // distance: over-serving costs a fraction of a cent, under-serving costs
        // the answer.
        (distance, i32::from(other < tier))
    });

    let mut chain = vec![config.model_for(tier)];
    for other in candidates {
        if chain.len() >= MAX_CHAIN {
            break;
        }
        let model = config.model_for(other);
        // A repeat would spend a whole round trip re-attempting what just failed.
        if !chain.contains(&model) {
            chain.push(model);
        }
    }
    chain
}

#[cfg(test)]
mod tests {
    use super::{MAX_CHAIN, chain_for};
    use crate::config::Config;
    use crate::tier::Tier;

    fn config() -> Config {
        unsafe { std::env::set_var("NEURALWATT_API_KEY", "test") };
        Config::from_env().expect("valid")
    }

    #[test]
    fn a_chain_starts_with_its_own_model_and_is_bounded() {
        let config = config();
        for tier in Tier::ALL {
            let chain = chain_for(&config, tier);
            assert_eq!(chain[0], config.model_for(tier), "for {}", tier.name());
            assert!(chain.len() <= MAX_CHAIN, "{} is too long", tier.name());
        }
    }

    #[test]
    fn no_chain_repeats_a_model() {
        let config = config();
        for tier in Tier::ALL {
            let chain = chain_for(&config, tier);
            let unique: std::collections::HashSet<_> = chain.iter().collect();
            assert_eq!(unique.len(), chain.len(), "{} repeats", tier.name());
        }
    }

    #[test]
    fn no_tier_ever_falls_back_to_a_smaller_context() {
        // The invariant, checked for every tier rather than for one. The
        // hand-written table this replaced violated it twice — heavy and cheap
        // both fell back to the 131K middle model — and the test only looked at
        // the long tier, so it passed.
        let config = config();
        for tier in Tier::ALL {
            for model in chain_for(&config, tier) {
                let served_by = Tier::ALL
                    .into_iter()
                    .find(|t| config.model_for(*t) == model)
                    .expect("every chain entry is some tier's model");
                assert!(
                    served_by.context_limit() >= tier.context_limit(),
                    "{} falls back to {}, which holds less context",
                    tier.name(),
                    served_by.name()
                );
            }
        }
    }

    #[test]
    fn every_tier_has_somewhere_to_fall_back_to() {
        // A chain of one means a single provider failure costs the turn.
        let config = config();
        for tier in Tier::ALL {
            assert!(
                chain_for(&config, tier).len() > 1,
                "{} has no fallback",
                tier.name()
            );
        }
    }
}
