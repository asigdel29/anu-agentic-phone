//! main.rs — the wattrouter binary.
//!
//! History
//!   2026-08-05  A. Sigdel  Created with configuration and the two endpoints a
//!                          client needs before it will talk to us at all.
//!
//! Contents
//!   `AppState`         Shared, read-only application state.
//!   `chat_completions` The endpoint the whole stack uses.
//!   `app`              Builds the router. Separate from `main` so tests reach it.
//!   `main`             Reads configuration, binds, serves.
//!
//! The lint posture and the crate documentation live in `lib.rs`; this file is
//! only the entry point and the handlers.

use std::sync::Arc;

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse as _, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{Value, json};

use wattrouter::chain::chain_for;
use wattrouter::classify::classify;
use wattrouter::config::{Config, ConfigError};
use wattrouter::embed::{Embedder, HashEmbedder};
use wattrouter::head::Head;
use wattrouter::policy::{Thresholds, decide};
use wattrouter::tier::Tier;
use wattrouter::upstream::Upstream;

/// Shared application state.
///
/// Read-only after construction, so it is handed to every request through an
/// [`Arc`] with no lock. The mutable pieces the router grows later — the
/// embedder, the decision cache, the connection pool — carry their own
/// synchronisation and document it where they are defined.
#[derive(Debug)]
struct AppState {
    config: Config,
    upstream: Upstream,
    thresholds: Thresholds,
    embedder: HashEmbedder,
    /// Absent when no weights were found, which is a supported state: the policy
    /// has a defined unscored path, and every rule not depending on difficulty
    /// still applies.
    head: Option<Head>,
}

/// Report liveness.
///
/// Deliberately does not touch the upstream: this answers "is the process
/// serving?", which is what systemd and the deployment script need to know. A
/// check that fails when the upstream is briefly unavailable would have systemd
/// restart a healthy router, which helps nobody.
///
/// # Returns
/// `200` with a fixed body, always.
///
/// # Rely
/// Called on the request path. Does no I/O and never blocks.
async fn healthz() -> (StatusCode, Json<Value>) {
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

/// List the models on offer, in the OpenAI catalogue shape.
///
/// Advertises one entry per tier plus `auto`. Clients are expected to request
/// `auto` and let the router decide; the per-tier names are listed so that an
/// operator can pin a tier by hand when the routing decision is wrong, and so
/// that a client which insists on choosing has something meaningful to choose.
///
/// # Returns
/// `200` and a catalogue with `Tier::ALL.len() + 1` entries.
///
/// # Rely
/// Called on the request path. Reads only immutable state; does no I/O.
async fn list_models(State(state): State<Arc<AppState>>) -> (StatusCode, Json<Value>) {
    let mut data = vec![json!({
        "id": "auto",
        "object": "model",
        "owned_by": "wattrouter",
        "description": "Route automatically. The intended default.",
    })];

    data.extend(Tier::ALL.iter().map(|&tier| {
        json!({
            "id": tier.name(),
            "object": "model",
            "owned_by": "wattrouter",
            "description": format!("Pin the {} tier ({}).", tier.name(), state.config.model_for(tier)),
        })
    }));

    (
        StatusCode::OK,
        Json(json!({ "object": "list", "data": data })),
    )
}

/// Route a chat completion and stream the answer back.
///
/// Classify, decide, map the tier to a model chain, forward. The decision is
/// reported on the response so an operator can see what happened without reading
/// logs, and so the verification script has something to assert against.
///
/// No score is supplied yet, so the policy takes its unscored path. That is a
/// defined behaviour rather than a gap: pinning, background detection and the
/// capability rule all still apply, and a scorer can be added without touching
/// this function.
///
/// # Returns
/// The upstream response, body still streaming, with `x-wattrouter-tier` added.
/// `502` IF every model in the chain failed — the request was well-formed and
/// the failure is upstream, which is what that status means.
///
/// # Rely
/// Called on the request path. Awaits the upstream response head; the body is
/// passed on unread.
///
/// # Atomic
/// Reads only immutable state. Concurrent calls share the connection pool, which
/// is internally synchronised.
async fn chat_completions(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Response {
    let pin = headers
        .get("x-wattrouter-tier")
        .and_then(|v| v.to_str().ok());
    let classified = classify(&body, pin);

    // Only when a head is loaded and there is something to score. Embedding an
    // empty string is an error, and the policy discards a score for a pinned or
    // background request anyway.
    let score = state.head.as_ref().and_then(|head| {
        if classified.text.is_empty() {
            return None;
        }
        match state.embedder.embed(&classified.text) {
            Ok(vector) => Some(head.score(&vector)),
            Err(e) => {
                tracing::warn!(error = %e, "cannot embed; routing unscored");
                None
            }
        }
    });

    let decision = decide(&classified.signals, score, &state.thresholds);
    let chain = chain_for(&state.config, decision.tier);

    tracing::info!(
        tier = decision.tier.name(),
        reason = decision.reason.label(),
        model = chain[0],
        tokens = classified.signals.estimated_tokens,
        score = score.map(|s| format!("{s:.3}")),
        "routing"
    );

    match state.upstream.forward(&chain, &body).await {
        Ok(mut response) => {
            // Both parts, because the tier alone does not say whether it was
            // chosen or imposed, and that is the first thing anyone asks.
            if let Ok(value) =
                format!("{}; {}", decision.tier.name(), decision.reason.label()).parse()
            {
                response.headers_mut().insert("x-wattrouter-tier", value);
            }
            response
        }
        Err(e) => {
            tracing::error!(error = %e, tier = decision.tier.name(), "every model failed");
            (
                StatusCode::BAD_GATEWAY,
                Json(json!({"error": {"message": e.to_string(), "type": "upstream_error"}})),
            )
                .into_response()
        }
    }
}

/// Build the HTTP router.
///
/// Separate from [`main`] so tests can drive it through `tower::ServiceExt`
/// without binding a port, which keeps them fast and free of port collisions.
fn app(config: Config, upstream: Upstream, head: Option<Head>) -> Router {
    let state = Arc::new(AppState {
        config,
        upstream,
        thresholds: Thresholds::default(),
        embedder: HashEmbedder::new(),
        head,
    });

    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/models", get(list_models))
        .route("/v1/chat/completions", post(chat_completions))
        .with_state(state)
}

/// Read configuration, bind, and serve until terminated.
///
/// # Errors
/// Returns [`ConfigError`] IF the environment is missing or malformed. Binding
/// and serving failures are reported and end the process, since neither is
/// recoverable and a router that cannot serve has nothing useful to do.
#[tokio::main]
async fn main() -> Result<(), ConfigError> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "wattrouter=info".into()),
        )
        .init();

    let config = Config::from_env()?;
    let addr = config.addr();

    // Enough for an operator to tell a misconfiguration from a bug without
    // turning on debug logging. The credential is confirmed, never printed:
    // knowing one was loaded is the useful half, and the other half is a leak.
    tracing::info!(
        %addr,
        upstream = config.upstream_base_url(),
        model_cache = %config.model_cache_dir().display(),
        credential = redacted(config.api_key()),
        "starting"
    );
    for tier in Tier::ALL {
        tracing::info!(tier = tier.name(), model = config.model_for(tier), "tier");
    }

    let upstream = Upstream::new(config.upstream_base_url(), config.api_key())
        .unwrap_or_else(|e| panic!("cannot build upstream client: {e}"));

    // A missing or mismatched head is reported and then tolerated. Routing gets
    // worse; serving does not stop. Refusing to start over an optional model
    // would make the stack less available than running it without one.
    let embedder = HashEmbedder::new();
    let head = match Head::load(config.head_path(), &embedder.id()) {
        Ok(head) => {
            tracing::info!(fitted_on = head.fitted_on(), "scoring head loaded");
            Some(head)
        }
        Err(e) => {
            tracing::warn!(error = %e, "no scoring head; routing unscored");
            None
        }
    };

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .unwrap_or_else(|e| panic!("cannot bind {addr}: {e}"));

    // After binding, so the port is accepting while this runs. The first request
    // then finds a live connection instead of paying for DNS, TCP and TLS.
    upstream.prewarm().await;

    axum::serve(listener, app(config, upstream, head))
        .with_graceful_shutdown(shutdown())
        .await
        .unwrap_or_else(|e| panic!("server failed: {e}"));

    Ok(())
}

/// Describe a credential without disclosing it.
///
/// Reports only the length, which distinguishes "absent", "truncated by a shell
/// expansion" and "looks right" — the three cases an operator actually needs to
/// tell apart — while carrying no material an attacker could use.
///
/// # Returns
/// A fixed-shape description. Never contains any part of `key`.
fn redacted(key: &str) -> String {
    format!("<{} chars>", key.len())
}

/// Resolve on SIGINT, so that in-flight requests finish before the process ends.
///
/// # Rely
/// Awaited by the server for the lifetime of the process.
async fn shutdown() {
    if let Err(e) = tokio::signal::ctrl_c().await {
        tracing::error!(error = %e, "cannot listen for shutdown; running until killed");
        std::future::pending::<()>().await;
    }
    tracing::info!("shutting down");
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request;
    use http_body_util::BodyExt as _;
    use tower::ServiceExt as _;

    /// Build a config for the handlers under test.
    ///
    /// `from_env` is the only constructor, so the one required variable is set
    /// here. Tests in this module never remove it, so ordering does not matter.
    /// An upstream pointed nowhere; these tests do not forward.
    fn test_upstream() -> Upstream {
        Upstream::new("http://127.0.0.1:1", "test-key").expect("client builds")
    }

    fn test_config() -> Config {
        unsafe { std::env::set_var("NEURALWATT_API_KEY", "test-key") };
        Config::from_env().expect("test configuration is valid")
    }

    async fn body_json(response: axum::response::Response) -> Value {
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice(&bytes).unwrap()
    }

    #[tokio::test]
    async fn healthz_reports_ok() {
        let response = app(test_config(), test_upstream(), None)
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(body_json(response).await["status"], "ok");
    }

    #[tokio::test]
    async fn models_lists_auto_and_every_tier() {
        let response = app(test_config(), test_upstream(), None)
            .oneshot(
                Request::builder()
                    .uri("/v1/models")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        let ids: Vec<&str> = body["data"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| m["id"].as_str().unwrap())
            .collect();

        assert!(
            ids.contains(&"auto"),
            "auto is the intended default: {ids:?}"
        );
        for tier in Tier::ALL {
            assert!(
                ids.contains(&tier.name()),
                "{} missing from {ids:?}",
                tier.name()
            );
        }
    }

    #[test]
    fn tiers_are_ordered_by_capability() {
        // The cache escalates but never silently demotes, which is only
        // meaningful if the ordering holds.
        assert!(Tier::Aux < Tier::Cheap);
        assert!(Tier::Cheap < Tier::Mid);
        assert!(Tier::Mid < Tier::Heavy);
    }

    #[test]
    fn every_tier_has_a_distinct_default_model() {
        let mut seen = std::collections::HashSet::new();
        for tier in Tier::ALL {
            assert!(
                seen.insert(tier.default_model()),
                "{} duplicates a model already assigned",
                tier.name()
            );
        }
    }
}
