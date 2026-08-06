//! backend.rs — where a tier's model runs.
//!
//! History
//!   2026-08-06  A. Sigdel  Created.
//!
//! Contents
//!   `Backend`  In this process, or over the network.
//!
//! Kept apart from [`crate::tier`] for the reason the tier vocabulary is kept
//! apart from configuration: which tiers run locally is a deployment's answer,
//! not a property of the tiers themselves. The board answers `Remote` for all
//! six, the phone answers `Local` for most of them, and the same routing rules
//! serve both.
//!
//! This is an axis, not a second tier ladder. A tier still says how capable a
//! model must be; a backend says where that model is.

/// Where a tier's model runs.
///
/// Ordered so that neither variant is "greater" by accident — unlike
/// [`crate::tier::Tier`], where the ordering is load-bearing, these are two
/// places rather than two capabilities.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Backend {
    /// In this process: an MLX build in the app's own address space.
    Local,
    /// Over the network, behind the upstream API.
    Remote,
}

impl Backend {
    /// Every backend. The single enumeration, matching [`crate::tier::Tier::ALL`].
    pub const ALL: [Self; 2] = [Self::Local, Self::Remote];

    /// The backend's stable name, as configuration spells it and metrics label it.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Local => "local",
            Self::Remote => "remote",
        }
    }

    /// Read a backend from a configured value, ignoring case and surrounding
    /// space.
    ///
    /// # Returns
    /// `Some` IF `value` names a backend. `None` is a configuration error for
    /// the caller to report rather than a value to fall back from: a typo that
    /// silently meant `Remote` would send to the network work the operator
    /// asked to keep on the device.
    #[must_use]
    pub fn parse(value: &str) -> Option<Self> {
        let value = value.trim();
        Self::ALL
            .into_iter()
            .find(|backend| value.eq_ignore_ascii_case(backend.name()))
    }
}

#[cfg(test)]
mod tests {
    use super::Backend;

    #[test]
    fn every_name_parses_back_to_its_backend() {
        for backend in Backend::ALL {
            assert_eq!(Backend::parse(backend.name()), Some(backend));
        }
    }

    #[test]
    fn case_and_surrounding_space_do_not_matter() {
        // Both are what a hand-edited .env file actually contains.
        assert_eq!(Backend::parse("  LOCAL "), Some(Backend::Local));
        assert_eq!(Backend::parse("Remote"), Some(Backend::Remote));
    }

    #[test]
    fn an_unrecognised_value_is_not_a_backend() {
        // Notably not silently Remote: see `parse`.
        for value in ["", "on-device", "localhost", "rmote"] {
            assert_eq!(Backend::parse(value), None, "for {value:?}");
        }
    }
}
