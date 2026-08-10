# anu-agentic-phone

[![CI](https://github.com/asigdel29/anu-agentic-phone/actions/workflows/ci.yml/badge.svg)](https://github.com/asigdel29/anu-agentic-phone/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**An Android assistant that uses your phone the way you would.** You ask for something; it
reads whatever is on screen, taps, types and scrolls its way through the apps you already
have, and tells you what happened. It is not an app that integrates with five services — it
drives the ones on your phone.

It does that through an accessibility service, which means it can read the contents of other
applications. That is the whole of what it is for and the whole of why
[`SECURITY.md`](SECURITY.md) is worth reading before you install it.

## What it can do today

Sixteen tools, all of them registered in the app and none of them aspirational.

| | |
|---|---|
| **The screen** | `read_screen`, `tap`, `type_text`, `scroll`, `navigate`, `open_app`, `wait_for_change`, `find_on_screen` |
| **Your things** | `read_calendar`, `find_contact`, `where_am_i` |
| **Code** | `read_repository`, `stage_paths`, `commit` |
| **Memory** | `remember`, `recall` |
| **Anything else** | tools from an [MCP](https://modelcontextprotocol.io) server you connect |

The model never receives a coordinate. It gets a *handle*, which is re-resolved against a
freshly read screen before every action; zero matches or more than one is a refusal that says
which. A turn cannot act more than 25 times. Some screens cannot be acted on at all — a locked
phone, the permission screens, the accessibility settings, and the assistant's own. A password
field cannot be typed into.

While it is working, a banner sits over whatever it is driving, naming what it is doing, with
a stop button one tap away. A bubble floats over every app when it is not working, so you can
reach it without leaving what you are doing, and the assist gesture — long-press home — can be
pointed at it too.

## How a request flows

```
  phone                          your server                    provider
  ─────                          ───────────                    ────────
  scores the prompt  ─────────►  holds the API key  ─────────►  answers
  picks a tier                   authenticates you
  runs the tools                 (Railway)                      NeuralWatt
```

**Routing happens on the phone.** A Rust core, linked into the app through JNI, scores the
prompt for difficulty and picks the cheapest of six tiers that can answer. It is instant and
works offline, and it is why the phone sends a *tier* rather than a model name.

**The server holds the key.** An API key cannot ship inside an APK that other people install,
so the phone talks to a small server that holds it and forwards. That server is this same Rust
binary in a container — it authenticates the caller, resolves the tier to a model, and proxies.

**The provider answers.** [NeuralWatt](https://neuralwatt.com). Which model serves which tier
is the table below, and every one of the six can be overridden.

## Where this is up to

One author, MIT, public. **It is being built to be installed by people other than its author,
and it is not there yet.**

**Nothing in this repository has run on a physical phone.** That sentence is the most important
one on this page. What an emulator has settled, exactly:

- the release build loads its native library with R8 and resource shrinking on
- a turn routes, reaches the provider, and surfaces a structured refusal without crashing
- the tool loop drives another application — `open_app` then `read_screen`, with Clock genuinely
  coming to the front
- the overlay reaches the display, and leaves when a turn starts
- the readiness checklist recomputes rather than caching

What it cannot settle is a real screen, a real calendar, a real contact, and a person deciding
whether they are comfortable with any of it. [#510](../../issues/510) is the checklist for the
first time a phone is attached.

**Planned and unbuilt:** a model running on the device itself, vision ([#439](../../issues/439)),
voice, scheduled background tasks, a terminal, and three autonomy modes — plan, auto, and ask
before every action ([#452](../../issues/452)). None of those exist; they are named here so the
list above can be read as complete.

## Routing

These six names are defaults in `router/src/tier.rs`, each overridable by
`WATTROUTER_MODEL_<TIER>`. All six were checked against the provider's live catalogue during
the emulator run in [#510](../../issues/510) and every one exists. The context windows below
are still the map the router was built to rather than a measurement.

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
| `onnx` | **on** | The ONNX embedder. Off gives a router that only ever hashes, which is the right build for the container, because the phone scores and the server does not. |
| `git` | off | libgit2, for the phone. A machine with a shell does not need it linked in. |
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
| **Targets** | `aarch64-linux-android` for the phone, `aarch64-unknown-linux-gnu` cross-built and gated in CI. |
| **Python** | 3.11, which is Hermes's floor. Only `train/` and the Hermes plugins need it. |
| **Java** | 21, for `android/`. |
| **Gradle** | 9.7. No wrapper — a wrapper is a jar, and this repository does not track binaries it cannot review, so it is installed rather than checked in. |
| **Android SDK** | `compileSdk 37.1`, `targetSdk 35`, `minSdk 29` — so Android 10 and up. |
| **Android ABI** | **`arm64-v8a` only.** The APK carries no other, which is every phone since about 2017 and no emulator image that is not arm64. |

`just toolchain` reports which of these are present and exits non-zero if one is missing or too
old. It treats the Android toolchain as optional rather than failing over it: a check that fails
over a milestone nobody is working on is a check people learn to ignore.

## Getting started

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

`android/AGENTS.md` says which of the two test recipes may claim what, and the difference
matters: only the emulator suite can load the native library at all.

## Security

[`SECURITY.md`](SECURITY.md) has the threat model, what leaves the device, and how to report a
vulnerability privately. Two things from it worth knowing before installing anything:
**`FLAG_SECURE` windows are readable through accessibility**, so a banking application is not
hidden from this; and **the router has no authentication**, which is why it binds loopback.

## Credits

The routing approach follows [RouteLLM](https://github.com/lm-sys/RouteLLM) (Ong et al.,
LMSYS) — win-rate scoring against a strong/weak model pair, with thresholds calibrated to a
target traffic split. The implementation here is independent: it embeds locally instead of
calling a hosted embedding API, and it thresholds into several tiers rather than two.

Memory is [zeromem](https://github.com/ptaranat/zeromem), an implementation of Zero-Mem (Xiao
et al., arXiv:2607.29377).

## License

MIT — [`LICENSE`](LICENSE).

The applications statically link a great deal that is not MIT-licensed by us, and
[`THIRD-PARTY.md`](THIRD-PARTY.md) is the notice that has to travel with a binary. It is
generated from the dependency graph by `scripts/notices.sh`; the part worth reading is the
preamble, which covers the three things the metadata gets wrong — libgit2 is GPL-2.0 with a
linking exception, SQLite is public domain, and one transitive crate is MPL-2.0.
