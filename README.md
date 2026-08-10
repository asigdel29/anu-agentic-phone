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

## Installing it

**Not from the Play Store.** Play policy forbids an accessibility service used for general
automation, so this is sideloaded and is not trying to be otherwise. You are trusting a build
you made or a Release you chose to install, which is the right way round for something with
these capabilities.

You need an **arm64 phone running Android 10 or newer**. The APK carries one ABI.

Then, in this order — the app's own checklist screen walks you through it, and the order
matters:

1. **Sign in.** The key goes to the Android Keystore; nothing else is stored.
2. **Allow restricted settings** — Settings › Apps › WattRouter › ⋮. Sideloaded builds need
   this before the next step is even possible.
3. **Turn on the accessibility service** — Settings › Accessibility › WattRouter.
4. **Allow notifications**, so a turn that outlives the screen is visible and stoppable.
5. Calendar, contacts and location are optional, and the checklist says so.

## Building it

```sh
just toolchain        # what is missing, before a build fails deep inside one
just android          # the Rust core, then the debug app
just android-test     # the JVM suite
just android-device-test   # the emulator suite — the only one that loads the .so
```

`just --list` is current where this table is not. There is no Gradle wrapper: a wrapper is a
jar, and this repository does not track binaries it cannot review — `just android` says how to
install Gradle instead.

For a release build you need your own signing keystore, which is never tracked:

```sh
export WATTROUTER_KEYSTORE=~/.android/wattrouter-release.jks
export WATTROUTER_KEYSTORE_PASSWORD=... WATTROUTER_KEY_ALIAS=... WATTROUTER_KEY_PASSWORD=...
just android-release
```

Verify the result against the artefact rather than the log — `apksigner verify --print-certs`
on the APK. The recipe reports the shell's environment and Gradle reads its own
([#514](../../issues/514)).

### Running the server

```sh
docker build -t wattrouter .
docker run -p 8080:8080 \
  -e PORT=8080 \
  -e NEURALWATT_API_KEY=... \
  -e WATTROUTER_TOKENS=phone:$(openssl rand -base64 32) \
  wattrouter
```

`WATTROUTER_TOKENS` is `label:token` pairs. **Absent means nobody** — every request to `/v1` is
refused, which is noisy and safe; the alternative is an unmetered proxy to a paid provider on
the internet. `/healthz` is the one endpoint left open, because a platform health check arrives
with no credential.

`railway.toml` deploys the same image. Secrets go in the dashboard, never in the repository.

### Testing without a provider

Every tool runs only because a model decided to call it, so the interesting part of this
application would otherwise need a paid third party to exercise. It does not:

```sh
just stub                                        # answers turns from a script
WATTROUTER_UPSTREAM=http://10.0.2.2:8099/v1 just android
```

The stub speaks the provider's wire format and logs every request the app made, which is how
the next script gets written. Only a debug build can reach it — a release build has no
cleartext exemption at all.

## Configuration

| Variable | What |
|---|---|
| `NEURALWATT_API_KEY` | The one provider credential. Server-side only. |
| `WATTROUTER_TOKENS` | `label:token` pairs. Who may call the server. |
| `WATTROUTER_ADDR` | Where to bind. Loopback by default. |
| `PORT` | What a platform assigns; becomes `0.0.0.0:PORT`. |
| `WATTROUTER_UPSTREAM` | Where requests go. Also the Android build-time endpoint. |
| `WATTROUTER_EMBEDDER` | `hash` or `onnx`. Scoring needs `onnx`. |
| `WATTROUTER_HEAD` | Weights for the scoring head. |
| `WATTROUTER_MODEL_<TIER>` | Override a tier's model. |

[`.env.example`](.env.example) documents the names and carries no values.

### Cargo features

`onnx` is on by default; `git`, `memory` and `android` are not. So `cargo build --release`
gives you the embedder and **no git and no memory** — the phone build turns those on, and the
container turns ONNX off, because the phone routes and the server does not score.

## Repository

| Path | What |
|---|---|
| `android/` | Two modules: `core/`, the routing core as a library, and `app/`, the assistant over it. |
| `router/` | The Rust core: scoring, routing, the JNI layer, and the server. |
| `hermes/` | Configuration for the same router from a terminal — the reference for what the phone has to match. |
| `scripts/` | Everything `just` calls, plus `scripts/guards/` for pull requests. |
| `docs/` | The coding standard, and the decision records. |
| `train/` | Builds the training set for the scoring head. |

Each subtree with its own `AGENTS.md` holds what is true only there.

## Security

It can read a banking application's balance. `FLAG_SECURE` stops screen *capture* and leaves
the accessibility tree readable, because a screen reader has to work in a banking application —
[#472](../../issues/472) has the measurement, and two decision records that claimed otherwise
were corrected.

Anything on another app's screen becomes model input, which makes screen-sourced prompt
injection the headline threat. What stands against it, what does not, and what leaves the
device are all in [`SECURITY.md`](SECURITY.md). Report a vulnerability through a private
advisory, not an issue.

## Contributing

Every pull request references an issue and changes at most 300 lines, and both are enforced.
[`CONTRIBUTING.md`](CONTRIBUTING.md) has the rules that would otherwise fail a first attempt;
[`AGENTS.md`](AGENTS.md) is the working guide and [`docs/coding-standard.md`](docs/coding-standard.md)
is what the lints are asking for.

`docs/decisions/INDEX.md` routes a question — why is a round committed atomically, why does the
agent refuse an ambiguous match — to the pull request that argued it.

One rule worth knowing before you write a claim down: **state where it ran.** A change that
compiled in CI, one that passed the JVM suite, one that ran on an emulator and one that was
watched on a phone are four different claims.

## Credits

[NeuralWatt](https://neuralwatt.com) for inference. [zeromem](https://github.com/ptaranat/zeromem)
for memory that costs no tokens. [Hermes Agent](https://github.com/NousResearch/hermes-agent) as
the terminal harness this is measured against.

`v0.1.0-multiplatform` is tagged at the last commit containing the iOS application and the
board deployment, both removed in [#545](../../pull/545) when the project became one thing.

## License

MIT. See [LICENSE](LICENSE).
