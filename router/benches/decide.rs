//! decide.rs — how long the router spends deciding, with no network in the way.
//!
//! History
//!   2026-08-06  A. Sigdel  Created.
//!
//! Contents
//!   `bench`  Times one closure and reports nanoseconds per call.
//!   `main`   Times each stage of the decision path and the whole of it.
//!
//! End-to-end request latency is dominated by the upstream — a round trip plus
//! inference, milliseconds to seconds, which nothing here can touch. What this
//! measures is the part the router owns: the work between reading a body and
//! opening a socket. That is pure CPU, it runs on every request, and it is the
//! only part whose cost is a choice rather than a fact.
//!
//! Hand-rolled rather than criterion: what is wanted is a per-stage figure on a
//! fixed input, the harness is thirty lines, and a benchmark dependency only CI
//! would ever build is a poor trade on a crate this size.

use std::hint::black_box;
use std::time::Instant;

use wattrouter::cache::DecisionCache;
use wattrouter::chain::chain_for;
use wattrouter::classify::classify;
use wattrouter::config::Config;
use wattrouter::embed::{Embedder, HashEmbedder};
use wattrouter::head::Head;
use wattrouter::policy::{Thresholds, decide};
use wattrouter::tier::Tier;

/// Iterations per stage. Large enough that timer resolution is not the signal.
const N: usize = 20_000;

/// Time `f` and report nanoseconds per call.
fn bench(name: &str, mut f: impl FnMut()) -> f64 {
    for _ in 0..N / 10 {
        f();
    }
    let start = Instant::now();
    for _ in 0..N {
        f();
    }
    #[allow(clippy::cast_precision_loss)]
    let per = start.elapsed().as_nanos() as f64 / N as f64;
    println!("  {name:<44} {per:>9.1} ns");
    per
}

fn main() {
    // SAFETY: single-threaded, before anything else reads the environment.
    unsafe { std::env::set_var("NEURALWATT_API_KEY", "bench") };
    let config = Config::from_env().expect("bench config");
    let embedder = HashEmbedder::new();
    let thresholds = Thresholds::default();
    let cache = DecisionCache::new();

    // A realistic turn: system prompt, a little history, and a real question.
    let body = serde_json::json!({
        "model": "auto",
        "messages": [
            {"role": "system", "content": "You are a careful engineer. ".repeat(20)},
            {"role": "user", "content": "How do I list files?"},
            {"role": "assistant", "content": "Use ls. ".repeat(10)},
            {"role": "user", "content":
                "Refactor the authentication module to use refresh tokens, update the \
                 integration tests, and keep the migration backwards compatible with \
                 sessions issued by the previous scheme."},
        ]
    });

    let head: Option<Head> = std::env::var("BENCH_HEAD")
        .ok()
        .and_then(|p| Head::load(std::path::Path::new(&p), &embedder.id()).ok());
    println!(
        "head: {}\n",
        head.as_ref()
            .map_or("none (unscored path)", Head::fitted_on)
    );

    let classified = classify(&body, None);
    let text = classified.text.clone();
    let vector = embedder.embed(&text).expect("bench text embeds");

    println!("stages");
    bench("classify", || {
        black_box(classify(black_box(&body), None));
    });
    bench("embed", || {
        black_box(embedder.embed(black_box(&text)).unwrap());
    });
    if let Some(h) = &head {
        bench("head.score", || {
            black_box(h.score(black_box(&vector)));
        });
    }
    bench("cache.score_for (miss)", || {
        black_box(cache.score_for(black_box(&text)));
    });
    cache.remember_score(&text, 0.7);
    bench("cache.score_for (hit)", || {
        black_box(cache.score_for(black_box(&text)));
    });
    bench("cache.escalate (unchanged)", || {
        black_box(cache.escalate(black_box("bench-session"), Tier::Mid));
    });
    bench("decide", || {
        black_box(decide(
            black_box(&classified.signals),
            Some(0.6),
            &thresholds,
        ));
    });
    bench("chain_for", || {
        black_box(chain_for(black_box(&config), Tier::Mid));
    });

    // black_box on every intermediate. Without it the optimiser observes that
    // the embedding is unused when no head is loaded and deletes the call, which
    // reports the uncached path as cheaper than the embed it contains.
    println!("\nwhole decision path");
    let cold = bench("uncached (classify+embed+score+decide+chain)", || {
        let c = black_box(classify(black_box(&body), None));
        let v = black_box(embedder.embed(&c.text).unwrap());
        let s = black_box(head.as_ref().map_or(0.5, |h| h.score(&v)));
        let d = black_box(decide(&c.signals, Some(s), &thresholds));
        black_box(chain_for(&config, d.tier));
    });
    let warm = bench("cached   (classify+cache+decide+chain)", || {
        let c = black_box(classify(black_box(&body), None));
        let s = black_box(cache.score_for(&c.text));
        let d = black_box(decide(&c.signals, s, &thresholds));
        black_box(chain_for(&config, d.tier));
    });

    println!(
        "\n  cached path is {:.1}x cheaper than uncached",
        cold / warm
    );
}
