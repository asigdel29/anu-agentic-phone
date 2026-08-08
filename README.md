# anu-agentic-stack

A personal agent stack. One routing core, in three places: a small aarch64 board, an iPhone,
and — next — an Android phone. The board runs [Hermes
Agent](https://github.com/NousResearch/hermes-agent) as the harness with
[zeromem](https://github.com/ptaranat/zeromem) for memory that costs no tokens; the phones
run their own turn loop over the same core. All three route to the cheapest model on
[NeuralWatt](https://neuralwatt.com) that can still do the job.

```
  you ── CLI / chat ──►  Hermes Agent   (conversation, tools, coding)
                           └─ memory: zeromem      (Rust core, SQLite, zero LLM calls)
                                     │
                                     │  OpenAI-compatible, 127.0.0.1
                                     ▼
                           wattrouter  (Rust)
                             heuristics → sticky tier → embed → score → tier
                                     │  pooled HTTPS, streaming passthrough
                                     ▼
                           api.neuralwatt.com/v1
```

On a phone the middle of that picture collapses: there is one address space, so the router
is linked as a static library and called through its C ABI rather than proxied over a
socket. `router/src/ffi.rs` says why.

## The pieces

**Hermes Agent** is the thing you talk to on the board, and the only agent there. It holds
the conversation, keeps long-term memory, and does the coding itself. It used to delegate
that to a second harness; `docs/decisions/retiring-the-second-harness.md` records why it no
longer does.

**zeromem** is the memory provider. Its point is in the name: indexing and retrieval are
deterministic, so remembering something costs zero tokens. It keeps raw conversation turns
with provenance rather than LLM-written summaries, which means recall returns what was
actually said, not a paraphrase of it.

**wattrouter** is the core, and the one thing every deployment shares. It scores each prompt
for difficulty and routes it to the cheapest tier that can handle it. On the board it is a
local proxy speaking the OpenAI wire protocol in both directions, so anything that can talk
to OpenAI can sit in front of it. On a phone it is the same crate as a static library.

## Where it runs

Three deployments, one core. What differs is everything above it.

### The board

`deploy/` bootstraps it and `hermes/` configures it. Hermes holds the conversation, zeromem
holds the memory, and the router is a service on `127.0.0.1`. This is the deployment the
rest of this README describes.

### iOS — `ios/`

A Swift app with its own turn loop, linking the router as an xcframework. It does not use
Hermes: a phone has no shell, so the tools are written rather than shelled out to, and the
loop is `Agent.swift` rather than a harness.

What exists: the turn loop with atomic rounds, streaming, interruption and resumption; six
file and todo tools over a workspace boundary; calendar reading and writing over EventKit
behind a permission seam; the transcript; the routing panel. `ios/AGENTS.md` has the layout
and, more usefully, what can and cannot be verified without Xcode.

What is blocked: local inference, permanently until a physical device exists —
`docs/decisions/inference-needs-a-phone.md` records why the simulator gives no signal at all.

### Android — `android/`, next

Not built yet. `docs/decisions/what-android-allows.md` is written first, because Android
permits most of what iOS forbids and the constraints on each are not the ones you would
guess.

### What the two phones do not share

| | iOS | Android |
|---|---|---|
| Read and drive other apps | Not possible for a third party, at all | `AccessibilityService`: node tree, gestures, screenshots |
| What stops it | The API does not exist | Play policy, not the API. A sideloaded build is unaffected |
| Floating chat | No | Bubbles (sanctioned) or an overlay window (flexible) |
| Long turns in the background | Seconds, so the app warns and stops | A foreground service, for as long as it is useful |
| A shell | No | Yes, if the tools ship as native libraries — an app cannot `exec` its own data directory |
| Driving a browser | An embedded web view only | An embedded web view, or the person's own browser through accessibility |
| Content handed in | Share extension, App Intents, Siri and Shortcuts | Intents and the share sheet |

The routing core is identical on both. A second routing policy written in Kotlin would agree
with the first until the day it did not, which is the argument
`retiring-the-second-harness.md` already made about a second harness.

## Routing

| Tier | Model | Context | Used for |
|------|-------|---------|----------|
| `heavy` | `kimi-k3` | 1M | Architecture, multi-file reasoning, debugging |
| `code` | `kimi-k2.7-code` | 262K | Code-shaped work below the heavy threshold |
| `long` | `glm-5.2` | 1M | Anything over ~200K tokens of context |
| `mid` | `qwen3.6-35b-fast` | 131K | The working default: tool calls, structured output |
| `cheap` | `deepseek-v4-flash` | 1M | Lookups, short answers, chat |
| `aux` | `gemma-4-31b` | 262K | Background work: titles, summaries, compaction |

Two things make this fast enough to sit in a hot path. Most turns never reach the scorer — a
heuristic pass catches the obvious cases, and a follow-up turn reuses its session's tier
instead of re-scoring. When the scorer does run it embeds only the last user message truncated
to ~512 tokens, so routing costs the same whether the conversation is one turn or a hundred.

The router also picks along a second axis. NeuralWatt exposes `-fast` variants with thinking
disabled and `-flex` variants that are cheaper but held serially. Interactive traffic gets
`-fast`; only background and cron work gets `-flex`, because serialization would be felt
immediately in a live session.

Set `x-wattrouter-tier` on a request to override the decision entirely.

## Resource floor

The stack is built for generic aarch64 Linux. Memory is what binds, and the deciding factor is
whether zeromem runs its ONNX embedder or falls back to hashing.

| RAM | Embedder | Notes |
|-----|----------|-------|
| 8GB+ | ONNX bge-small-en-v1.5 | Recommended. Best recall quality. |
| 4GB | Hash fallback (`use_model: false`) | Skips the 130MB model. Lower recall, much lower RSS. |

The router and zeromem share one model cache directory, so the model is downloaded once rather
than once per process.

## Credentials

One: `NEURALWATT_API_KEY`. Supply it through the environment or a systemd `EnvironmentFile` —
never a tracked file. See `.env.example`.

## Getting started

### The board

```sh
just toolchain                        # are the required tools present
cargo build --release --manifest-path router/Cargo.toml
sudo NEURALWATT_API_KEY=nw-... deploy/bootstrap-pi.sh
deploy/install-zeromem.sh             # memory; compiles a Rust extension
NEURALWATT_API_KEY=nw-... scripts/verify-stack.sh
```

The router runs without a scoring head, taking the policy's unscored path. To fit
one:

```sh
uv run --with datasets python train/fetch_dataset.py
cargo run --release --manifest-path router/Cargo.toml --bin train-head \
  -- train/prompts.jsonl > ~/.hermes/memory/zeromem-models/head.json
```

The head carries thresholds calibrated against its own score distribution, so
they cannot drift apart from the weights that produced them.

### iOS

Needs Xcode and one simulator runtime; nothing else has to be installed by hand.

```sh
just ios-core                         # the routing core as an xcframework
just ios-test                         # the suite, on a simulator it creates if absent
just ios-project                      # regenerate the Xcode project from project.yml
```

The project and the xcframework are both build output and neither is checked in.
`ios/AGENTS.md` says what a pull request may claim to have verified on a machine without
Xcode, which is less than it looks.

## Credits

The routing approach follows [RouteLLM](https://github.com/lm-sys/RouteLLM) (Ong et al.,
LMSYS) — win-rate scoring against a strong/weak model pair, with thresholds calibrated to a
target traffic split. The implementation here is independent: it embeds locally instead of
calling a hosted embedding API, and it thresholds into several tiers rather than two.

Memory is [zeromem](https://github.com/ptaranat/zeromem), an implementation of Zero-Mem (Xiao
et al., arXiv:2607.29377).

## License

MIT.
