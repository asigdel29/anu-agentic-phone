//! train-head.rs: fit the routing head.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `main`  Reads prompts.jsonl, fits, writes head.json.
//!
//! In Rust rather than beside the fetch script in Python for one reason: it
//! embeds with the router's own [`HashEmbedder`]. A head is only meaningful
//! paired with the embedder that produced its vectors, and a second
//! implementation would be free to drift silently, showing up as bad routing,
//! never as an error.
//!
//! Usage
//!   train-head train/prompts.jsonl > head.json
//!
//! Result, recorded because it decides what happens next
//! ------------------------------------------------------
//! Over 109,067 examples with the hash embedder, this does not work:
//!
//!   unbalanced   separation 0.001   accuracy 88.1%
//!   balanced     separation 0.022   accuracy 59.1%
//!
//! The 88.1% is the trap. The set is seven-to-one in favour of the weak model, so
//! predicting the majority for everything scores exactly 88.1% while learning
//! nothing. Balancing makes the number honest and reveals the real state: a
//! separation of 0.022 means the score distribution is collapsed around 0.5, so
//! nearly everything lands in one band and the heavy tier is unreachable.
//!
//! The cause is the embedder, not the fit. Hash embeddings encode lexical
//! overlap, and difficulty is not a lexical property: "prove this is NP-hard"
//! and "spell NP-hard" share their vocabulary. No linear model over these
//! features can separate them.
//!
//! So the ONNX backend is a prerequisite for scoring, not an optimisation, and
//! the router ships unscored until it lands. That is a supported state: every
//! rule that does not depend on difficulty still applies. These weights are
//! deliberately not committed as a default: a head that separates nothing would
//! route worse than no head at all, while looking like it worked.

use std::io::BufRead as _;

use wattrouter::embed::{DIM, Embedder, HashEmbedder, dot};
use wattrouter::head::sigmoid;

/// Passes over the training set. A single linear layer over a fixed embedding
/// converges quickly; longer gains nothing.
const EPOCHS: usize = 60;

/// Step size for gradient descent.
const LEARNING_RATE: f32 = 0.5;

/// Weight each class by inverse frequency. Left off, the cheapest way to reduce
/// loss on this seven-to-one set is to predict the majority for everything.
const BALANCE_CLASSES: bool = true;

/// L2 penalty. Hash embeddings collide, so shrinking the coefficients keeps any
/// one collision from dominating a score.
const L2: f32 = 1e-4;

/// One labelled example.
struct Example {
    embedding: Vec<f32>,
    label: f32,
    /// How much this example counts, from class balancing.
    weight: f32,
}

/// Read and embed the training set.
#[allow(clippy::cast_possible_truncation)] // labels are exactly 0.0, 0.5 or 1.0
fn load(path: &str, embedder: &dyn Embedder, limit: usize) -> std::io::Result<Vec<Example>> {
    let file = std::fs::File::open(path)?;
    let mut examples = Vec::new();

    for line in std::io::BufReader::new(file).lines() {
        if examples.len() >= limit {
            break;
        }
        let line = line?;
        let Ok(row) = serde_json::from_str::<serde_json::Value>(&line) else {
            continue;
        };
        let (Some(text), Some(label)) = (
            row.get("text").and_then(|t| t.as_str()),
            row.get("label").and_then(serde_json::Value::as_f64),
        ) else {
            continue;
        };
        let label = label as f32;
        // A prompt the router could not embed at serving time is one it should
        // not be fitted on either.
        if let Ok(embedding) = embedder.embed(text) {
            examples.push(Example {
                embedding,
                label,
                weight: 1.0,
            });
        }
    }
    Ok(examples)
}

/// Weight each example by the inverse frequency of its class.
#[allow(clippy::cast_precision_loss)] // counts are far below f32's limit
fn balance(examples: &mut [Example]) {
    let hard = examples.iter().filter(|e| e.label > 0.5).count().max(1) as f32;
    let easy = examples.iter().filter(|e| e.label < 0.5).count().max(1) as f32;
    let total = hard + easy;
    for example in examples.iter_mut() {
        example.weight = if example.label > 0.5 {
            total / (2.0 * hard)
        } else if example.label < 0.5 {
            total / (2.0 * easy)
        } else {
            1.0
        };
    }
    eprintln!("class balance: {hard:.0} strong-wins, {easy:.0} weak-wins");
}

/// Fit by gradient descent, returning the coefficients and the bias.
#[allow(clippy::cast_precision_loss)] // counts are far below f32's limit
fn fit(examples: &[Example]) -> (Vec<f32>, f32) {
    let mut weights = vec![0.0f32; DIM];
    let mut bias = 0.0f32;
    let n: f32 = examples.iter().map(|e| e.weight).sum();

    for epoch in 0..EPOCHS {
        let mut grad = vec![0.0f32; DIM];
        let mut grad_bias = 0.0f32;
        let mut loss = 0.0f32;
        // Only the epochs that print it: two logarithms per example across the
        // 56 epochs that discard it is ~13M transcendental calls wasted.
        let reporting = epoch % 20 == 0 || epoch + 1 == EPOCHS;

        for example in examples {
            let predicted = sigmoid(dot(&weights, &example.embedding) + bias);
            let error = predicted - example.label;

            let error = error * example.weight;
            for (g, x) in grad.iter_mut().zip(&example.embedding) {
                *g += error * x;
            }
            grad_bias += error;

            // Cross-entropy, clamped away from zero so a confident correct
            // prediction does not take the logarithm of zero.
            let p = predicted.clamp(1e-7, 1.0 - 1e-7);
            loss -=
                example.weight * (example.label * p.ln() + (1.0 - example.label) * (1.0 - p).ln());
        }

        for (w, g) in weights.iter_mut().zip(&grad) {
            *w -= LEARNING_RATE * (g / n + L2 * *w);
        }
        bias -= LEARNING_RATE * grad_bias / n;

        if reporting {
            eprintln!("epoch {epoch:3}  loss {:.4}", loss / n);
        }
    }
    (weights, bias)
}

/// Report how well the head ranks, and where the thresholds should sit.
///
/// Separation of the class means is reported but is not the deciding number.
/// Only ~9% of prompts need the strong model, so a head can rank usefully while
/// the two means sit almost on top of each other. What matters for routing is
/// whether a harder prompt scores above an easier one (AUC) because the policy
/// thresholds a distribution rather than testing an absolute value.
///
/// The percentiles are the output to act on. Absolute thresholds are wrong when
/// scores cluster: they must be set from the distribution so that a chosen share
/// of traffic reaches each tier, which is what RouteLLM's threshold calibration
/// does and what this prints.
///
/// # Returns
/// The calibrated `(cheap_max, mid_max)` thresholds, at p50 and p85 of the score
/// distribution.
#[allow(clippy::cast_precision_loss)] // counts are far below f32's limit
fn report(examples: &[Example], weights: &[f32], bias: f32) -> (f32, f32) {
    // The serving formula, not a second one: these scores become the thresholds
    // shipped inside the head, so calibrating against a different implementation
    // would calibrate for a router that does not exist.
    let score_of = |e: &Example| sigmoid(dot(weights, &e.embedding) + bias);

    let mut hard: Vec<f32> = Vec::new();
    let mut easy: Vec<f32> = Vec::new();
    let mut all: Vec<f32> = Vec::with_capacity(examples.len());
    for example in examples {
        let score = score_of(example);
        all.push(score);
        if example.label > 0.5 {
            hard.push(score);
        } else if example.label < 0.5 {
            easy.push(score);
        }
    }

    let mean = |v: &[f32]| v.iter().sum::<f32>() / v.len().max(1) as f32;
    eprintln!(
        "mean score: strong-wins {:.3} over {}, weak-wins {:.3} over {}",
        mean(&hard),
        hard.len(),
        mean(&easy),
        easy.len()
    );
    eprintln!("separation: {:.3}", mean(&hard) - mean(&easy));
    eprintln!("AUC: {:.3}  (0.5 is a coin flip)", auc(&hard, &easy));

    all.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let at = |q: f64| all[((all.len() as f64 - 1.0) * q) as usize];
    eprintln!(
        "score percentiles: p50 {:.4}  p65 {:.4}  p85 {:.4}  p95 {:.4}",
        at(0.50),
        at(0.65),
        at(0.85),
        at(0.95)
    );
    eprintln!(
        "calibrated thresholds: cheap_max {:.4} (p50), mid_max {:.4} (p85)",
        at(0.50),
        at(0.85)
    );
    (at(0.50), at(0.85))
}

/// Probability that a randomly chosen hard prompt outscores an easy one.
///
/// Computed by rank rather than by comparing every pair, which would be
/// quadratic over a hundred thousand examples.
#[allow(clippy::cast_precision_loss)] // counts are far below f64's limit
fn auc(hard: &[f32], easy: &[f32]) -> f64 {
    if hard.is_empty() || easy.is_empty() {
        return 0.5;
    }
    let mut all: Vec<(f32, bool)> = hard
        .iter()
        .map(|s| (*s, true))
        .chain(easy.iter().map(|s| (*s, false)))
        .collect();
    all.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));

    // Sum of ranks of the positive class, one-based, ties left un-averaged: the
    // scores are continuous, so exact ties are vanishingly rare.
    let rank_sum: f64 = all
        .iter()
        .enumerate()
        .filter(|(_, (_, is_hard))| *is_hard)
        .map(|(i, _)| (i + 1) as f64)
        .sum();

    let n_hard = hard.len() as f64;
    let n_easy = easy.len() as f64;
    (rank_sum - n_hard * (n_hard + 1.0) / 2.0) / (n_hard * n_easy)
}

fn main() -> std::io::Result<()> {
    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "train/prompts.jsonl".to_owned());

    let limit: usize = std::env::var("TRAIN_LIMIT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(usize::MAX);

    // Which embedder is used decides what the head means, so it is named rather
    // than inferred: a head is only valid with the embedder that produced its
    // vectors, and the output file records which that was.
    let embedder: Box<dyn Embedder> = if std::env::var("TRAIN_EMBEDDER").as_deref() == Ok("onnx") {
        #[cfg(feature = "onnx")]
        {
            let dir = std::env::var("WATTROUTER_MODEL_CACHE")
                .unwrap_or_else(|_| "/tmp/wattrouter-models".to_owned());
            Box::new(
                wattrouter::embed::OnnxEmbedder::new(std::path::Path::new(&dir))
                    .expect("onnx model loads"),
            )
        }
        #[cfg(not(feature = "onnx"))]
        panic!("built without the onnx feature")
    } else {
        Box::new(HashEmbedder::new())
    };
    eprintln!("embedder: {}", embedder.id());

    let mut examples = load(&path, embedder.as_ref(), limit)?;
    eprintln!("loaded {} examples from {path}", examples.len());
    assert!(!examples.is_empty(), "no usable examples in {path}");

    if BALANCE_CLASSES {
        balance(&mut examples);
    }
    let (weights, bias) = fit(&examples);
    let (cheap_max, mid_max) = report(&examples, &weights, bias);

    // The thresholds travel with the head. They are a property of this fit:
    // scores from a different embedder or a different run land elsewhere, so
    // shipping them separately would let the two drift apart silently.
    let head = serde_json::json!({
        "embedder": embedder.id(),
        "weights": weights,
        "bias": bias,
        "cheap_max": cheap_max,
        "mid_max": mid_max,
    });
    println!("{head}");
    Ok(())
}
