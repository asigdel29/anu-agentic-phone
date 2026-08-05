//! head.rs — scoring a prompt's difficulty.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `HeadError`  Why a head could not be loaded.
//!   `Head`       Weights, and the score they produce.
//!
//! A logistic head over an embedding: one dot product and a sigmoid. That is the
//! entire model, which is the point — inference costs microseconds on the board
//! and the weights are a few kilobytes of JSON.
//!
//! The method follows RouteLLM (Ong et al., LMSYS): score a prompt by the
//! probability that a strong model beats a weak one on it, and threshold that
//! into tiers. The implementation is independent — the embedding is computed
//! locally rather than fetched from a hosted API, and the score is thresholded
//! into several tiers rather than two.
//!
//! Weights are tied to the embedder that produced the training vectors. Scoring
//! hash embeddings with a head fitted on ONNX ones would produce confident
//! nonsense rather than an error, so the pairing is checked at load and never
//! assumed.

use std::path::Path;

use serde::Deserialize;
use thiserror::Error;

/// Why a head could not be loaded.
#[derive(Debug, Error)]
pub enum HeadError {
    /// The file could not be read.
    #[error("cannot read {path}: {source}")]
    Read {
        /// Where the head was expected.
        path: String,
        /// What the filesystem reported.
        source: std::io::Error,
    },

    /// The file was not the expected shape.
    #[error("{path} is not a valid head: {reason}")]
    Malformed {
        /// Where the head was expected.
        path: String,
        /// What was wrong with it.
        reason: String,
    },

    /// The head was fitted against a different embedder.
    #[error(
        "head was fitted on embedder {fitted_on} but the router is using {in_use}; \
         scoring one with the other yields confident nonsense"
    )]
    EmbedderMismatch {
        /// The embedder named in the weights file.
        fitted_on: String,
        /// The embedder the router actually has.
        in_use: String,
    },
}

/// The on-disk shape of a head.
///
/// Named for the file rather than its contents: `Weights.weights` reads as a
/// stutter, and the type is the whole document, not just the coefficients.
///
/// Deliberately plain JSON rather than a serialised model: it is a few hundred
/// floats, and a format anyone can read and diff is worth more here than one that
/// saves a few kilobytes.
#[derive(Debug, Deserialize)]
struct HeadFile {
    /// The [`crate::embed::Embedder::id`] of the embedder used to fit this.
    embedder: String,
    /// One coefficient per embedding dimension.
    weights: Vec<f32>,
    /// The intercept.
    bias: f32,
}

/// A fitted logistic head.
#[derive(Debug, Clone)]
pub struct Head {
    weights: Vec<f32>,
    bias: f32,
    fitted_on: String,
}

impl Head {
    /// Load a head and check it matches the embedder in use.
    ///
    /// # Arguments
    /// * `path` — the weights file.
    /// * `embedder_id` — [`crate::embed::Embedder::id`] of the router's embedder.
    ///
    /// # Errors
    /// [`HeadError::Read`] IF the file is absent or unreadable;
    /// [`HeadError::Malformed`] IF it does not parse or carries no weights;
    /// [`HeadError::EmbedderMismatch`] IF it was fitted on a different embedder.
    ///
    /// # Rely
    /// Called once, at startup. The caller decides what an error means; the
    /// router treats it as "route unscored" rather than as a reason not to serve.
    pub fn load(path: &Path, embedder_id: &str) -> Result<Self, HeadError> {
        let raw = std::fs::read_to_string(path).map_err(|source| HeadError::Read {
            path: path.display().to_string(),
            source,
        })?;
        Self::from_json(&raw, embedder_id, &path.display().to_string())
    }

    /// Parse and validate a head that has already been read.
    ///
    /// Separated from [`Self::load`] so that validation is exercised without
    /// touching a filesystem: every rule worth testing here is about the content,
    /// and reading a file to check them would only test the standard library.
    ///
    /// # Arguments
    /// * `origin` — where the text came from, for error messages only.
    ///
    /// # Errors
    /// [`HeadError::Malformed`] IF it does not parse or carries no weights;
    /// [`HeadError::EmbedderMismatch`] IF it names a different embedder.
    pub fn from_json(raw: &str, embedder_id: &str, origin: &str) -> Result<Self, HeadError> {
        let parsed: HeadFile = serde_json::from_str(raw).map_err(|e| HeadError::Malformed {
            path: origin.to_owned(),
            reason: e.to_string(),
        })?;

        if parsed.weights.is_empty() {
            return Err(HeadError::Malformed {
                path: origin.to_owned(),
                reason: "carries no weights".to_owned(),
            });
        }

        if parsed.embedder != embedder_id {
            return Err(HeadError::EmbedderMismatch {
                fitted_on: parsed.embedder,
                in_use: embedder_id.to_owned(),
            });
        }

        Ok(Self {
            weights: parsed.weights,
            bias: parsed.bias,
            fitted_on: parsed.embedder,
        })
    }

    /// The embedder this head was fitted on, for logging and metrics.
    #[must_use]
    pub fn fitted_on(&self) -> &str {
        &self.fitted_on
    }

    /// Score `embedding`: the probability that a strong model is needed.
    ///
    /// # Arguments
    /// * `embedding` — WHERE its length equals the head's weight count.
    ///
    /// # Returns
    /// A value in `[0, 1]`, higher meaning harder. Returns `0.5` IF the length
    /// disagrees — an exactly ambivalent score, which the policy resolves to the
    /// middle band. Refusing to answer here would fail requests over what is a
    /// configuration mistake, and the mismatch is already reported at load.
    ///
    /// # Rely
    /// Called on the request path. Pure arithmetic; no allocation, no blocking.
    ///
    /// # Atomic
    /// Reads only immutable state, so concurrent calls do not interact.
    #[must_use]
    pub fn score(&self, embedding: &[f32]) -> f32 {
        if embedding.len() != self.weights.len() {
            return 0.5;
        }

        let logit: f32 = self
            .weights
            .iter()
            .zip(embedding)
            .map(|(w, x)| w * x)
            .sum::<f32>()
            + self.bias;

        sigmoid(logit)
    }
}

/// The logistic function.
///
/// Written with the sign split so that a large-magnitude logit cannot overflow
/// `exp`. The naive form returns `inf` for a logit below about -88 and then `NaN`
/// from the division, which would reach the policy as a score and route by
/// whichever comparison happened to be false.
fn sigmoid(x: f32) -> f32 {
    if x >= 0.0 {
        1.0 / (1.0 + (-x).exp())
    } else {
        let e = x.exp();
        e / (1.0 + e)
    }
}

#[cfg(test)]
mod tests {
    use super::{Head, HeadError, sigmoid};

    fn head(json: &str) -> Result<Head, HeadError> {
        Head::from_json(json, "e", "test")
    }

    #[test]
    fn a_head_scores_between_zero_and_one() {
        let head = head(r#"{"embedder":"e","weights":[1.0,-1.0],"bias":0.0}"#).expect("loads");
        for embedding in [[1.0, 0.0], [0.0, 1.0], [0.7, 0.7], [-3.0, 9.0]] {
            let s = head.score(&embedding);
            assert!((0.0..=1.0).contains(&s), "{embedding:?} scored {s}");
        }
    }

    #[test]
    fn weights_determine_the_direction_of_the_score() {
        // A positive coefficient must raise the score for a positive feature, or
        // the head is wired backwards and every routing decision inverts.
        let head = head(r#"{"embedder":"e","weights":[4.0],"bias":0.0}"#).expect("loads");
        assert!(head.score(&[1.0]) > head.score(&[-1.0]));
        assert!(
            (head.score(&[0.0]) - 0.5).abs() < 1e-6,
            "zero input is neutral"
        );
    }

    #[test]
    fn a_head_fitted_on_another_embedder_is_refused() {
        // The failure this guards against is silent: hash vectors scored by an
        // ONNX-fitted head give confident, meaningless numbers.
        let err = Head::from_json(
            r#"{"embedder":"bge-small-en-v1.5","weights":[1.0],"bias":0.0}"#,
            "hash-v1-384",
            "test",
        )
        .expect_err("must refuse");
        assert!(matches!(err, HeadError::EmbedderMismatch { .. }));
    }

    #[test]
    fn malformed_content_is_reported_as_such() {
        assert!(matches!(head("not json"), Err(HeadError::Malformed { .. })));
        assert!(matches!(
            head(r#"{"embedder":"e","weights":[],"bias":0.0}"#),
            Err(HeadError::Malformed { .. })
        ));
    }

    #[test]
    fn a_missing_file_is_reported_as_a_read_failure() {
        let missing = Head::load(std::path::Path::new("/nonexistent/head.json"), "e");
        assert!(matches!(missing, Err(HeadError::Read { .. })));
    }

    #[test]
    fn a_length_mismatch_scores_ambivalently_rather_than_panicking() {
        // Resolves to the middle band. Failing the request instead would turn a
        // configuration mistake into an outage, and load already reported it.
        let head = head(r#"{"embedder":"e","weights":[1.0,2.0],"bias":0.0}"#).expect("loads");
        assert!((head.score(&[1.0]) - 0.5).abs() < f32::EPSILON);
    }

    #[test]
    fn the_sigmoid_saturates_instead_of_overflowing() {
        // The naive form returns inf for a logit below about -88, then NaN from
        // the division — which would reach the policy as a score.
        assert!(sigmoid(-1000.0) >= 0.0 && sigmoid(-1000.0) < 1e-6);
        assert!(sigmoid(1000.0) > 0.999_999);
        assert!(sigmoid(f32::NEG_INFINITY).is_finite());
    }
}
