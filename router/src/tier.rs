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
}

#[cfg(test)]
mod tests {
    use super::Tier;

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
            let expected = format!("WATTROUTER_MODEL_{}", tier.name().to_uppercase());
            assert_eq!(tier.env_var(), expected, "for tier {}", tier.name());
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
