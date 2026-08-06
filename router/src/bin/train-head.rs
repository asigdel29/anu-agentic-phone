//! train-head.rs — fit the routing head.
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
//! implementation would be free to drift silently — showing up as bad routing,
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
//! overlap, and difficulty is not a lexical property — "prove this is NP-hard"
//! and "spell NP-hard" share their vocabulary. No linear model over these
//! features can separate them.
//!
//! So the ONNX backend is a prerequisite for scoring, not an optimisation, and
//! the router ships unscored until it lands. That is a supported state: every
//! rule that does not depend on difficulty still applies. These weights are
//! deliberately not committed as a default — a head that separates nothing would
//! route worse than no head at all, while looking like it worked.

use std::io::BufRead as _;

use wattrouter::embed::{DIM, Embedder, HashEmbedder};

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

/// Logistic, split on sign so a large logit cannot overflow `exp`.
fn sigmoid(x: f32) -> f32 {
    if x >= 0.0 {
        1.0 / (1.0 + (-x).exp())
    } else {
        let e = x.exp();
        e / (1.0 + e)
    }
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

        for example in examples {
            let logit: f32 = weights
                .iter()
                .zip(&example.embedding)
                .map(|(w, x)| w * x)
                .sum::<f32>()
                + bias;
            let predicted = sigmoid(logit);
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

        if epoch % 20 == 0 || epoch + 1 == EPOCHS {
            eprintln!("epoch {epoch:3}  loss {:.4}", loss / n);
        }
    }
    (weights, bias)
}

/// Report how well the head separates the two classes.
///
/// Accuracy alone misleads on an unbalanced set, so the per-class mean score is
/// reported too: a head that separates shows a gap, one that has not shows none.
/// That number is what revealed this configuration does not work.
#[allow(clippy::cast_precision_loss)] // counts are far below f32's limit
fn report(examples: &[Example], weights: &[f32], bias: f32) {
    let (mut hard_sum, mut hard_n, mut easy_sum, mut easy_n) = (0.0f32, 0usize, 0.0f32, 0usize);
    let mut correct = 0usize;

    for example in examples {
        let score = sigmoid(
            weights
                .iter()
                .zip(&example.embedding)
                .map(|(w, x)| w * x)
                .sum::<f32>()
                + bias,
        );
        if example.label > 0.5 {
            hard_sum += score;
            hard_n += 1;
            if score > 0.5 {
                correct += 1;
            }
        } else if example.label < 0.5 {
            easy_sum += score;
            easy_n += 1;
            if score <= 0.5 {
                correct += 1;
            }
        }
    }

    let hard_mean = hard_sum / hard_n.max(1) as f32;
    let easy_mean = easy_sum / easy_n.max(1) as f32;
    let decided = (hard_n + easy_n).max(1) as f32;
    eprintln!(
        "mean score: strong-wins {hard_mean:.3} over {hard_n}, weak-wins {easy_mean:.3} over {easy_n}"
    );
    eprintln!("separation: {:.3}", hard_mean - easy_mean);
    eprintln!(
        "accuracy on decided examples: {:.1}%",
        100.0 * correct as f32 / decided
    );
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
    report(&examples, &weights, bias);

    let head = serde_json::json!({
        "embedder": embedder.id(),
        "weights": weights,
        "bias": bias,
    });
    println!("{head}");
    Ok(())
}
