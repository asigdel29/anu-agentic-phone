//! load.rs — what the router does when turns overlap.
//!
//! History
//!   2026-08-07  A. Sigdel  Created.
//!
//! Contents
//!   `CONCURRENCY`  The in-flight request counts swept.
//!   `PER_LEVEL`    Requests per level, bounded by loopback ports rather than time.
//!   `quick`        Whether to run the reduced sweep CI uses.
//!   `per_client`   How that budget divides among a level's clients.
//!   `check`        Assert a gate, printing either way.
//!   `levels`       The concurrency levels this run sweeps.
//!   `drain`        The pause between levels, so TIME_WAIT does not accumulate.
//!   `Child`        A spawned router, killed however this ends.
//!   `mock`         An upstream that answers immediately.
//!   `free_port`    A port nothing is listening on.
//!   `percentile`   One order statistic from sorted latencies.
//!   `run`          Holds `n` requests in flight and reports the spread.
//!   `main`         Sweeps concurrency both ways, then gates the ratios.
//!
//! Every other figure in this crate is single-threaded: `decide` times a closure
//! in a loop, `serve` times one request at a time. The board runs an agent, a
//! coding harness and their background work against one router, so what happens
//! when several turns overlap is the question none of them ask.
//!
//! Three things are only interesting under load, and this is what they are:
//!
//!   cache    `DecisionCache` is a mutex over one store, taken on the score
//!            lookup, the score insert and `escalate`. Its own header says
//!            concurrent callers "contend only on a cache hit". Running every
//!            request on one session is the worst case for that claim, and
//!            running each on its own is the best; the gap between them is the
//!            answer.
//!   pool     `pool_max_idle_per_host` is 8. Past that, upstream calls queue.
//!   state    `AppState` is shared through an `Arc` with no lock, by design.
//!
//! This drives the real binary rather than a copy of its handler — spawned with
//! the upstream pointed at a mock — so axum's dispatch, the extractor, the
//! decision, the cache and the pool are all the ones that ship. `serve` explains
//! why reproducing the handler in a bench is the thing to avoid.

use std::io::ErrorKind;
use std::process::{Child as Process, Command, Stdio};
use std::time::{Duration, Instant};

use axum::Router;
use axum::routing::post;
use serde_json::json;
use tokio::net::TcpListener;

/// The in-flight request counts swept.
///
/// One is the baseline every other row is read against. Eight is the pool's idle
/// cap, so it is the last point before upstream calls queue. Past that the
/// question is whether the router degrades gracefully or falls over. The board
/// runs an agent, a coding harness and their background work, so its real load
/// sits at the low end and the rest of the sweep is headroom.
const CONCURRENCY: [usize; 5] = [1, 8, 32, 128, 256];

/// Requests per concurrency level, split across its clients.
///
/// Held constant rather than per-client, because the binding constraint is
/// loopback ephemeral ports, not time. The range here is 16 384 wide with a
/// 15-second MSL, and the router keeps only 8 idle upstream connections — so
/// every request past the eighth in flight closes its connection and parks a
/// port in TIME_WAIT for two MSL. A per-client count of 200 at 256 clients is
/// 51 200 connections against 16 384 ports, which exhausts the range and reports
/// the exhaustion as if it were router latency. It did, before this was fixed:
/// a p99 of 581 ms and a max over a second, none of it the router's doing.
///
/// Four thousand keeps a level comfortably inside the range and still leaves a
/// p99 as an order statistic over forty samples at the top of the sweep.
const PER_LEVEL: usize = 4_000;

/// Whether to run the reduced sweep CI uses.
///
/// The gates are ratios between two workloads measured in the same run, so
/// dropping levels widens the noise around them without moving them. What it
/// must not do is shrink a level, for the reason [`per_client`] records.
fn quick() -> bool {
    std::env::var_os("WATTROUTER_BENCH_QUICK").is_some()
}

/// Requests each client sends at `clients` concurrency, at least twenty so a
/// percentile at the top of the sweep still means something.
///
/// Not reduced in quick mode, and the first draft of that was a mistake worth
/// recording: cutting the budget to a quarter left twenty requests per client at
/// the top of the sweep, where establishing 256 connections costs more than
/// sending them. Throughput then read as a collapse — 9 203/s against a peak of
/// 23 597/s — and the gate below fired on warmup rather than on anything the
/// router did. What actually costs time in a sweep is [`DRAIN`], not the
/// requests, so quick mode trims levels and waiting instead — see [`drain`].
fn per_client(clients: usize) -> usize {
    (PER_LEVEL / clients).max(20)
}

/// The concurrency levels this run sweeps.
///
/// Quick mode drops 8 and 256: the first says little that 1 and 32 do not, and
/// the second is where loopback port exhaustion starts showing up in the failure
/// column — which is a property of the runner's port range, not of the router,
/// and not something to have CI adjudicate.
fn levels() -> &'static [usize] {
    if quick() { &[1, 32, 128] } else { &CONCURRENCY }
}

/// Assert a gate, printing either way, and report whether it held.
///
/// Prints rather than panicking so one run reports every gate it broke instead
/// of only the first.
#[must_use]
fn check(name: &str, held: bool, detail: &str) -> bool {
    println!(
        "  {:<4} {name:<44} {detail}",
        if held { "ok" } else { "FAIL" }
    );
    held
}

/// How long to let TIME_WAIT drain between levels.
///
/// Well under two MSL: the point is to take the edge off the accumulation across
/// a sweep, not to wait it out, which would triple the runtime. This is what a
/// sweep actually spends its time on — the requests themselves are seconds — so
/// it is the first thing quick mode cuts.
fn drain() -> Duration {
    if quick() {
        Duration::from_millis(500)
    } else {
        Duration::from_secs(2)
    }
}

/// A spawned router, killed however this ends.
///
/// A bench that panics partway through would otherwise leave a process holding a
/// port, and the next run would fail to bind for a reason that looks nothing
/// like the cause.
struct Child(Process);

impl Drop for Child {
    fn drop(&mut self) {
        let _ = self.0.kill();
        // Reaped, or the process lingers as a zombie for the life of the shell
        // that started the bench.
        let _ = self.0.wait();
    }
}

/// Start an upstream that answers immediately, and return its base URL.
///
/// It reads the body, as a real upstream must, and returns a minimal completion.
/// Nothing here should be slow: the point is to measure the router, so every
/// millisecond the mock spends would be a millisecond wrongly attributed.
async fn mock() -> String {
    let app = Router::new().route(
        "/chat/completions",
        post(|body: axum::body::Bytes| async move {
            std::hint::black_box(body.len());
            axum::Json(json!({
                "choices": [{"message": {"role": "assistant", "content": "ok"}}]
            }))
        }),
    );
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}")
}

/// A port nothing is listening on.
///
/// Bound and released rather than guessed. There is a window between releasing
/// it and the router binding it, which nothing here can close — the router takes
/// an address, not a listener. In practice the window is microseconds on
/// loopback; if a run ever fails to bind, that is why, and re-running is the fix.
async fn free_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    listener.local_addr().unwrap().port()
}

/// The value at `q` of sorted `samples`, where `q` is in `[0, 1]`.
#[allow(
    clippy::cast_precision_loss,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss
)]
fn percentile(samples: &[u128], q: f64) -> f64 {
    let index = ((samples.len() - 1) as f64 * q).round() as usize;
    samples[index] as f64 / 1e6
}

/// Hold `clients` requests in flight, and report the spread.
///
/// # Arguments
/// * `session` — `Some(id)` puts every request on one session, which is the
///   worst case for the cache mutex; `None` gives each client its own.
///
/// # Returns
/// The median in milliseconds and the throughput, which are what the gates in
/// [`main`] compare. `(0.0, 0.0)` IF every request failed, which reads as a
/// broken gate rather than a suspiciously fast one.
async fn run(base: &str, clients: usize, session: Option<&str>) -> (f64, f64) {
    let http = reqwest::Client::builder()
        // Above the load, or the generator's own pool becomes the bottleneck and
        // this measures the client instead of the router.
        .pool_max_idle_per_host(clients * 2)
        .build()
        .expect("client builds");

    let body = json!({
        "model": "auto",
        "messages": [{"role": "user", "content": "How do I list files?"}],
    });

    let started = Instant::now();
    let mut tasks = Vec::with_capacity(clients);
    for client in 0..clients {
        let http = http.clone();
        let url = format!("{base}/v1/chat/completions");
        let body = body.clone();
        let session = session.map_or_else(|| format!("load-{client}"), ToOwned::to_owned);
        let each = per_client(clients);
        tasks.push(tokio::spawn(async move {
            let mut samples = Vec::with_capacity(each);
            let mut failed = 0_usize;
            for _ in 0..each {
                let at = Instant::now();
                // Counted, never panicked on. A load generator that dies on the
                // first refused connection cannot report the one thing worth
                // knowing about saturation, which is where answers stop arriving.
                match http
                    .post(&url)
                    .header("x-session-id", &session)
                    .json(&body)
                    .send()
                    .await
                {
                    // Drained, or this times the response head, not the request.
                    Ok(response) => match response.bytes().await {
                        Ok(_) => samples.push(at.elapsed().as_nanos()),
                        Err(_) => failed += 1,
                    },
                    Err(_) => failed += 1,
                }
            }
            (samples, failed)
        }));
    }

    let mut samples = Vec::with_capacity(PER_LEVEL);
    let mut failed = 0_usize;
    for task in tasks {
        let (client_samples, client_failed) = task.await.expect("a client finishes");
        samples.extend(client_samples);
        failed += client_failed;
    }
    let elapsed = started.elapsed();
    samples.sort_unstable();

    if samples.is_empty() {
        println!(
            "  {clients:>4}  {:>53}  {failed:>8}",
            "every request failed"
        );
        return (0.0, 0.0);
    }

    #[allow(clippy::cast_precision_loss)]
    let throughput = samples.len() as f64 / elapsed.as_secs_f64();
    let p50 = percentile(&samples, 0.50);
    println!(
        "  {clients:>4}  {p50:>9.2} {:>9.2} {:>9.2} {:>9.2} {throughput:>12.0} {failed:>8}",
        percentile(&samples, 0.95),
        percentile(&samples, 0.99),
        percentile(&samples, 1.0),
    );
    (p50, throughput)
}

#[tokio::main]
async fn main() {
    let upstream = mock().await;
    let port = free_port().await;
    let addr = format!("127.0.0.1:{port}");

    // The binary that ships, not a rebuild of its handler. Cargo points this at
    // whatever profile and features the bench was built with.
    let mut command = Command::new(env!("CARGO_BIN_EXE_wattrouter"));
    command
        .env("NEURALWATT_API_KEY", "load")
        .env("WATTROUTER_UPSTREAM", &upstream)
        .env("WATTROUTER_ADDR", &addr)
        // Its own log lines would interleave with the table and say nothing a
        // reader of this output wants.
        .env("RUST_LOG", "wattrouter=error")
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    let child = match command.spawn() {
        Ok(process) => Child(process),
        Err(e) if e.kind() == ErrorKind::NotFound => {
            panic!("no router binary to load; build it first")
        }
        Err(e) => panic!("cannot spawn the router: {e}"),
    };

    let base = format!("http://{addr}");
    let http = reqwest::Client::new();
    let mut up = false;
    for _ in 0..100 {
        if http.get(format!("{base}/healthz")).send().await.is_ok() {
            up = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(up, "the router never answered /healthz on {addr}");

    println!("router on {addr}, upstream mocked at {upstream}");
    println!("{} requests per level, latency in ms\n", per_client(1));

    // Per workload, one row of (p50, throughput) per concurrency level.
    let mut measured: Vec<Vec<(f64, f64)>> = Vec::with_capacity(2);

    for (label, session) in [
        ("a session each — the cache's best case", None),
        ("one shared session — its worst", Some("load-shared")),
    ] {
        println!("{label}");
        println!(
            "  {:>4}  {:>9} {:>9} {:>9} {:>9} {:>12} {:>8}",
            "conc", "p50", "p95", "p99", "max", "req/s", "failed"
        );
        let mut rows = Vec::with_capacity(levels().len());
        for &clients in levels() {
            rows.push(run(&base, clients, session).await);
            tokio::time::sleep(drain()).await;
        }
        measured.push(rows);
        println!();
    }

    // Explicit, so the reason the process outlives the sweep is stated rather
    // than resting on where the binding happens to fall.
    drop(child);

    // Gates on ratios within one run, for the reason `serve` states: an absolute
    // latency says as much about the runner as about the code, and a threshold
    // loose enough not to be flaky on a shared runner is loose enough to miss
    // everything short of a catastrophe.
    println!("gates");
    let (separate, shared) = (&measured[0], &measured[1]);

    // `cache.rs` says concurrent callers "contend only on a cache hit". One
    // session for everybody is the worst case for that and a session each is the
    // best, so the two agreeing is the claim holding. They currently match
    // within noise; a lock held across the upstream call would separate them by
    // orders of magnitude, not by the factor gated here.
    let mut held = true;
    for (index, clients) in levels().iter().enumerate() {
        let (best, worst) = (separate[index].0, shared[index].0);
        held &= check(
            "one session costs no more than a session each",
            worst < best * 3.0 + 1.0,
            &format!("{worst:.2}ms against {best:.2}ms at {clients} in flight"),
        );
    }

    // Throughput flattens once the router saturates and must stay flat. A
    // collapse at the top of the sweep is the shape of a queue that has stopped
    // draining, which is the failure worth catching — the absolute figure it
    // flattens at is the runner's business, so only the ratio is gated.
    for rows in [separate, shared] {
        let peak = rows.iter().map(|r| r.1).fold(0.0_f64, f64::max);
        let top = rows[rows.len() - 1].1;
        held &= check(
            "throughput does not collapse under load",
            top * 2.5 > peak,
            &format!("{top:.0}/s at the top against a peak of {peak:.0}/s"),
        );
    }

    if !held {
        eprintln!("\na performance gate broke; the ratios above say which");
        std::process::exit(1);
    }
}
