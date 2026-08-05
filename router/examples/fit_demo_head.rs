//! Fits a throwaway head from two labelled word lists, to demonstrate that a
//! head changes routing. Not the real training path — that lands with the
//! dataset — but it uses the router's own embedder, so the vectors are exactly
//! what it sees at runtime.
use wattrouter::embed::{DIM, Embedder, HashEmbedder};

fn main() {
    let e = HashEmbedder::new();
    let hard = [
        "design a distributed consensus protocol and prove its safety",
        "refactor the compiler backend to use SSA form",
        "debug a race condition across three services",
        "architect a migration from a monolith to services",
    ];
    let easy = [
        "what time is it",
        "capital of france",
        "say hello",
        "what is 2 + 2",
    ];
    let mut w = vec![0.0f32; DIM];
    for t in hard {
        for (i, v) in e.embed(t).unwrap().iter().enumerate() {
            w[i] += *v;
        }
    }
    for t in easy {
        for (i, v) in e.embed(t).unwrap().iter().enumerate() {
            w[i] -= *v;
        }
    }
    // Scale so the separation lands across the default thresholds.
    for x in &mut w {
        *x *= 12.0;
    }
    let out = serde_json::json!({"embedder": e.id(), "weights": w, "bias": 0.0});
    println!("{out}");
}
