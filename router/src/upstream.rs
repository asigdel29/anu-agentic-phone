//! upstream.rs — forwarding a request to the provider.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `UpstreamError`  Why a request could not be forwarded.
//!   `Upstream`       The client, its pool, and the fallback chain.
//! The response body is never buffered. This sits between the agent and the
//! model, so anything held here lands directly on time-to-first-token. Buffering
//! would turn a streaming response into a batch one, and would do so silently —
//! everything still works, only slower. Hence a test asserting the first chunk
//! arrives well before the last.

use std::time::Duration;

use futures_util::TryStreamExt as _;
use thiserror::Error;

/// Why a request could not be forwarded.
#[derive(Debug, Error)]
pub enum UpstreamError {
    /// The client could not be built. Fatal, and only possible at startup.
    #[error("cannot build upstream client: {0}")]
    Build(String),
    /// Every model in the chain failed.
    #[error("all {tried} upstream models failed; last error: {last}")]
    Exhausted {
        /// How many models were attempted.
        tried: usize,
        /// What the final attempt reported.
        last: String,
    },
}

/// The upstream client.
///
/// Holds the only copy of the credential on the board, so a compromise of Hermes
/// or OpenCode does not yield one.
#[derive(Debug, Clone)]
pub struct Upstream {
    client: reqwest::Client,
    base_url: String,
    api_key: String,
}

impl Upstream {
    /// Build a client with a reusable connection pool.
    ///
    /// # Errors
    /// [`UpstreamError::Build`] IF the TLS backend or resolver cannot start.
    ///
    /// # Rely
    /// Called once, at startup. Cheap to clone; every clone shares one pool.
    pub fn new(base_url: &str, api_key: &str) -> Result<Self, UpstreamError> {
        let client = reqwest::Client::builder()
            // Past a typical think-time gap, so a turn pays no fresh handshake.
            .pool_idle_timeout(Duration::from_mins(5))
            .pool_max_idle_per_host(8)
            // Generous: a heavy-tier response legitimately takes minutes; the
            // read timeout is what catches a genuinely dead connection.
            .timeout(Duration::from_mins(30))
            .read_timeout(Duration::from_mins(2))
            .build()
            .map_err(|e| UpstreamError::Build(e.to_string()))?;

        Ok(Self {
            client,
            base_url: base_url.trim_end_matches('/').to_owned(),
            api_key: api_key.to_owned(),
        })
    }

    /// Open a connection ahead of the first real request, which would otherwise
    /// pay for DNS, TCP and TLS — a visible fraction of a second on a small board.
    ///
    /// # Rely
    /// Called at startup, after binding. Failure is logged and ignored: an
    /// upstream unreachable at boot may well be reachable by the first request.
    pub async fn prewarm(&self) {
        let url = format!("{}/models", self.base_url);
        match self
            .client
            .get(&url)
            .bearer_auth(&self.api_key)
            .send()
            .await
        {
            Ok(response) => tracing::debug!(status = %response.status(), "upstream prewarmed"),
            Err(e) => tracing::warn!(error = %e, "upstream prewarm failed; continuing"),
        }
    }

    /// Forward `body` to the first model in `chain` that answers, substituting
    /// the chosen model since the client asked for `auto`.
    ///
    /// # Arguments
    /// * `chain` — models to try in order, WHERE `chain` is non-empty.
    ///
    /// # Returns
    /// The response, body still streaming, so the caller sees the first chunk as
    /// soon as the provider emits it.
    ///
    /// # Errors
    /// [`UpstreamError::Exhausted`] IF every model in the chain fails.
    ///
    /// # Rely
    /// Called on the request path. Awaits the response head only.
    ///
    /// # Atomic
    /// Safe to call concurrently; the pool is internally synchronised.
    pub async fn forward(
        &self,
        chain: &[&str],
        body: &serde_json::Value,
    ) -> Result<axum::response::Response, UpstreamError> {
        let url = format!("{}/chat/completions", self.base_url);
        let mut last = String::from("chain was empty");

        for model in chain {
            let mut outgoing = body.clone();
            if let Some(object) = outgoing.as_object_mut() {
                object.insert("model".into(), serde_json::Value::from(*model));
            }

            let sent = self
                .client
                .post(&url)
                .bearer_auth(&self.api_key)
                .json(&outgoing)
                .send()
                .await;

            match sent {
                // A server error is worth another model; a client error is not,
                // since the next model would reject the same body identically.
                Ok(response) if response.status().is_server_error() => {
                    last = format!("{} returned {}", model, response.status());
                    tracing::warn!(model, status = %response.status(), "upstream error; trying next");
                }
                Ok(response) => return Ok(Self::stream_back(response)),
                Err(e) => {
                    last = format!("{model}: {e}");
                    tracing::warn!(model, error = %e, "upstream unreachable; trying next");
                }
            }
        }

        Err(UpstreamError::Exhausted {
            tried: chain.len(),
            last,
        })
    }

    /// Rebuild the upstream response around an unread body stream.
    ///
    /// `bytes_stream` yields chunks as they arrive. Losing that is the one change
    /// here that breaks the latency guarantee without breaking a functional test.
    fn stream_back(response: reqwest::Response) -> axum::response::Response {
        let status = response.status();
        let headers = response.headers().clone();

        let stream = response.bytes_stream().map_err(std::io::Error::other);
        let mut out = axum::response::Response::new(axum::body::Body::from_stream(stream));

        *out.status_mut() = status;
        // Content-Length dropped deliberately: the body is chunked now, and a
        // stale length would truncate it.
        for (name, value) in headers.iter().filter(|(n, _)| *n != "content-length") {
            out.headers_mut().insert(name, value.clone());
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::{Upstream, UpstreamError};
    use axum::Router;
    use axum::routing::post;
    use serde_json::json;
    use std::time::{Duration, Instant};
    use tokio::net::TcpListener;

    /// Start `app` on an ephemeral port; port zero so runs cannot collide.
    async fn serve(app: Router) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        format!("http://{addr}")
    }

    #[tokio::test]
    async fn the_chosen_model_is_substituted_and_the_credential_attached() {
        let app = Router::new().route(
            "/chat/completions",
            post(|headers: axum::http::HeaderMap, body: String| async move {
                let auth = headers.get("authorization").unwrap().to_str().unwrap();
                format!("{auth}|{body}")
            }),
        );
        let base = serve(app).await;
        let upstream = Upstream::new(&base, "secret-key").unwrap();

        let body = serde_json::json!({"model": "auto", "messages": []});
        let response = upstream.forward(&["kimi-k3"], &body).await.unwrap();
        let echoed = String::from_utf8(
            axum::body::to_bytes(response.into_body(), usize::MAX)
                .await
                .unwrap()
                .to_vec(),
        )
        .unwrap();

        assert!(echoed.contains("Bearer secret-key"), "{echoed}");
        assert!(echoed.contains(r#""model":"kimi-k3""#), "{echoed}");
        assert!(
            !echoed.contains("auto"),
            "auto should be replaced: {echoed}"
        );
    }

    #[tokio::test]
    async fn a_server_error_falls_through_to_the_next_model() {
        let app = Router::new().route(
            "/chat/completions",
            post(|body: String| async move {
                if body.contains("kimi-k3") {
                    (
                        axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                        "upstream down",
                    )
                } else {
                    (axum::http::StatusCode::OK, "served by the fallback")
                }
            }),
        );
        let base = serve(app).await;
        let upstream = Upstream::new(&base, "k").unwrap();

        let body = serde_json::json!({"model": "auto"});
        let response = upstream
            .forward(&["kimi-k3", "glm-5.2"], &body)
            .await
            .unwrap();
        assert_eq!(response.status(), axum::http::StatusCode::OK);
    }

    #[tokio::test]
    async fn an_exhausted_chain_reports_what_was_tried() {
        let app = Router::new().route(
            "/chat/completions",
            post(|| async { axum::http::StatusCode::INTERNAL_SERVER_ERROR }),
        );
        let base = serve(app).await;
        let upstream = Upstream::new(&base, "k").unwrap();

        let err = upstream
            .forward(&["a", "b"], &serde_json::json!({}))
            .await
            .expect_err("every model failed");
        assert!(matches!(err, UpstreamError::Exhausted { tried: 2, .. }));
    }

    #[tokio::test]
    async fn the_first_chunk_arrives_long_before_the_last() {
        // The point of this module. A buffering client passes every other test
        // here and fails only this one.
        let app = Router::new().route(
            "/chat/completions",
            post(|| async {
                let stream = async_stream::stream! {
                    for i in 0..5 {
                        yield Ok::<_, std::io::Error>(format!("chunk {i}\n"));
                        tokio::time::sleep(Duration::from_millis(120)).await;
                    }
                };
                axum::body::Body::from_stream(stream)
            }),
        );
        let base = serve(app).await;
        let upstream = Upstream::new(&base, "k").unwrap();

        let started = Instant::now();
        let response = upstream.forward(&["m"], &json!({})).await.unwrap();
        let mut body = response.into_body().into_data_stream();
        let first_at = {
            use futures_util::StreamExt as _;
            let _ = body.next().await.expect("a first chunk").unwrap();
            started.elapsed()
        };

        // Drain the rest so the total is a fair comparison.
        {
            use futures_util::StreamExt as _;
            while let Some(chunk) = body.next().await {
                chunk.unwrap();
            }
        }
        let total = started.elapsed();

        assert!(
            first_at < total / 2,
            "first chunk at {first_at:?} of {total:?} total — the body is being buffered"
        );
    }
}
