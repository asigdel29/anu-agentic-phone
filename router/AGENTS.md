# Working in `router/`

The routing core: it scores a prompt and picks the cheapest tier that can answer, and it is
also the C ABI the phone links against. Read the root `AGENTS.md` first; this holds what is
true only here.

## The modules

`lib.rs` lists them, and every file opens with its own purpose. In rough request order:

| File | What it does |
|---|---|
| `config.rs` | Configuration read from the environment. |
| `classify.rs` | Reading routing signals off a request. |
| `embed.rs` | Turning a prompt into a vector. |
| `head.rs` | Scoring a prompt's difficulty. |
| `policy.rs` | Choosing a tier. |
| `tier.rs`, `chain.rs`, `backend.rs` | The tiers, the models each may use in order, and where one runs. |
| `cache.rs` | Remembering routing decisions. |
| `upstream.rs` | Forwarding a request to the provider. |
| `metrics.rs` | Counting what the router did. |
| `ffi.rs` | The C ABI the iOS app calls the core through. |
| `ffi_answer.rs` | The envelope an allocating ABI call answers with, shared by the two below. |
| `git.rs` | git operations without a subprocess, behind the `git` feature. |
| `ffi_git.rs` | Those operations across the ABI, behind the same feature. |
| `memory.rs` | Bounding what a store loads at open, behind the `memory` feature. |
| `ffi_memory.rs` | A memory store across the ABI, behind the same feature. |
| `jni.rs` | The same core reached from Kotlin, behind the `android` feature. |
| `jni_memory.rs` | The memory store from Kotlin, behind `android` and `memory`. |
| `testenv.rs` | The environment, as the crate's tests are allowed to touch it. |

## Building and testing

Every command needs the manifest path, because the workspace root is the repository and the
crate is not at it:

```
cargo build   --manifest-path router/Cargo.toml
cargo test    --manifest-path router/Cargo.toml --all-targets
cargo clippy  --manifest-path router/Cargo.toml --all-targets -- -D warnings
cargo fmt     --manifest-path router/Cargo.toml --check
```

`--all-targets` is not optional in CI, so a change compiling under `cargo test` and failing
under `--all-targets` fails on the push rather than here. Benches live in `benches/` and back
the performance gates.

## What the lints demand

`lib.rs` sets `#![warn(missing_docs)]` and `#![warn(clippy::pedantic)]`, and CI runs clippy
with `-D warnings`, so both are errors by the time they matter. Every public item needs
documentation, and `docs/coding-standard.md` states the shape: `# Rely` on every `pub async
fn`, `# Atomic` on every method over shared state, and an `# Errors` section naming the
condition rather than only the type.

`clippy.toml` extends `doc-valid-idents` with the product names that appear in prose. Add a
name there rather than backticking it — a product is not a code item.

## Two things that catch people

**The environment is process-global and the lib tests share a process.** A test that sets a
variable races every test that reads one. Use `testenv::with_env`, which holds a crate-wide
lock; do not add a second lock inside a `mod tests`, which is the bug that put `testenv.rs`
here.

**The header and the module map have to agree with Swift.** `include/wattrouter.h` is the ABI
and `include/module.modulemap` names the module `WattRouterFFI`, which must match the binary
target in `ios/Package.swift`. Changing one without the other breaks the import with a message
naming neither.

Kotlin has the same problem and a different check. `jni.rs` exports symbols by name, so a
rename is not a compile error on either side — it is an `UnsatisfiedLinkError` on a device. A
test in `jni.rs` reads `android/src/main/kotlin/com/getlora/wattrouter/Core.kt` by path and
holds the two in step, which also makes that path load-bearing: move the Kotlin and edit
`jni.rs` in the same commit.

**Locks.** `cache.rs:91` and `embed.rs:335` hold one each. They are independent, no path takes
both, and each documents one acquisition per method rather than an order across two. Add a path
that takes both and you owe it an order, stated where it does — that is what the standard asks
for, and there is nothing to state until then.
