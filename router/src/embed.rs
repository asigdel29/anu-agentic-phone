//! embed.rs — turning a prompt into a vector.
//!
//! History
//!   2026-08-05  A. Sigdel  Created with the trait and the offline backend.
//!   2026-08-05  A. Sigdel  Added the ONNX backend, which the scoring head needs:
//!                          a head fitted on hash vectors separated the classes
//!                          by 0.022, which is nothing.
//!
//! Contents
//!   `Embedder`      What the router needs from any backend.
//!   `EmbedError`    Why an embedding could not be produced.
//!   `HashEmbedder`  Feature hashing. No model, no download.
//!   `fnv1a`, `dot`  The crate's single hash and single dot product.
//!   `OnnxEmbedder`  bge-small-en-v1.5. Better, and what the head needs.
//!   `cosine`        Similarity between two normalised vectors.
//!
//! Two backends exist because the deployment target forces the choice. On a board
//! with 8GB or more, ONNX bge-small-en-v1.5 gives the best quality at ~130MB
//! resident plus a download. On a 4GB board that is not affordable alongside
//! Hermes and zeromem, and a worse embedding that fits beats a better one that
//! does not. zeromem splits the same way for the same reason.
//!
//! Both produce [`DIM`]-dimensional L2-normalised vectors, so they are
//! interchangeable without retraining the head, and a dot product of two outputs
//! is their cosine.

use thiserror::Error;

/// The embedding dimension, fixed at bge-small-en-v1.5's output width.
///
/// The hash backend matches it deliberately: the head is trained against one
/// width, and a backend that disagreed would silently produce nonsense rather
/// than fail.
pub const DIM: usize = 384;

/// Why an embedding could not be produced.
#[derive(Debug, Error)]
pub enum EmbedError {
    /// The backend rejected the input.
    #[error("cannot embed: {0}")]
    Input(String),

    /// The backend itself failed.
    #[error("embedding backend failed: {0}")]
    Backend(String),
}

/// What the router needs from any embedding backend.
///
/// The backend is chosen once, at startup, and the rest of the router never
/// learns which one it got. That is the point: routing quality degrades on a
/// small board, but nothing else changes shape.
pub trait Embedder: Send + Sync {
    /// A stable identifier for the backend, reported in metrics and logs.
    ///
    /// Included so that a routing decision can be attributed to the backend that
    /// produced it — scores from the two are not comparable, and an operator
    /// comparing metrics across boards needs to know which they are reading.
    fn id(&self) -> String;

    /// Embed `text`.
    ///
    /// # Arguments
    /// * `text` — WHERE `text` is non-empty after trimming.
    ///
    /// # Returns
    /// A vector of exactly [`DIM`] elements, L2-normalised, so that a dot product
    /// with another output is the cosine of the angle between them.
    ///
    /// # Errors
    /// [`EmbedError::Input`] IF `text` is empty or whitespace;
    /// [`EmbedError::Backend`] IF the backend fails.
    ///
    /// # Rely
    /// Called on the request path. Implementations that block must say so and be
    /// dispatched accordingly; this one does not block.
    ///
    /// # Atomic
    /// Implementations must be safe to call concurrently. This one holds no
    /// mutable state, so calls do not interact at all.
    fn embed(&self, text: &str) -> Result<Vec<f32>, EmbedError>;
}

/// FNV-1a over `bytes`, salted by `kind`.
///
/// The single hash in the crate. The salt separates feature spaces that would
/// otherwise collide — a token and a trigram spelled alike, or a prompt key and
/// an embedding feature.
#[must_use]
pub fn fnv1a(kind: u8, bytes: &[u8]) -> u64 {
    const OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
    const PRIME: u64 = 0x0000_0100_0000_01b3;

    let mut h = OFFSET ^ u64::from(kind);
    for byte in bytes {
        h ^= u64::from(*byte);
        h = h.wrapping_mul(PRIME);
    }
    h
}

/// The dot product of two equal-length vectors.
///
/// The single one in the crate: embedder, head and trainer all reduce to this, so
/// a change here — wider accumulation, SIMD — reaches all three or none.
///
/// # Returns
/// The sum of products, or `0.0` IF the lengths differ. A wrong answer beats a
/// panic on the request path, and the callers that care check length themselves.
#[must_use]
pub fn dot(a: &[f32], b: &[f32]) -> f32 {
    if a.len() != b.len() {
        return 0.0;
    }
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

/// Cosine similarity between two vectors.
///
/// # Arguments
/// * `a`, `b` — WHERE both have the same length.
///
/// # Returns
/// The dot product, which equals the cosine WHEN both inputs are L2-normalised —
/// as every [`Embedder`] output is. Returns `0.0` IF the lengths differ, which
/// cannot happen through the trait but is preferable to a panic on the request
/// path.
#[must_use]
pub fn cosine(a: &[f32], b: &[f32]) -> f32 {
    dot(a, b)
}

/// Scale `v` to unit length, in place.
///
/// A zero vector is left alone: it has no direction to preserve, and dividing
/// would produce `NaN`, which would then contaminate every downstream comparison
/// silently rather than loudly.
pub fn l2_normalize(v: &mut [f32]) {
    let norm = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    if norm > 0.0 {
        for x in v.iter_mut() {
            *x /= norm;
        }
    }
}

/// Signed feature hashing over tokens and character trigrams.
///
/// Needs no model, no download and no network, which is what makes it the
/// fallback for a memory-constrained board and the backend for tests.
///
/// It has no notion of meaning — only of overlap. Two prompts sharing vocabulary
/// score close whether or not they mean the same thing. That is a real quality
/// loss against ONNX and the reason it is not the default; it is enough to
/// separate "write a compiler" from "what time is it", which is most of what
/// routing asks.
///
/// Trigrams are included alongside whole tokens so that morphological variants
/// ("refactor", "refactoring") retain some overlap, which whole-token hashing
/// alone would discard entirely.
#[derive(Debug, Clone, Copy)]
pub struct HashEmbedder;

impl HashEmbedder {
    /// Build an embedder producing [`DIM`]-wide vectors.
    #[must_use]
    pub const fn new() -> Self {
        Self
    }

    /// Add `feature` to `out`, with a sign taken from the hash.
    ///
    /// The sign is what keeps this unbiased: without it every collision adds, and
    /// the vector drifts towards a constant as the input grows. With it,
    /// collisions cancel on average.
    fn accumulate(out: &mut [f32], kind: u8, feature: &str) {
        let h = fnv1a(kind, feature.as_bytes());
        // `h % len` is strictly less than `len`, which is itself a `usize`, so
        // the conversion cannot fail. Checked rather than cast so that stays a
        // fact the compiler enforces instead of one a comment asserts.
        let index = usize::try_from(h % out.len() as u64)
            .expect("a value below out.len() always fits in a usize");
        let sign = if h & (1 << 63) == 0 { 1.0 } else { -1.0 };
        out[index] += sign;
    }
}

impl Default for HashEmbedder {
    fn default() -> Self {
        Self::new()
    }
}

impl Embedder for HashEmbedder {
    fn id(&self) -> String {
        format!("hash-v1-{DIM}")
    }

    fn embed(&self, text: &str) -> Result<Vec<f32>, EmbedError> {
        if text.trim().is_empty() {
            return Err(EmbedError::Input("text is empty".to_owned()));
        }

        let mut out = vec![0.0f32; DIM];
        let lowered = text.to_lowercase();

        for token in lowered.split(|c: char| !c.is_alphanumeric()) {
            if token.is_empty() {
                continue;
            }
            Self::accumulate(&mut out, 0, token);

            // Trigrams over characters, not bytes, so multi-byte text is not
            // split mid-character into features that mean nothing.
            let chars: Vec<char> = token.chars().collect();
            for window in chars.windows(3) {
                let trigram: String = window.iter().collect();
                Self::accumulate(&mut out, 1, &trigram);
            }
        }

        l2_normalize(&mut out);
        Ok(out)
    }
}

/// bge-small-en-v1.5 through ONNX Runtime.
///
/// The better embedding, and the one the scoring head needs: hash features
/// encode lexical overlap, and difficulty is not lexical — "prove this is
/// NP-hard" and "spell NP-hard" share nearly all their vocabulary. Fitting a head
/// on hash vectors separated the training classes by 0.022, which is nothing.
///
/// Costs ~130MB resident plus a one-time model download, which is why it is
/// selectable rather than mandatory.
///
/// The model cache is shared with zeromem, so the download happens once on the
/// board rather than once per process.
#[cfg(feature = "onnx")]
pub struct OnnxEmbedder {
    model: std::sync::Mutex<fastembed::TextEmbedding>,
}

#[cfg(feature = "onnx")]
impl std::fmt::Debug for OnnxEmbedder {
    /// The model holds no printable state worth showing, and its `Debug` is not
    /// implemented upstream.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OnnxEmbedder").finish_non_exhaustive()
    }
}

#[cfg(feature = "onnx")]
impl OnnxEmbedder {
    /// Load the model, downloading it into `cache_dir` if absent.
    ///
    /// # Errors
    /// [`EmbedError::Backend`] IF the model cannot be fetched or the runtime
    /// cannot start. The caller is expected to fall back to [`HashEmbedder`]
    /// rather than refuse to serve: a worse embedding beats no router.
    ///
    /// # Rely
    /// Called once, at startup. Blocks for the download on first run, which is
    /// why it is not called from the request path.
    pub fn new(cache_dir: &std::path::Path) -> Result<Self, EmbedError> {
        let options = fastembed::InitOptions::new(fastembed::EmbeddingModel::BGESmallENV15)
            .with_cache_dir(cache_dir.to_path_buf())
            .with_show_download_progress(false);

        let model = fastembed::TextEmbedding::try_new(options)
            .map_err(|e| EmbedError::Backend(e.to_string()))?;

        Ok(Self {
            model: std::sync::Mutex::new(model),
        })
    }
}

#[cfg(feature = "onnx")]
impl Embedder for OnnxEmbedder {
    fn id(&self) -> String {
        "bge-small-en-v1.5".to_owned()
    }

    /// # Rely
    /// **Blocks.** ONNX inference is CPU-bound and runs for milliseconds, so the
    /// caller must dispatch this off the async executor — a blocked worker stalls
    /// every other request sharing it.
    ///
    /// # Atomic
    /// Serialized on a mutex: the session is not safe to use concurrently.
    /// Callers queue, which is the intended behaviour on a board with few cores —
    /// parallel inference there would contend for the same cores anyway.
    fn embed(&self, text: &str) -> Result<Vec<f32>, EmbedError> {
        if text.trim().is_empty() {
            return Err(EmbedError::Input("text is empty".to_owned()));
        }

        let mut model = self
            .model
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);

        let mut out = model
            .embed(vec![text], None)
            .map_err(|e| EmbedError::Backend(e.to_string()))?
            .pop()
            .ok_or_else(|| EmbedError::Backend("model returned no vector".to_owned()))?;

        if out.len() != DIM {
            return Err(EmbedError::Backend(format!(
                "model produced {} dimensions, expected {DIM}",
                out.len()
            )));
        }

        // The model normalises already, but the trait promises it and the head
        // depends on it, so it is enforced here rather than assumed of upstream.
        l2_normalize(&mut out);
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::{DIM, EmbedError, Embedder, HashEmbedder, cosine, l2_normalize};

    fn embed(text: &str) -> Vec<f32> {
        HashEmbedder::new().embed(text).expect("non-empty text")
    }

    #[test]
    fn output_is_the_expected_width_and_normalised() {
        let v = embed("refactor the authentication module");
        assert_eq!(v.len(), DIM);
        let norm = v.iter().map(|x| x * x).sum::<f32>().sqrt();
        assert!((norm - 1.0).abs() < 1e-5, "norm was {norm}");
    }

    #[test]
    fn empty_input_is_rejected_rather_than_returning_a_zero_vector() {
        // A zero vector would score 0.0 against everything and route silently by
        // whichever threshold happened to sit lowest.
        assert!(matches!(
            HashEmbedder::new().embed("   "),
            Err(EmbedError::Input(_))
        ));
    }

    #[test]
    fn embedding_is_deterministic() {
        // The decision cache keys on the prompt and assumes a repeat scores the
        // same. If this drifted, a cached tier would disagree with a fresh one.
        assert_eq!(embed("design a schema"), embed("design a schema"));
    }

    #[test]
    fn related_text_scores_above_unrelated_text() {
        let anchor = embed("refactor the authentication module and add tests");
        let related = embed("refactoring authentication and adding a test");
        let unrelated = embed("what time is sunset in Kathmandu");

        let near = cosine(&anchor, &related);
        let far = cosine(&anchor, &unrelated);
        assert!(near > far, "related {near} should exceed unrelated {far}");
    }

    #[test]
    fn trigrams_preserve_overlap_across_word_forms() {
        // Whole-token hashing alone would score these at zero, which is the whole
        // reason trigrams are mixed in.
        assert!(cosine(&embed("refactor"), &embed("refactoring")) > 0.0);
    }

    #[test]
    fn cosine_of_mismatched_lengths_is_zero_not_a_panic() {
        // Unreachable through the trait, but this sits on the request path and a
        // wrong answer beats taking the process down.
        assert!(cosine(&[1.0, 0.0], &[1.0]).abs() < f32::EPSILON);
    }

    #[test]
    fn normalising_a_zero_vector_leaves_it_alone() {
        // Dividing by zero would give NaN, which then silently contaminates every
        // comparison downstream instead of failing where it happened.
        let mut v = vec![0.0f32; 4];
        l2_normalize(&mut v);
        assert!(v.iter().all(|x| x.abs() < f32::EPSILON));
    }
}
