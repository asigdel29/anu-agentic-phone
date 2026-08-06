//! tier.rs — the routing tiers.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `Tier`  A routing role, and the upstream model serving it.
//!
//! A tier is the vocabulary the rest of the router speaks: policy decides which
//! tier a prompt deserves, configuration decides which model serves that tier,
//! and metrics count by it. Kept apart from configuration so that the vocabulary
//! does not depend on where the values happen to be read from.
//!
//! A tier says how capable a model must be. Where that model runs is the other
//! axis, and it lives in [`crate::backend`].

use crate::backend::Backend;

/// A routing tier: a role, not a model name.
///
/// Ordered by capability, and the order is load-bearing — a session may be
/// escalated to a higher tier but never quietly dropped to a lower one, so that a
/// conversation cannot get worse partway through.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Tier {
    /// Background work: titles, summaries, compaction. Never user-facing.
    Aux,
    /// Lookups, short answers, chat.
    Cheap,
    /// The working default: tool calls and structured output.
    Mid,
    /// Code-shaped work below the heavy threshold.
    Code,
    /// Contexts too large for any other tier.
    Long,
    /// Architecture, multi-file reasoning, debugging.
    Heavy,
}

impl Tier {
    /// Every tier, ascending.
    pub const ALL: [Self; 6] = [
        Self::Aux,
        Self::Cheap,
        Self::Mid,
        Self::Code,
        Self::Long,
        Self::Heavy,
    ];

    /// The tier's stable name, as used in configuration and metrics.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Aux => "aux",
            Self::Cheap => "cheap",
            Self::Mid => "mid",
            Self::Code => "code",
            Self::Long => "long",
            Self::Heavy => "heavy",
        }
    }

    /// The upstream model serving this tier by default.
    ///
    /// Each was verified against the upstream catalogue. Overridable per tier
    /// through the environment, because that catalogue changes faster than this
    /// source does.
    #[must_use]
    pub const fn default_model(self) -> &'static str {
        match self {
            Self::Aux => "gemma-4-31b",
            Self::Cheap => "deepseek-v4-flash",
            Self::Mid => "qwen3.6-35b-fast",
            Self::Code => "kimi-k2.7-code",
            Self::Long => "glm-5.2",
            Self::Heavy => "kimi-k3",
        }
    }

    /// The context window this tier's model can hold, in tokens.
    ///
    /// Used to keep a fallback from substituting a model that cannot hold the
    /// request the original could — which would turn a degraded answer into a
    /// rejected one. Nominal: an operator who overrides a tier's model to a
    /// smaller one takes that on.
    ///
    /// # Arguments
    /// * `backend` — where the model runs. The same tier holds far less
    ///   locally: what a build advertises is what its architecture allows, and
    ///   what a phone holds is decided by the KV cache, which at 27B outweighs
    ///   the weights.
    ///
    /// # Returns
    /// A token count. The remote figures are verified against the upstream
    /// catalogue. The local ones are not measurements — they are the
    /// conservative end of what the plan expects, and settling them needs a
    /// physical iPhone. Read them as open.
    #[must_use]
    // Several tiers share a limit today, which clippy reads as duplication.
    // Merging the arms would couple facts about different models: when one
    // provider changes a window, only that tier should move. Kept separate.
    #[allow(clippy::match_same_arms)]
    pub const fn context_limit(self, backend: Backend) -> usize {
        match backend {
            Backend::Remote => match self {
                Self::Aux => 262_128,
                Self::Cheap => 1_048_560,
                Self::Mid => 131_056,
                Self::Code => 262_128,
                Self::Long => 1_048_560,
                Self::Heavy => 1_048_560,
            },
            // Grouped by the model behind the tier rather than by the tier: the
            // plan puts a 1.7B on aux and cheap, an 8B on mid and a 27B on the
            // rest, and KV cache scales with the model, not with the role.
            Backend::Local => match self {
                Self::Aux => 65_520,
                Self::Cheap => 65_520,
                Self::Mid => 32_752,
                Self::Code => 32_752,
                Self::Long => 32_752,
                Self::Heavy => 32_752,
            },
        }
    }

    /// The environment variable overriding this tier's model.
    #[must_use]
    pub const fn env_var(self) -> &'static str {
        match self {
            Self::Aux => "WATTROUTER_MODEL_AUX",
            Self::Cheap => "WATTROUTER_MODEL_CHEAP",
            Self::Mid => "WATTROUTER_MODEL_MID",
            Self::Code => "WATTROUTER_MODEL_CODE",
            Self::Long => "WATTROUTER_MODEL_LONG",
            Self::Heavy => "WATTROUTER_MODEL_HEAVY",
        }
    }

    /// The environment variable choosing where this tier's model runs.
    #[must_use]
    pub const fn backend_env_var(self) -> &'static str {
        match self {
            Self::Aux => "WATTROUTER_BACKEND_AUX",
            Self::Cheap => "WATTROUTER_BACKEND_CHEAP",
            Self::Mid => "WATTROUTER_BACKEND_MID",
            Self::Code => "WATTROUTER_BACKEND_CODE",
            Self::Long => "WATTROUTER_BACKEND_LONG",
            Self::Heavy => "WATTROUTER_BACKEND_HEAVY",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::Tier;
    use crate::backend::Backend;

    #[test]
    fn tiers_ascend_by_capability() {
        // The cache escalates but never silently demotes, which only means
        // anything if this ordering holds.
        assert!(Tier::Aux < Tier::Cheap);
        assert!(Tier::Cheap < Tier::Mid);
        assert!(Tier::Mid < Tier::Code);
        assert!(Tier::Long < Tier::Heavy);
    }

    #[test]
    fn every_tier_maps_to_a_distinct_model() {
        let mut seen = std::collections::HashSet::new();
        for tier in Tier::ALL {
            assert!(
                seen.insert(tier.default_model()),
                "{} duplicates a model already assigned",
                tier.name()
            );
        }
    }

    #[test]
    fn tier_names_and_variables_agree() {
        // A mismatch would make an override silently ineffective, which is the
        // kind of failure nobody notices until the bill arrives.
        for tier in Tier::ALL {
            let name = tier.name().to_uppercase();
            let model = format!("WATTROUTER_MODEL_{name}");
            let backend = format!("WATTROUTER_BACKEND_{name}");
            assert_eq!(tier.env_var(), model, "for tier {}", tier.name());
            assert_eq!(tier.backend_env_var(), backend, "for tier {}", tier.name());
        }
    }

    #[test]
    fn no_tier_holds_more_locally_than_it_does_remotely() {
        // The local figures are placeholders; this relation between them is not.
        // A tier holding more on a phone than behind the upstream would mean its
        // local number came from the architecture's ceiling rather than from
        // what fits in the memory the device has.
        for tier in Tier::ALL {
            assert!(
                tier.context_limit(Backend::Local) <= tier.context_limit(Backend::Remote),
                "{} holds more locally than remotely",
                tier.name()
            );
        }
    }

    #[test]
    fn discriminants_match_positions_in_all() {
        // Configuration indexes a per-tier array by discriminant. Were `ALL`
        // reordered, or a variant added without extending it, this catches it
        // before an out-of-bounds panic reaches the request path.
        for (index, tier) in Tier::ALL.into_iter().enumerate() {
            assert_eq!(tier as usize, index, "{} is out of position", tier.name());
        }
    }
}
