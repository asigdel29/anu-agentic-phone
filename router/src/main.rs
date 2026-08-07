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

use wattrouter::backend::Backend;
use wattrouter::cache::DecisionCache;
use wattrouter::chain::chain_for;
use wattrouter::classify::classify;
use wattrouter::config::{Config, ConfigError};
use wattrouter::embed::{Embedder, HashEmbedder};
use wattrouter::head::Head;
use wattrouter::metrics::Metrics;
use wattrouter::policy::{Decision, Reason, Thresholds, decide};
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
    cache: DecisionCache,
    metrics: Metrics,
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
/// Classify, score, decide, apply session stickiness, map to a chain, forward. The decision is
/// reported on the response so an operator can see what happened without reading
/// logs, and so the verification script has something to assert against.
///
/// With no head loaded the policy takes its unscored path — a defined behaviour
/// rather than a gap, since pinning, background detection and the capability rule
/// all still apply.
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
    // Without one every request is independent, which is correct rather than
    // merely tolerated. Counted because nothing in this repository sends one
    // today, so stickiness — which `cache.rs` calls the larger win — has never
    // fired, and a response cannot say so: no session and an unseen session
    // route identically.
    let session = headers
        .get("x-session-id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    if !session.is_empty() {
        state.metrics.record_session();
    }
    let classified = classify(&body, pin);

    // Only when a head is loaded and there is something to score. Embedding an
    // empty string is an error, and the policy discards a score for a pinned or
    // background request anyway.
    let score = state.head.as_ref().and_then(|head| {
        if classified.text.is_empty() {
            return None;
        }
        if let Some(cached) = state.cache.score_for(&classified.text) {
            state.metrics.record_cache_hit();
            return Some(cached);
        }
        state.metrics.record_embedding();
        match state.embedder.embed(&classified.text) {
            Ok(vector) => {
                let score = head.score(&vector);
                state.cache.remember_score(&classified.text, score);
                Some(score)
            }
            Err(e) => {
                tracing::warn!(error = %e, "cannot embed; routing unscored");
                None
            }
        }
    });

    let mut decision = decide(&classified.signals, score, &state.thresholds);

    // Stickiness applies to what the router chose, never to what the caller
    // asked for. A pin is an instruction, not an observation about the session,
    // and letting one raise the floor for every later turn would make the escape
    // hatch permanent.
    if decision.reason != Reason::Pinned {
        let effective = state.cache.escalate(session, decision.tier);
        if effective > decision.tier {
            decision = Decision::new(effective, Reason::Sticky);
        }
    }
    let chain = chain_for(&state.config, decision.tier);
    state.metrics.record(decision.tier, decision.reason);

    tracing::info!(
        tier = decision.tier.name(),
        reason = decision.reason.label(),
        model = chain[0].model(),
        tokens = classified.signals.estimated_tokens,
        score = score.map(|s| format!("{s:.3}")),
        "routing"
    );

    match state.upstream.forward(&chain, body).await {
        Ok(mut response) => {
            // The chain reports which model answered, so a fallback is countable
            // rather than only visible in a warning nobody aggregates.
            if response
                .headers()
                .get("x-wattrouter-model")
                .and_then(|v| v.to_str().ok())
                .is_some_and(|served| served != chain[0].model())
            {
                state.metrics.record_fallback();
            }
            report(&mut response, decision);
            response
        }
        Err(e) => {
            state.metrics.record_upstream_failure();
            tracing::error!(error = %e, tier = decision.tier.name(), "every model failed");
            // Reported here too. The decision is as true of a failure as of a
            // success, and which tier failed is the first thing anyone asks of a
            // 502 — answering it from the logs means still having them.
            let mut response = (
                StatusCode::BAD_GATEWAY,
                Json(json!({"error": {"message": e.to_string(), "type": "upstream_error"}})),
            )
                .into_response();
            report(&mut response, decision);
            response
        }
    }
}

/// Add the decision to a response, whatever the response says.
///
/// Both parts, because the tier alone does not say whether it was chosen or
/// imposed, and that is the first thing anyone asks. One function rather than a
/// call on each arm: the format is a small contract with
/// `scripts/verify-stack.sh` and a second copy of it would be free to drift.
///
/// A tier or reason that could not be turned into a header value is dropped
/// rather than failing the request. Every name is ASCII and none can fail today;
/// a request lost to a header is a worse outcome than a header lost to a name.
fn report(response: &mut Response, decision: Decision) {
    if let Ok(value) = format!("{}; {}", decision.tier.name(), decision.reason.label()).parse() {
        response.headers_mut().insert("x-wattrouter-tier", value);
    }
}

/// Expose the counters, in the Prometheus text format.
///
/// # Returns
/// `200` and a complete exposition, including series that have never fired.
///
/// # Rely
/// Called by a scraper or by hand. Reads atomics; does no I/O and never blocks.
///
/// # Atomic
/// Each counter is read independently, so the snapshot may straddle a request in
/// flight. These are rates over time, not an invariant, so that is correct.
async fn metrics(State(state): State<Arc<AppState>>) -> (StatusCode, String) {
    (StatusCode::OK, state.metrics.render())
}

/// Build the HTTP router.
///
/// Separate from [`main`] so tests can drive it through `tower::ServiceExt`
/// without binding a port, which keeps them fast and free of port collisions.
fn app(config: Config, upstream: Upstream, head: Option<Head>) -> Router {
    // A head's scores occupy a narrow band around 0.5, so the absolute defaults
    // would strand whole tiers. When the head carries thresholds calibrated
    // against its own distribution, those win — they are the only ones that can
    // be right for it.
    let thresholds = head
        .as_ref()
        .and_then(Head::thresholds)
        .and_then(|(cheap, mid)| Thresholds::new(cheap, mid))
        .unwrap_or_default();

    let state = Arc::new(AppState {
        config,
        upstream,
        thresholds,
        embedder: HashEmbedder::new(),
        cache: DecisionCache::new(),
        metrics: Metrics::new(),
        head,
    });

    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/models", get(list_models))
        .route("/v1/chat/completions", post(chat_completions))
        .route("/metrics", get(metrics))
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

    // This binary forwards over HTTP and does nothing else. A tier configured to
    // run in this process would instead be sent upstream under a model name the
    // provider does not have, so refuse at startup rather than on every request.
    // The core is shared with an app that does have a local runtime; that caller
    // does not come through here, which is why the check lives in the binary
    // rather than in Config.
    if let Some(tier) = Tier::ALL
        .into_iter()
        .find(|&tier| config.backend_for(tier) == Backend::Local)
    {
        return Err(ConfigError::Invalid {
            name: tier.backend_env_var(),
            expected: "backend this binary can run; it has no local runtime, so remote",
            value: Backend::Local.name().to_owned(),
        });
    }

    let addr = config.addr();

    // Enough for an operator to tell a misconfiguration from a bug without
    // turning on debug logging. The credential is confirmed, never printed:
    // knowing one was loaded is the useful half, and the other half is a leak.
    tracing::info!(
        %addr,
        upstream = config.upstream_base_url(),
        model_cache = %config.model_cache_dir().display(),
        credential = config.redacted_api_key(),
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

    if let Some((cheap, mid)) = head.as_ref().and_then(Head::thresholds) {
        tracing::info!(cheap_max = cheap, mid_max = mid, "calibrated thresholds");
    } else {
        tracing::warn!("no calibrated thresholds; using defaults, which suit no head");
    }

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
    async fn only_a_request_naming_a_session_is_counted_as_one() {
        // The whole point of the counter: it is the only thing that separates a
        // client reaching the router from a client reaching it carrying a
        // session, and no response can report that difference.
        let app = app(test_config(), test_upstream(), None);
        let body = || Body::from(r#"{"messages":[{"role":"user","content":"hello there"}]}"#);

        // One with, one without. The upstream refuses, so both 502 — which does
        // not matter here: the header is read before anything is forwarded.
        for session in [Some("verify-1"), None] {
            let mut request = Request::builder()
                .method("POST")
                .uri("/v1/chat/completions")
                .header("content-type", "application/json");
            if let Some(id) = session {
                request = request.header("x-session-id", id);
            }
            let _ = app
                .clone()
                .oneshot(request.body(body()).unwrap())
                .await
                .unwrap();
        }

        let response = app
            .oneshot(
                Request::builder()
                    .uri("/metrics")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let rendered = String::from_utf8(bytes.to_vec()).unwrap();

        assert!(
            rendered.contains("wattrouter_requests_with_session_total 1"),
            "two requests, one session: {rendered}"
        );
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

    #[tokio::test]
    async fn a_failed_request_still_reports_its_decision() {
        // The upstream here refuses connections, so the chain is exhausted and
        // this takes the error arm. What it must not lose on the way is the
        // decision: an operator reading a 502 wants to know which tier failed,
        // and `scripts/verify-stack.sh` reads exactly this header to assert the
        // routing rules without paying for inference.
        let body = r#"{"messages":[{"role":"user","content":"hello there"}]}"#;
        let response = app(test_config(), test_upstream(), None)
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/chat/completions")
                    .header("content-type", "application/json")
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
        let reported = response
            .headers()
            .get("x-wattrouter-tier")
            .expect("a 502 still names its tier")
            .to_str()
            .unwrap();
        // Both halves, in the shape the verifier parses. Unscored because no head
        // is loaded, which is the policy's defined path rather than a gap.
        assert_eq!(reported, "mid; unscored");
    }

    #[tokio::test]
    async fn a_pinned_failure_reports_the_pin() {
        // The reason travels with the tier, so a 502 distinguishes a tier the
        // router chose from one an operator imposed. Those call for opposite
        // responses, and the status code alone says neither.
        let body = r#"{"messages":[{"role":"user","content":"hello there"}]}"#;
        let response = app(test_config(), test_upstream(), None)
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/chat/completions")
                    .header("content-type", "application/json")
                    .header("x-wattrouter-tier", "heavy")
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
        assert_eq!(
            response.headers().get("x-wattrouter-tier").unwrap(),
            "heavy; pinned"
        );
    }
}
