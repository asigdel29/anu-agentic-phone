//! config.rs — configuration read from the environment.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `Config`       Everything the router needs to run.
//!   `ConfigError`  Why configuration was rejected.
//!
//! Configuration answers which model serves each tier. Choosing between tiers is
//! policy and lives elsewhere.
//!
//! Everything is validated once, at startup: a router that refuses to start is
//! easier to diagnose than one failing on every request, because the operator
//! reading the failure still has the context that caused it.

use std::env;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};

use thiserror::Error;

use crate::backend::Backend;
use crate::tier::Tier;

/// Why configuration was rejected.
///
/// Validated once at startup and never again, so all of these are fatal. Refusing
/// to start beats serving every request against a broken upstream.
#[derive(Debug, Error)]
pub enum ConfigError {
    /// A required variable was absent or empty.
    #[error("{0} is not set; see .env.example")]
    Missing(&'static str),

    /// A variable was present but could not be parsed as the type it names.
    #[error("{name} is not a valid {expected}: {value}")]
    Invalid {
        /// The offending variable.
        name: &'static str,
        /// What was expected, for the operator reading the message.
        expected: &'static str,
        /// What was actually supplied.
        value: String,
    },
}

/// What a backend variable must have said, for the operator reading the
/// failure. Written out because [`ConfigError::Invalid`] carries a `&'static
/// str` and this is the one place a message has to outlive its formatting;
/// `the_backend_message_names_every_backend` keeps it complete.
const BACKEND_VALUES: &str = "backend, which is local or remote";

/// Everything the router needs to run.
///
/// Built once at startup and read-only thereafter, so it is shared across tasks
/// without synchronisation.
///
/// [`Debug`] is hand-written, not derived: the derived form prints every field
/// and one of them is a credential.
#[derive(Clone)]
pub struct Config {
    addr: SocketAddr,
    upstream_base_url: String,
    api_key: String,
    model_cache_dir: PathBuf,
    head_path: PathBuf,
    models: [String; Tier::ALL.len()],
    backends: [Backend; Tier::ALL.len()],
}

impl Config {
    /// Read and validate configuration from the environment.
    ///
    /// # Errors
    /// [`ConfigError::Missing`] IF `NEURALWATT_API_KEY` is absent or empty;
    /// [`ConfigError::Invalid`] IF `WATTROUTER_ADDR` does not parse as a socket
    /// address.
    ///
    /// # Rely
    /// Called once, before the server binds. Reads the process environment, so it
    /// must not run concurrently with anything mutating it.
    pub fn from_env() -> Result<Self, ConfigError> {
        let addr_raw = optional("WATTROUTER_ADDR").unwrap_or_else(|| "127.0.0.1:8080".to_owned());
        let addr = addr_raw.parse().map_err(|_| ConfigError::Invalid {
            name: "WATTROUTER_ADDR",
            expected: "socket address such as 127.0.0.1:8080",
            value: addr_raw,
        })?;

        let api_key =
            optional("NEURALWATT_API_KEY").ok_or(ConfigError::Missing("NEURALWATT_API_KEY"))?;

        let upstream_base_url = optional("WATTROUTER_UPSTREAM")
            .unwrap_or_else(|| "https://api.neuralwatt.com/v1".to_owned());

        // Shared with zeromem so the model is fetched once, not once per process.
        let model_cache_dir = optional("WATTROUTER_MODEL_CACHE").map_or_else(
            || {
                let home = env::var("HOME").unwrap_or_else(|_| ".".to_owned());
                PathBuf::from(home).join(".hermes/memory/zeromem-models")
            },
            PathBuf::from,
        );

        let head_path = optional("WATTROUTER_HEAD")
            .map_or_else(|| model_cache_dir.join("head.json"), PathBuf::from);

        let models = Tier::ALL.map(|tier| {
            optional(tier.env_var()).unwrap_or_else(|| tier.default_model().to_owned())
        });

        // Remote unless a deployment says otherwise, so a configuration written
        // before this axis existed keeps behaving exactly as it did. An
        // unrecognised value is fatal rather than remote: a typo that quietly
        // meant remote would send off the device the work an operator asked to
        // keep on it.
        let mut backends = [Backend::Remote; Tier::ALL.len()];
        for tier in Tier::ALL {
            let Some(raw) = optional(tier.backend_env_var()) else {
                continue;
            };
            backends[tier as usize] = Backend::parse(&raw).ok_or(ConfigError::Invalid {
                name: tier.backend_env_var(),
                expected: BACKEND_VALUES,
                value: raw,
            })?;
        }

        Ok(Self {
            addr,
            upstream_base_url,
            api_key,
            model_cache_dir,
            head_path,
            models,
            backends,
        })
    }

    /// The address the server binds.
    #[must_use]
    pub const fn addr(&self) -> SocketAddr {
        self.addr
    }

    /// The upstream API root, without a trailing slash.
    #[must_use]
    pub fn upstream_base_url(&self) -> &str {
        &self.upstream_base_url
    }

    /// The upstream credential. Never logged, never returned over HTTP; the
    /// router holds the only copy on the board.
    #[must_use]
    pub fn api_key(&self) -> &str {
        &self.api_key
    }

    /// Describe the credential without disclosing it.
    ///
    /// The single redaction in the crate: [`Debug`] and the startup log line both
    /// use it, so the shape cannot change in one and not the other. Reports only
    /// the length, which separates "absent", "truncated by a shell expansion" and
    /// "looks right" while carrying nothing usable.
    #[must_use]
    pub fn redacted_api_key(&self) -> String {
        format!("<{} chars>", self.api_key.len())
    }

    /// Where the embedding model is cached.
    #[must_use]
    pub fn model_cache_dir(&self) -> &Path {
        &self.model_cache_dir
    }

    /// Where the scoring head's weights live.
    ///
    /// Beside the model cache, because the two belong together: a head is only
    /// meaningful paired with the embedder that produced its training vectors.
    #[must_use]
    pub fn head_path(&self) -> &Path {
        &self.head_path
    }

    /// The upstream model serving `tier`.
    ///
    /// # Returns
    /// The configured override IF one was set, otherwise [`Tier::default_model`].
    #[must_use]
    pub fn model_for(&self, tier: Tier) -> &str {
        // Total: `models` is built by mapping over `Tier::ALL`, which is ordered
        // by the same discriminants used to index it.
        &self.models[tier as usize]
    }

    /// Where `tier`'s model runs.
    ///
    /// # Returns
    /// The configured backend IF one was set, otherwise [`Backend::Remote`],
    /// which is the board's answer for every tier.
    #[must_use]
    pub const fn backend_for(&self, tier: Tier) -> Backend {
        // Total, for the reason `model_for` is.
        self.backends[tier as usize]
    }
}

impl std::fmt::Debug for Config {
    /// Format without disclosing the credential, reporting only its length.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Config")
            .field("addr", &self.addr)
            .field("upstream_base_url", &self.upstream_base_url)
            .field("api_key", &self.redacted_api_key())
            .field("model_cache_dir", &self.model_cache_dir)
            .field("head_path", &self.head_path)
            .field("models", &self.models)
            .field("backends", &self.backends)
            .finish()
    }
}

/// Read a variable, treating empty as absent.
///
/// An empty variable is almost always an unset one that survived a shell
/// expansion, and taking it as a value produces a confusing failure much later.
fn optional(name: &str) -> Option<String> {
    env::var(name).ok().filter(|v| !v.trim().is_empty())
}

#[cfg(test)]
mod tests {
    use super::{BACKEND_VALUES, Config, ConfigError, optional};
    use crate::backend::Backend;
    use crate::tier::Tier;

    /// Environment variables are process-global; mutating tests share one lock.
    static ENV: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// Run `body` with `vars` applied, restoring previous values afterwards. A
    /// leaked variable would silently change whichever test ran next.
    fn with_env<T>(vars: &[(&str, Option<&str>)], body: impl FnOnce() -> T) -> T {
        let _guard = ENV
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let saved: Vec<_> = vars
            .iter()
            .map(|(k, _)| ((*k).to_owned(), env_get(k)))
            .collect();

        for (k, v) in vars {
            set(k, *v);
        }
        let out = body();
        for (k, v) in &saved {
            set(k, v.as_deref());
        }
        out
    }

    fn env_get(key: &str) -> Option<String> {
        std::env::var(key).ok()
    }

    fn set(key: &str, value: Option<&str>) {
        // Serialised by ENV above; nothing else reads the environment meanwhile.
        unsafe {
            match value {
                Some(v) => std::env::set_var(key, v),
                None => std::env::remove_var(key),
            }
        }
    }

    #[test]
    fn defaults_apply_when_only_the_credential_is_set() {
        let config = with_env(
            &[
                ("NEURALWATT_API_KEY", Some("k")),
                ("WATTROUTER_ADDR", None),
                ("WATTROUTER_UPSTREAM", None),
                ("WATTROUTER_MODEL_HEAVY", None),
                ("WATTROUTER_BACKEND_HEAVY", None),
            ],
            || Config::from_env().expect("defaults are valid"),
        );

        assert_eq!(config.addr().to_string(), "127.0.0.1:8080");
        assert_eq!(config.upstream_base_url(), "https://api.neuralwatt.com/v1");
        assert_eq!(config.model_for(Tier::Heavy), Tier::Heavy.default_model());
        // Saying nothing has to mean the board's answer, or this axis changes
        // the behaviour of every deployment that predates it.
        assert_eq!(config.backend_for(Tier::Heavy), Backend::Remote);
    }

    #[test]
    fn a_missing_credential_is_fatal() {
        let err = with_env(&[("NEURALWATT_API_KEY", None)], || {
            Config::from_env().expect_err("must refuse to start without a credential")
        });
        assert!(matches!(err, ConfigError::Missing("NEURALWATT_API_KEY")));
    }

    #[test]
    fn a_malformed_address_is_fatal() {
        let err = with_env(
            &[
                ("NEURALWATT_API_KEY", Some("k")),
                ("WATTROUTER_ADDR", Some("not-an-address")),
            ],
            || Config::from_env().expect_err("must refuse to start on a bad address"),
        );
        // An operator should not read the source to learn which variable was wrong.
        let message = err.to_string();
        assert!(message.contains("WATTROUTER_ADDR"), "{message}");
        assert!(message.contains("not-an-address"), "{message}");
    }

    #[test]
    fn an_empty_variable_counts_as_absent() {
        // Usually an unset variable that survived a shell expansion; taken
        // literally it would send an empty credential upstream.
        let err = with_env(&[("NEURALWATT_API_KEY", Some("   "))], || {
            Config::from_env().expect_err("blank is not a credential")
        });
        assert!(matches!(err, ConfigError::Missing(_)));
        assert!(
            with_env(&[("SOME_UNSET_VAR", Some(""))], || optional(
                "SOME_UNSET_VAR"
            ))
            .is_none()
        );
    }

    #[test]
    fn per_tier_overrides_are_honoured() {
        let config = with_env(
            &[
                ("NEURALWATT_API_KEY", Some("k")),
                ("WATTROUTER_MODEL_CHEAP", Some("some-other-model")),
            ],
            || Config::from_env().expect("override is valid"),
        );

        assert_eq!(config.model_for(Tier::Cheap), "some-other-model");
        // Overriding one tier must not disturb another.
        assert_eq!(config.model_for(Tier::Mid), Tier::Mid.default_model());
    }

    #[test]
    fn a_backend_is_chosen_per_tier() {
        // The phone's shape: local everywhere except the tier holding the work
        // too large for a local window.
        let config = with_env(
            &[
                ("NEURALWATT_API_KEY", Some("k")),
                ("WATTROUTER_BACKEND_CODE", Some("local")),
                ("WATTROUTER_BACKEND_LONG", Some("remote")),
            ],
            || Config::from_env().expect("both values are valid"),
        );

        assert_eq!(config.backend_for(Tier::Code), Backend::Local);
        assert_eq!(config.backend_for(Tier::Long), Backend::Remote);
        // Choosing for one tier must not disturb another.
        assert_eq!(config.backend_for(Tier::Mid), Backend::Remote);
    }

    #[test]
    fn an_unrecognised_backend_is_fatal() {
        // Not remote-by-default: that would send off the device the work the
        // operator was trying to keep on it, and nothing would report it.
        let err = with_env(
            &[
                ("NEURALWATT_API_KEY", Some("k")),
                ("WATTROUTER_BACKEND_MID", Some("on-device")),
            ],
            || Config::from_env().expect_err("must refuse to start on a bad backend"),
        );
        let message = err.to_string();
        assert!(message.contains("WATTROUTER_BACKEND_MID"), "{message}");
        assert!(message.contains("on-device"), "{message}");
    }

    #[test]
    fn the_backend_message_names_every_backend() {
        // The message is written out; this is what keeps it complete when a
        // third backend arrives.
        for backend in Backend::ALL {
            assert!(
                BACKEND_VALUES.contains(backend.name()),
                "{} is missing from the message an operator reads",
                backend.name()
            );
        }
    }

    #[test]
    fn the_credential_never_appears_in_debug_output() {
        // A debug-formatted config is what ends up in a log during an incident.
        let config = with_env(
            &[("NEURALWATT_API_KEY", Some("super-secret-value"))],
            || Config::from_env().expect("valid"),
        );
        assert!(
            !format!("{config:?}").contains("super-secret-value"),
            "the credential leaked into Debug output"
        );
    }
}
