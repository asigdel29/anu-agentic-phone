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

/// The ordered list of models backing `tier`.
///
/// The tier's own model comes first. Candidates follow, restricted to tiers whose
/// context window is at least as large: a substitution should change cost or
/// speed, never whether the request can be served at all. Each window is read for
/// the backend that tier actually runs on, so a local model is judged by what a
/// phone holds rather than by what its weights advertise.
///
/// Candidates on the same backend come first, then the rest ordered by how close
/// their capability is to the original, so a substitution is the smallest one
/// available. Leaving the device is a different kind of substitution from picking
/// another local model — it is the escape hatch for work no local window can hold
/// — and capability distance alone would reach for it whenever it sorted well.
///
/// # Returns
/// A non-empty list of at most [`MAX_CHAIN`] models, so
/// [`crate::upstream::Upstream::forward`] always has something to try.
#[must_use]
pub fn chain_for(config: &Config, tier: Tier) -> Vec<&str> {
    let backend = config.backend_for(tier);
    let limit = tier.context_limit(backend);
    let mut candidates: Vec<Tier> = Tier::ALL
        .into_iter()
        .filter(|&other| other != tier && other.context_limit(config.backend_for(other)) >= limit)
        .collect();

    // Own backend first, then closest capability, so a heavy request degrades to
    // the next thing down rather than straight to the cheapest that happens to
    // fit. `backend_for` is an array index, so reading it again here is cheaper
    // than carrying it alongside each candidate: pairing the two widens both the
    // vector's elements and the sort key, and measured slower.
    candidates.sort_by_key(|&other| {
        let distance = (other as i32 - tier as i32).abs();
        // Prefer a more capable substitute over a less capable one at equal
        // distance: over-serving costs a fraction of a cent, under-serving costs
        // the answer.
        (
            i32::from(config.backend_for(other) != backend),
            distance,
            i32::from(other < tier),
        )
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
    use crate::backend::Backend;
    use crate::config::Config;
    use crate::tier::Tier;

    /// The board: every tier behind the upstream.
    fn config() -> Config {
        Config::with_backends([Backend::Remote; Tier::ALL.len()])
    }

    /// The phone: every tier on the device except the long one, which is where
    /// work no local window can hold goes.
    fn phone() -> Config {
        let mut backends = [Backend::Local; Tier::ALL.len()];
        backends[Tier::Long as usize] = Backend::Remote;
        Config::with_backends(backends)
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
                    served_by.context_limit(config.backend_for(served_by))
                        >= tier.context_limit(config.backend_for(tier)),
                    "{} falls back to {}, which holds less context",
                    tier.name(),
                    served_by.name()
                );
            }
        }
    }

    #[test]
    fn a_local_tier_does_not_leave_the_device_while_it_has_alternatives() {
        // Capability distance alone puts the long tier first here — one step from
        // code and more capable at that distance — so without the backend key a
        // coding request takes the escape hatch as its first fallback, sending
        // off the device work three local models could have served.
        let config = phone();
        let chain = chain_for(&config, Tier::Code);
        let off_device = config.model_for(Tier::Long);
        assert!(
            !chain.contains(&off_device),
            "code left the device with local models still available: {chain:?}"
        );
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
