//! Confirms the ONNX backend loads and separates meaning, not just vocabulary.
#[cfg(feature = "onnx")]
fn main() {
    use wattrouter::embed::{Embedder, HashEmbedder, OnnxEmbedder, cosine};
    let dir = std::env::var("WATTROUTER_MODEL_CACHE")
        .unwrap_or_else(|_| "/tmp/wattrouter-models".into());
    let t = std::time::Instant::now();
    let onnx = OnnxEmbedder::new(std::path::Path::new(&dir)).expect("model loads");
    println!("load: {:?}  id={}", t.elapsed(), onnx.id());

    // Same vocabulary, opposite difficulty. This is the pair hash features cannot
    // tell apart, and the reason the head needs this backend.
    let hard = "prove that this scheduling problem is NP-hard";
    let easy = "how do you spell NP-hard";
    let alsohard = "show this problem has no polynomial time algorithm";

    for (name, e) in [("hash", &HashEmbedder::new() as &dyn Embedder), ("onnx", &onnx)] {
        let h = e.embed(hard).unwrap();
        let s = cosine(&h, &e.embed(easy).unwrap());
        let d = cosine(&h, &e.embed(alsohard).unwrap());
        println!("{name:5}  hard~easy {s:.3}   hard~alsohard {d:.3}   gap {:+.3}", d - s);
    }

    let t = std::time::Instant::now();
    for _ in 0..50 { onnx.embed(hard).unwrap(); }
    println!("onnx embed: {:.1} ms/call", t.elapsed().as_secs_f64() * 1000.0 / 50.0);
}
#[cfg(not(feature = "onnx"))]
fn main() { println!("built without the onnx feature"); }
