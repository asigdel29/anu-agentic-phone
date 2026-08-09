# anu-agentic-stack

A personal agent stack. One routing core, in three places: a small aarch64 board, an iPhone
and an Android phone. The board runs [Hermes
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

What exists: the turn loop with atomic rounds, streaming, interruption and resumption;
eighteen tools — files and todos over a workspace boundary, calendar and reminders over
EventKit, contacts, location, Shortcuts, git, and memory across the same store the board
uses — each behind a permission seam; the transcript; the routing panel. `ios/AGENTS.md` has
the layout and, more usefully, what can and cannot be verified without Xcode.

What is blocked: local inference, permanently until a physical device exists —
`docs/decisions/inference-needs-a-phone.md` records why the simulator gives no signal at all.

### Android — `android/`

Two Gradle modules: `core/`, the routing core as a library over four JNI entry points, and
`app/`, a Compose chat over it. Same turn loop as iOS, arrived at in Kotlin rather than
shared — `Agent.kt` is `Agent.swift`'s four properties argued out again, because a second
implementation that agrees is worth more than a shared one that has to.

What exists: sign-in over the Android Keystore; a streaming turn that survives backgrounding
in a foreground service, which iOS cannot do; memory across JNI into the same store;
calendar, contacts and location behind a permission seam that is deliberately *not* iOS's —
Android's permissions are revocable, so nothing is cached and a permanent denial has its own
name; git over the same libgit2 the phone already links; and `ACTION_SEND` intake.

And the part only Android can do: **it reads and drives other apps.** An `AccessibilityService`
behind eight tools — read the screen, tap, type, scroll, navigate, open an app, wait for a
change, search what was not printed. The model never sees a coordinate: it holds a *handle*,
which is a recipe re-resolved against a freshly fetched tree before every action, and zero
matches or more than one is a refusal that says which.
`docs/decisions/how-the-agent-drives.md` argues all of it, including the two decisions that
only work together — the generation follows the tree's shape rather than its content, and
resolution refuses when the field that would tell two rows apart matches neither.

What is not verified: **any of it on a real phone.** Every claim above is a host suite or an
emulator. Driving *other* apps needs a device with apps on it, no real calendar, contact,
location fix or repository has been read, and the onboarding checklist has not been looked at
by anybody. `android/AGENTS.md` says which of the two Android test recipes may claim what.

What is missing: a screenshot tool, blocked because nothing in the stack carries an image
(#439); and a confirmation prompt, deferred because every rule for when it should fire is a
guess (#452).

### What the two phones do not share

Built unless the row says otherwise — and **built** here means the emulator or the simulator.
Nothing in this section has run on a physical phone; #188 is the checklist for the first time
one is attached.

| | iOS | Android |
|---|---|---|
| Read and drive other apps | Not possible for a third party, at all | **Built.** Eight tools over an `AccessibilityService`, by handle rather than coordinate |
| What stops it | The API does not exist | Play policy, not the API — so this build is sideloaded, and pays for it in restricted settings |
| Saying what it is doing over another app | — | **Built.** `TYPE_ACCESSIBILITY_OVERLAY`, which needs no permission at all |
| Long turns in the background | Seconds, so the app warns and stops | **Built.** A `specialUse` foreground service, uncapped |
| Summoned from anywhere | — | **Built.** The accessibility button; the assistant role was declined |
| A shell | No | *Predicted, not built.* Possible if the tools ship as native libraries — an app cannot `exec` its own data directory |
| Content handed in | Share extension, App Intents, Siri and Shortcuts | **Built.** `ACTION_SEND`, `singleTop` so a share reuses the one instance |

The routing core is identical on both. A second routing policy written in Kotlin would agree
with the first until the day it did not, which is the argument
`retiring-the-second-harness.md` already made about a second harness.

The tools are not identical, and the table above does not show it. Android registers sixteen
and iOS eighteen. Only iOS has `read_file`, `write_file`, `patch`, `search_files`, `todo`,
`clarify`, `add_event`, `add_reminder`, `read_reminders` and `run_shortcut`; only Android has
the eight screen tools. And the same three git operations are `git_status`, `git_add` and
`git_commit` on one phone and `read_repository`, `stage_paths` and `commit` on the other —
which is a divergence nobody decided, recorded here rather than quietly fixed.

## Routing

These six names are defaults in `router/src/tier.rs`, each overridable by
`WATTROUTER_MODEL_<TIER>`. Nothing in this repository establishes that they exist upstream or
that the context windows below are right — the table is the map the router was built to, and
#188 is what would turn it into a measurement.

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
whether zeromem runs its ONNX embedder or falls back to hashing. `use_model` below is zeromem's
own setting rather than one of this repository's, and neither figure has been measured on a
board — they are the sizes the model and the runtime are documented to want.

| RAM | Embedder | Notes |
|-----|----------|-------|
| 8GB+ | ONNX bge-small-en-v1.5 | Recommended. Best recall quality. |
| 4GB | Hash fallback (`use_model: false`) | Skips the 130MB model. Lower recall, much lower RSS. |

The router and zeromem share one model cache directory, so the model is downloaded once rather
than once per process.

## Configuration

One credential: `NEURALWATT_API_KEY`. Supply it through the environment or a systemd
`EnvironmentFile` — never a tracked file. `.env.example` carries the names and no values.

Everything else has a default that works, and these are the ones worth knowing about.

| Variable | Default | What it decides |
|---|---|---|
| `NEURALWATT_API_KEY` | — | The one credential. Required. |
| `WATTROUTER_ADDR` | `127.0.0.1:8080` | Where the router listens. Loopback on purpose; see below. |
| `WATTROUTER_UPSTREAM` | `https://api.neuralwatt.com/v1` | Where requests go. |
| `WATTROUTER_MODEL_<TIER>` | the six names in the table above | Overrides one tier's model. |
| `WATTROUTER_BACKEND_<TIER>` | `remote` | `local` or `remote`, per tier. An unrecognised value refuses to start rather than quietly sending work off the machine. |
| `WATTROUTER_EMBEDDER` | hashing | `hash` or `onnx`. Changing it means refitting the head — a head is only readable by the embedder that produced its training vectors, and it is checked at load. |
| `WATTROUTER_MODEL_CACHE` | `~/.hermes/memory/zeromem-models` | Shared with zeromem, so the model is fetched once. |
| `WATTROUTER_HEAD` | `<model cache>/head.json` | The scoring head. Absent is not an error; the policy has an unscored path. |
| `HERMES_HOME` | `~/.hermes` | Hermes state: config, plugins, memory, skills. |

### What it serves

| Route | |
|---|---|
| `POST /v1/chat/completions` | The one that matters. OpenAI-shaped, streaming both ways. |
| `GET /v1/models` | The tiers, as models. |
| `GET /healthz` | |
| `GET /metrics` | Prometheus. |

**None of them is authenticated**, which is why the default binds loopback. `SECURITY.md` says
what follows from that.

Set `x-wattrouter-tier` on a request to override the routing decision entirely.

### Cargo features, which change what you get

| Feature | Default | |
|---|---|---|
| `onnx` | **on** | The ONNX embedder. Off gives a router that only ever hashes, which is the right build for a memory-tight board. |
| `git` | off | libgit2, for the phones. A board has a shell and does not need it linked in. |
| `memory` | off | The bounded memory store. Only a phone needs one. |
| `android` | off | The JNI entry points. |

So `cargo build --release` gives you ONNX and **neither git nor memory** — while `just ios-core`
and CI build `--no-default-features --features git`. The same asymmetry applies to the tests:
`cargo test --all-targets` does not compile `git2`, `rusqlite` or the JNI layer at all, so a
change to any of them wants `--all-features` before it is called tested.

## What you need

Every floor below is pinned in a file; this is the one place they are all together.

| | |
|---|---|
| **Rust** | 1.95, edition 2024 |
| **Targets** | `aarch64-unknown-linux-gnu` for the board, `aarch64-apple-ios` for the phone. Those two, cross-built and gated in CI. |
| **Python** | 3.11, which is Hermes's floor. Only `train/` and the Hermes plugins need it. |
| **Xcode** | 26 and one simulator runtime, for `ios/`. |
| **Java** | 21, for `android/`. |
| **Gradle** | 9.7. No wrapper — a wrapper is a jar, and this repository does not track binaries it cannot review, so it is installed rather than checked in. |
| **Android SDK** | `compileSdk 37.1`, `targetSdk 35`, `minSdk 29` — so Android 10 and up. |
| **Android ABI** | **`arm64-v8a` only.** The APK carries no other, which is every phone since about 2017 and no emulator image that is not arm64. |

`just toolchain` reports which of these are present and exits non-zero if one is missing or too
old. It treats the Android and iOS toolchains as optional rather than failing over them: a check
that fails over a milestone nobody is working on is a check people learn to ignore.

## Getting started

### The board

Any aarch64 Linux with systemd. It is called the board because that is what it runs on here;
nothing depends on the hardware. `deploy/bootstrap-pi.sh` installs two services —
`wattrouter` and `hermes` — so this is a machine you are willing to have run things.

```sh
just toolchain                        # are the required tools present
cargo build --release --manifest-path router/Cargo.toml
sudo NEURALWATT_API_KEY=nw-... deploy/bootstrap-pi.sh
deploy/install-zeromem.sh             # memory; compiles a Rust extension
```

Then point Hermes at the router, which is the step that makes the diagram above true and is
otherwise the easiest thing in this repository to leave undone:

```sh
just install-hermes                   # this repository's plugins where Hermes finds them
just hermes-config                    # what pointing Hermes at the router would change
just hermes-config-apply              # do it. `just hermes-unconfig` puts it back
```

`hermes-config` changes nothing and prints the diff, because rewriting somebody's agent
configuration unasked is not a thing to do quietly. Both are reversible leaf by leaf.

To check it end to end you need a router answering. On the board the service is already up;
anywhere else, start one:

```sh
just up                               # detached, and waits until it answers
just status                           # is one serving, and which process
just verify                           # the stack, end to end
just down
```

`just verify` is the wrapper that passes the address from `WATTROUTER_ADDR`. Running
`scripts/verify-stack.sh` by hand verifies whatever the default happens to be, which is not
the same claim.

`just router` runs it in the foreground until Ctrl-C, which is what you want while changing it.

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

**The order matters and is not optional.** The Xcode project and the xcframework are both
generated and neither is checked in, so a fresh clone has neither:

```sh
just ios-project                      # generate the project from ios/project.yml — first, on a fresh clone
just ios-core                         # the routing core as an xcframework
just ios-test                         # the suite, on a simulator it creates if absent
```

`just ios-test` without `just ios-core` fails on a missing framework rather than on anything
about the change being tested. Re-run `ios-project` after editing `project.yml`; it also writes
the `Info.plist` files and the entitlements, which is why those are gitignored too.

### Android

Needs the Android SDK, the NDK and Gradle; `just toolchain` says which are absent rather
than failing inside a build. There is no Gradle wrapper — a wrapper is a jar, and this
repository does not track binaries it cannot review.

```sh
just android                          # the core, then the debug app
just android-test                     # the JVM suite: everything that touches nothing Android
just android-device-test              # the emulator suite: the only one that loads the .so
just android-release                  # the APK a person installs, signed from the environment
```

The two test recipes make different claims and `android/AGENTS.md` is emphatic about it: the
`.so` is built for `aarch64-linux-android` and will not load on the host at all, so a change
to `Core.kt` or `jni.rs` claiming only `just android-test` has claimed nothing.

Signing reads `WATTROUTER_KEYSTORE`, `WATTROUTER_KEYSTORE_PASSWORD`, `WATTROUTER_KEY_ALIAS`
and `WATTROUTER_KEY_PASSWORD`. Without them the release build assembles unsigned and says so,
and an unsigned APK cannot be installed. The keystore is yours and is never tracked.

Installed, the app asks for one thing that is not a normal permission: an accessibility
service, from Settings. On a sideloaded build that switch is greyed out until restricted
settings are allowed — the app's own checklist screen walks through it, because nothing else
on the phone explains why the switch does not work.

`ios/AGENTS.md` says what a pull request may claim to have verified on a machine without
Xcode, which is less than it looks.

## Security

This application reads and acts on other applications' screens, and what it reads reaches the
provider that answers the turn. [`SECURITY.md`](SECURITY.md) says what that means in practice —
the threat model, what leaves the device, what is protected and what is not — and how to report
a vulnerability privately.

Two things from it worth knowing before installing anything: **`FLAG_SECURE` windows are
readable through accessibility**, so a banking application is not hidden from this; and **the
router has no authentication**, which is why it binds loopback.

## Credits

The routing approach follows [RouteLLM](https://github.com/lm-sys/RouteLLM) (Ong et al.,
LMSYS) — win-rate scoring against a strong/weak model pair, with thresholds calibrated to a
target traffic split. The implementation here is independent: it embeds locally instead of
calling a hosted embedding API, and it thresholds into several tiers rather than two.

Memory is [zeromem](https://github.com/ptaranat/zeromem), an implementation of Zero-Mem (Xiao
et al., arXiv:2607.29377).

## License

MIT.
