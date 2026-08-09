# Working in this repository

Read this first. It says where things are, what runs them, and what will reject a change.
It does not restate the rules — `docs/coding-standard.md` is the standard, and a second copy
of a rule is free to disagree with the first. Read that file before writing code, and read it
again before arguing with a lint.

`CLAUDE.md` beside this file is a symlink to it. One file, two names, no second copy.

A subtree with an `AGENTS.md` of its own holds what is true only there, and overrides this file
where they disagree. Four have one: `router/`, `ios/`, `android/`, `hermes/`.

## What this is

A personal agent stack for a small aarch64 board. `README.md` has the architecture and the tier
table; this file assumes you have read it.

| Path | Language | What it is |
|---|---|---|
| `router/` | Rust | `wattrouter`: scores a prompt and routes it to the cheapest tier that can answer. Also the C ABI the iOS app links. Has its own `AGENTS.md`. |
| `ios/` | Swift | The phone app and the routing core wrapped for it. Has its own `AGENTS.md`. |
| `hermes/` | Python, YAML | Configuration and two plugins for the agent, which is installed separately. Has its own `AGENTS.md`. |
| `train/` | Python | `fetch_dataset.py`, which builds the training set for the scoring head. |
| `deploy/` | Shell, systemd | Board bootstrap and the two service units. |
| `android/` | Kotlin, Gradle | The core as an Android library. `just android` builds it, core first. One module, no wrapper, and no app yet. |
| `scripts/` | Shell | Everything `just` calls, plus `scripts/guards/`, which CI calls. |
| `docs/` | Prose | The standard, and the decision records. |

## Running things

`just` is the entry point; `just --list` is current and this table is not, so prefer it.
`just toolchain` reports what is missing before a build fails deep inside one.

The recipes that matter: `just router` runs it in the foreground, `just up` and `just down`
start and stop it detached, `just verify` checks the stack end to end and needs a router
running first.

`just ios-core` and `just android-core` build the same crate for the two phones, and
`just android` packages the Android one into a library Gradle can hand to an app. All of it is
optional toolchain — `just toolchain` reports the NDK, the SDK and Gradle as absent rather than
failing, because a check that fails over a milestone nobody is working on is a check people
learn to ignore.

There is no Gradle wrapper. A wrapper is a jar, and this repository does not track binaries it
cannot review; the recipe says how to install Gradle instead, the way `scripts/test-ios.sh` does
for xcodegen.

Android has two test recipes and the difference matters. `just android-test` runs on the JVM and
covers everything that touches nothing Android. `just android-device-test` boots an emulator,
and it is the only one that can load the library — the `.so` is built for
`aarch64-linux-android` and will not load on the host at all.

## What will reject a change

Two workflows, and they check different things.

`.github/workflows/ci.yml` checks the code, and **most of it is conditional** — the surprising
property of this pipeline, and the first thing to know about it. A `detect` job probes for
`router/Cargo.toml`, `train/pyproject.toml` and `android/settings.gradle.kts` and sets a flag
per language; every later step carries an `if` on those flags.

`router/Cargo.toml` exists, so the Rust half runs: `cargo fmt --check`, `clippy -D warnings`,
`cargo test --all-targets`, a cross-build for `aarch64-unknown-linux-gnu`, a build for
`aarch64-apple-ios`, and performance gates.

`android/settings.gradle.kts` exists, so `gradle test` runs — **the JVM suite only**. The
instrumented one cannot run there: the system image is `arm64-v8a` and the runner is x86_64
with no KVM for it. So the only suite that can load the library is a local gate, which is why
`android/AGENTS.md` asks a pull request to name which of the two it ran.

**`train/pyproject.toml` does not exist, so the Python half runs over nothing.** `ruff format`,
`ruff`, `mypy --strict` and `pytest` are all configured and all skipped. `train/` currently
holds one file, so there is little to check — but a contributor writing Python here and
trusting CI to catch a type error will not be caught. Adding `train/pyproject.toml` turns all
four on at once.

`shfmt`, `yamllint`, `shellcheck` and `actionlint` are **not** conditional and run over the
whole repository, whatever you touched.

`.github/workflows/pr-governance.yml` checks the pull request itself, through
`scripts/guards/`. Severity lives in `scripts/guards/registry.json` rather than in the
workflow, so promoting or demoting a guard is a one-line edit reviewed like any other change.
`hard` fails the job; `advisory` reports and does not.

The guards are `issue-link` (a pull request references an issue), `pr-size` (a diff is at most
300 lines, excluding lockfiles and vendored data) and `slopgate` (added lines, commit messages
and the pull request text avoid the register of unreviewed prose).

Which of them blocks is **not repeated here**. `registry.json` carries each guard's mode and
its description, and a second copy in this file would turn a one-line promotion into a two-file
edit — with the failure mode that this document ends up asserting the opposite of what CI does.
Read the registry: it is nine lines per guard and it is the truth.

Run them before pushing:

```
just guards                    # against the default branch
just guards 153-agents-md      # when the pull request targets something else
```

A gate you can only exercise by opening a pull request is a gate you debug by opening pull
requests. Each guard is an ordinary script taking its inputs from the environment, so the recipe
is a convenience over them rather than a second path — `run-all.sh` is what CI calls too.

## Issues and pull requests

Open the issue first: the issue is where the problem is stated, the pull request only where it
is solved. Every pull request references one and changes at most 300 lines. Work running over
splits into a follow-up on the same branch under a new issue linked to the parent — the
`pr-size` failure message spells out the steps.

Branch names are `<issue-number>-<short-description>`, which is what `gh issue develop`
produces and what `issue-link` recognises.

Commit subjects are imperative and one line. The body says why, wraps at 80 columns, and
references the issue. No tooling is named in a commit, a pull request, a comment or a document,
and no attribution trailer is added. The repository has one authorial voice, and `slopgate`
now checks for the trailer.

## Finding out why something is the way it is

1. **`docs/decisions/INDEX.md`.** Start here. It routes a question — why is a round committed
   atomically, why does `patch` refuse an ambiguous match — to the pull request that argued it,
   and holds the records for decisions with no diff to live in.
2. **Merged pull request bodies.** Where the reasoning actually is, which is why the index
   points at them rather than restating them. `gh pr view <n>`, or
   `gh pr list --state merged --search <term>` when the index has no row for your question.
3. **File headers.** Every file opens with its purpose and a history line. `head -20` on a file
   is usually faster than reading it.
4. **`docs/coding-standard.md`.** What is expected of the code, and which gate decides it.

## Before you claim something works

State where it ran. This repository is developed on more than one machine and they do not have
the same tools: `ios/` needs Xcode, and the machine most of this was written on has only the
Command Line Tools. A change that compiled in CI and a change that ran on a simulator are
different claims, and the pull request should say which one it is making.
