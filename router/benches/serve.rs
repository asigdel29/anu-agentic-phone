//! serve.rs: what the router costs between the socket and the next socket.
//!
//! History
//!   2026-08-07  A. Sigdel  Created.
//!
//! Contents
//!   `SIZES`         The conversation sizes swept, smallest first.
//!   `conversation`  A request body of roughly a given size.
//!   `ROUNDS`        Timed rounds per stage, of which the fastest is reported.
//!   `quick`         Whether to run the reduced sweep CI uses.
//!   `check`         Assert a gate, printing either way.
//!   `bench`         Times one closure and reports nanoseconds per call.
//!   `bench_async`   The same, inside one runtime rather than one per call.
//!   `main`          Sweeps every stage across every size, then gates the ratios.
//!
//! [`super::decide`] measures the work between reading a body and opening a
//! socket, and says so. This measures the two stages on either side of that,
//! which nothing has ever timed:
//!
//!   extract  `Json(body): Json<Value>` buffers the request and parses it into a
//!            whole `serde_json::Value` tree. The router reads the last user
//!            message and a character count off it; it pays for the entire tree.
//!   forward  `Upstream::forward` re-serializes that same tree back to bytes and
//!            spends one loopback round trip.
//!
//! Both sit on the request path of every turn. The question this answers is
//! whether either grows with conversation length, because the argument for
//! collapsing the loopback hop keeps being made without a number attached.
//!
//! What is deliberately NOT measured: axum's route dispatch and extractor
//! plumbing. A bench links the library, and `app` and `chat_completions` live in
//! the binary, and reproducing them here would time a copy, and a copy free to
//! drift from the handler is the thing this crate keeps refusing to keep. The
//! omission is small: dispatch is a match on a path, against a parse and a round
//! trip. Moving `app` into the library would close it, and is its own change.
//!
//! No network and no inference: the upstream is a mock on an ephemeral port that
//! answers immediately. Runs are free and repeatable, which is what lets this go
//! into CI later.

use std::hint::black_box;
use std::time::Instant;

use axum::Router;
use axum::routing::post;
use serde_json::{Value, json};
use tokio::net::TcpListener;

use wattrouter::chain::chain_for;
use wattrouter::classify::classify;
use wattrouter::config::Config;
use wattrouter::policy::{Thresholds, decide};
use wattrouter::tier::Tier;
use wattrouter::upstream::Upstream;

/// The conversation sizes swept, as approximate body bytes, with the total
/// iteration budget each is timed for.
///
/// Budgets fall as the body grows so a sweep stays under a couple of minutes:
/// the large sizes are slow precisely because of the effect being measured. Each
/// is split across [`ROUNDS`], so the smallest still takes a hundred samples per
/// round, enough that the fastest round is measuring work rather than luck.
///
/// The last entry sits just under `LONG_CONTEXT_TOKENS * CHARS_PER_TOKEN`, which
/// is the largest body the policy will route anywhere but the long tier, so it
/// is the worst case the router actually serves rather than an invented one.
const SIZES: [(&str, usize, usize); 4] = [
    ("one short turn (~200 B)", 200, 20_000),
    ("a working conversation (~8 KB)", 8_192, 10_000),
    ("a long session (~120 KB)", 122_880, 2_000),
    ("at the long-tier threshold (~750 KB)", 768_000, 500),
];

/// A request body of roughly `bytes`, shaped like a real turn.
///
/// A system prompt, alternating history, and a final user message, because
/// `classify` reads the last user message and estimates context from all of
/// them, so a body that is one enormous string would exercise neither the tree
/// walk nor the search backwards.
fn conversation(bytes: usize) -> Value {
    let mut messages = vec![json!({
        "role": "system",
        "content": "You are a careful engineer. Prefer the smallest change that works.",
    })];

    // Split the budget across turns of a plausible length rather than a few
    // giant ones: parse cost follows the node count, not only the byte count,
    // and a handful of huge strings would hide that.
    const PER_TURN: usize = 400;
    let turns = bytes / PER_TURN;
    for i in 0..turns {
        let role = if i % 2 == 0 { "user" } else { "assistant" };
        messages.push(json!({
            "role": role,
            "content": format!("{i:04} ").repeat(PER_TURN / 5),
        }));
    }

    messages.push(json!({
        "role": "user",
        "content": "Refactor the authentication module to use refresh tokens, update the \
                    integration tests, and keep the migration backwards compatible with \
                    sessions issued by the previous scheme.",
    }));

    json!({ "model": "auto", "messages": messages })
}

/// Timed rounds per stage, of which the fastest is reported.
///
/// The mean over one long run is not stable enough to compare against: two runs
/// of an earlier draft disagreed by 2.3x on the loopback stage, because a round
/// trip competes with the scheduler, with background load and with the thermal
/// state of the machine. Every one of those can only make a round slower, never
/// faster, so the minimum is the estimate closest to the work itself, and it is
/// what makes a regression threshold mean anything.
const ROUNDS: usize = 5;

/// Whether to run the reduced sweep CI uses.
///
/// The gates below are ratios between stages measured in the same run, so they
/// hold at any sample count; only the noise around them grows. A tenth of the
/// budget keeps the CI job to a couple of minutes and still leaves every gate
/// with the margin recorded beside it.
fn quick() -> bool {
    std::env::var_os("WATTROUTER_BENCH_QUICK").is_some()
}

/// Assert a gate, printing either way, and report whether it held.
///
/// Prints rather than panicking so one run reports every gate it broke instead
/// of only the first, which is the difference between one CI round trip and
/// four.
#[must_use]
fn check(name: &str, held: bool, detail: &str) -> bool {
    println!(
        "  {:<4} {name:<44} {detail}",
        if held { "ok" } else { "FAIL" }
    );
    held
}

/// Time `f` and report nanoseconds per call, fastest round.
///
/// `n` is the whole budget, split across [`ROUNDS`], so raising the round count
/// costs stability rather than time.
fn bench(name: &str, n: usize, mut f: impl FnMut()) -> f64 {
    let per_round = (n / ROUNDS).max(1);
    for _ in 0..per_round {
        f();
    }
    let mut best = f64::MAX;
    for _ in 0..ROUNDS {
        let start = Instant::now();
        for _ in 0..per_round {
            f();
        }
        #[allow(clippy::cast_precision_loss)]
        let per = start.elapsed().as_nanos() as f64 / per_round as f64;
        best = best.min(per);
    }
    println!("    {name:<34} {best:>12.0} ns");
    best
}

/// Time an async `f`, reporting nanoseconds per call, fastest round.
///
/// The rounds run inside one runtime rather than one per call, so what is
/// reported is the work and not the cost of entering the runtime.
async fn bench_async<F, Fut>(name: &str, n: usize, mut f: F) -> f64
where
    F: FnMut() -> Fut,
    Fut: Future<Output = ()>,
{
    let per_round = (n / ROUNDS).max(1);
    for _ in 0..per_round {
        f().await;
    }
    let mut best = f64::MAX;
    for _ in 0..ROUNDS {
        let start = Instant::now();
        for _ in 0..per_round {
            f().await;
        }
        #[allow(clippy::cast_precision_loss)]
        let per = start.elapsed().as_nanos() as f64 / per_round as f64;
        best = best.min(per);
    }
    println!("    {name:<34} {best:>12.0} ns");
    best
}

/// Start a mock upstream that answers immediately, and return its base URL.
///
/// It reads the body, so the bench pays for the write and the server pays for
/// the read, as a real upstream would. It does not parse it: a provider's own
/// parse is not the router's cost.
async fn mock_upstream() -> String {
    let app = Router::new().route(
        "/chat/completions",
        post(|body: axum::body::Bytes| async move {
            black_box(body.len());
            r#"{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"#
        }),
    );
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}")
}

#[tokio::main]
async fn main() {
    // SAFETY: single-threaded, before anything else reads the environment.
    unsafe { std::env::set_var("NEURALWATT_API_KEY", "bench") };
    let config = Config::from_env().expect("bench config");
    let thresholds = Thresholds::default();

    let base = mock_upstream().await;
    let upstream = Upstream::new(&base, "bench").expect("client builds");
    // After binding, as the binary does: the first timed request should find a
    // live connection rather than paying for DNS, TCP and the handshake.
    upstream.prewarm().await;

    println!("mock upstream at {base}\n");

    // Per size, the three stages the gates below compare.
    let mut measured: Vec<(f64, f64, f64)> = Vec::with_capacity(SIZES.len());

    for (label, bytes, n) in SIZES {
        let n = if quick() { (n / 10).max(50) } else { n };
        let body = conversation(bytes);
        let encoded = serde_json::to_vec(&body).expect("a body serializes");
        let chain = chain_for(&config, Tier::Mid);

        println!("{label}: {} B encoded, {n} iterations", encoded.len());

        let extract = bench("extract (parse to Value)", n, || {
            black_box(serde_json::from_slice::<Value>(black_box(&encoded)).unwrap());
        });

        let classified = classify(&body, None);
        let classify_ns = bench("classify", n, || {
            black_box(classify(black_box(&body), None));
        });

        let decide_ns = bench("decide", n, || {
            black_box(decide(
                black_box(&classified.signals),
                Some(0.6),
                &thresholds,
            ));
        });
        measured.push((extract, classify_ns, decide_ns));

        // `forward` takes the body by value, so a clone is unavoidable per call.
        // Timed on its own line rather than hidden inside the next one: it is
        // the router's cost either way, but a reader comparing this against a
        // design that never clones needs to see the two apart.
        let clone = bench("clone the body", n, || {
            black_box(black_box(&body).clone());
        });

        // Both borrowed, so the closure can be called again next iteration
        // rather than consuming what it captured.
        let upstream = &upstream;
        let chain = &chain;
        let forward = bench_async("forward (serialize + loopback)", n, || {
            let body = body.clone();
            async move {
                let response = upstream.forward(chain, body).await.expect("mock answers");
                // Drain, or this times the response head and not the round trip.
                let _ = axum::body::to_bytes(response.into_body(), usize::MAX)
                    .await
                    .expect("a body arrives");
            }
        })
        .await;

        // What one turn costs the router outside the decision it was already
        // measured making. `forward` already contains one clone, so the clone
        // line is not added again.
        println!(
            "    {:<34} {:>12.0} ns   ({:.3} ms)",
            "── extract + forward",
            extract + forward,
            (extract + forward) / 1e6
        );
        println!("    {:<34} {clone:>12.0} ns\n", "   of which clone");
    }

    println!("Read the sweep, not one row: the question is which stages grow with the body.\n");

    // Gates, not thresholds. An absolute figure says as much about the machine
    // as about the code: two runs of this on one idle laptop disagreed by 2.3x
    // before the fastest-round change, and a shared CI runner is worse. A ratio
    // between two stages timed in the same run survives that: a slow runner
    // scales both, so only the code can move them apart.
    println!("gates");
    let (first_extract, first_classify, _) = measured[0];
    let (last_extract, last_classify, _) = measured[measured.len() - 1];

    let extract_growth = last_extract / first_extract;
    let classify_growth = last_classify / first_classify;
    // `classify` truncates at MAX_ROUTING_CHARS; `extract` parses everything. So
    // across a 2500x range of bodies the first must grow far less than the
    // second. Removing the cap is the change this catches, and it would invert
    // the two rather than nudge them. Measured at 47x against 765x, a margin of
    // four over the gate.
    let mut held = check(
        "classify grows far less than extract",
        classify_growth * 4.0 < extract_growth,
        &format!("{classify_growth:.0}x against {extract_growth:.0}x over the sweep"),
    );

    // `decide` is pure arithmetic over a handful of fields and measures 1-2ns
    // against classify's 200ns and up. Anything that made the policy touch the
    // message list, or allocate, closes that gap long before it reaches parity.
    for (index, (_, classify_ns, decide_ns)) in measured.iter().enumerate() {
        held &= check(
            "decide stays far below classify",
            decide_ns * 10.0 < *classify_ns,
            &format!(
                "{decide_ns:.0}ns against {classify_ns:.0}ns at size {}",
                index + 1
            ),
        );
    }

    if !held {
        // Non-zero, or CI reports a broken gate as a passing job.
        eprintln!("\na performance gate broke; the ratios above say which");
        std::process::exit(1);
    }
}
