# Coding standard

This repository follows Doug Lea's [Java Coding
Standard](https://gee.cs.oswego.edu/dl/html/javaCodingStd.html). That document is written for
Java; this repository is Rust, Python, shell and YAML. This file records the adaptation, so a
reader can tell what is expected, what a tool will reject, and which rules were dropped on
purpose rather than forgotten.

The standard's premise is that a reader should be able to use a piece of code correctly
without reading its body. Most of what follows serves that.

## Documentation

### File headers

Every source file opens with a comment block giving the file's name and purpose, a history
table, and — where the file holds more than one significant item — a list of its contents.
Checked by `scripts/lint/file-headers.sh`.

```rust
//! embed.rs — prompt embedding for the routing head.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   Embedder      Trait shared by both backends.
//!   OnnxEmbedder  bge-small-en-v1.5 via ONNX. Default.
//!   HashEmbedder  Offline fallback for memory-constrained hosts.
```

### Public items

Every public item is documented: what it is for, what invariants hold, and how to use it.
Enforced by `#![warn(missing_docs)]`, denied in CI, and by ruff's pydocstyle rules for Python.

The standard specifies a formal shape for method documentation. Restated for this repository:

| Lea's form | Meaning | Here |
|---|---|---|
| `@param x WHERE (cond)` | Precondition | `# Arguments`, stating the condition |
| `@return (cond)` | Postcondition | `# Returns`, stating what holds of the result |
| `@exception E IF (cond)` | Failure condition | `# Errors` and `# Panics`, stating the trigger |
| `RELY (cond)` | Assumed execution context | `# Rely` |
| `ATOMIC` | Free of thread interference | `# Atomic` |
| `GENERATE T` | Creates new entities | Stated in `# Returns` |
| `PREV(obj)` | Pre-call state | Stated inline where needed |

`# Rely` is required on every `pub async fn`, and `# Atomic` on every method touching shared
state. Both are checked by `scripts/lint/doc-tags.sh`. They matter here because the router
holds three pieces of shared mutable state — the ONNX session, the decision cache, and the
upstream connection pool — and a caller cannot reason about a request path without knowing
which operations are safe to interleave.

```rust
/// Score a prompt's difficulty as the probability that the strong model wins.
///
/// # Arguments
/// * `prompt` — the last user message, WHERE `prompt` is non-empty and already
///   truncated to at most `MAX_ROUTING_TOKENS`.
///
/// # Returns
/// A win rate in `[0.0, 1.0]`. Higher means a harder prompt.
///
/// # Errors
/// Returns `EmbedError::Backend` IF the ONNX session fails to run.
///
/// # Rely
/// Called from the request path. Must not block the executor; the ONNX call is
/// dispatched to the blocking pool.
///
/// # Atomic
/// Serialized on the embedder mutex. Concurrent callers queue.
pub async fn score(&self, prompt: &str) -> Result<f32, EmbedError>
```

Python takes the same fields as docstring sections. `mypy --strict` covers what the types say,
so docstrings carry only what types cannot: the conditions.

### Local comments

Block comments explain an algorithm spanning several statements; line comments clarify what is
not obvious. Neither restates the code. The standard's instruction is to prefer making the
code clear over explaining unclear code, and that ordering holds here.

Comments carry the *why*. A comment explaining that a value is clamped is noise; a comment
explaining that it is clamped because the upstream rejects zero is not.

## Naming

The standard's intent maps onto each language's existing convention, so this is mostly a
matter of not fighting the idiom.

| Kind | Rust | Python |
|---|---|---|
| Types | `CapitalCase` | `CapitalCase` |
| Functions, methods | `snake_case` | `snake_case` |
| Constants | `SCREAMING_SNAKE` | `SCREAMING_SNAKE` |
| Modules | `snake_case` | `snake_case` |
| Factories | `new`, `new_from_x` | `from_x` classmethod |
| Converters | `to_x`, `into_x` | `to_x` |
| Accessors | `x()` | `x` property |
| Mutators | `set_x(v)` | `set_x(v)` |
| Errors | `XError` | `XError` |

Lea's `Ifc` interface suffix is dropped — Rust traits and Python protocols are already
distinguishable at the point of use.

## Structure

- One significant type per file. Where a trait and its implementations are meaningless apart,
  they may share a file; the header lists them.
- A function does one thing. Enforced by a cyclomatic-complexity gate, which is a proxy — it
  catches the egregious cases and cannot judge cohesion. Review does the rest.
- No public struct fields. State is reached through accessors, so invariants have somewhere to
  live.
- No `static mut`, and no mutable global state. `const` and immutable `static` only.
- Declare a binding at the point its initial value is known, and prefer a new binding to
  reassigning an existing one.
- Prefer defining a trait over an abstract base when another implementation is conceivable.

## Errors

- No assignment inside a condition.
- A fallible function returns `Result`; it does not signal failure through a sentinel.
- `#[must_use]` on anything whose result carries meaning. A deliberately discarded result is
  written `let _ = ...` with a comment saying why — the standard asks that ignored returns be
  documented, and this is where.
- Panics are for broken invariants, never for input. Anything reachable from a request returns
  an error instead.
- Every `# Errors` section names the condition that triggers it, not just the type.

## Concurrency

The standard asks that a public method either be synchronized or describe the context it
assumes. Rust has no `synchronized`, so the second half is the whole rule here: every public
async function documents its `# Rely`, and every method over shared state documents its
`# Atomic` guarantee.

Where locks are taken, the acquisition order is documented at the type holding them. The router
holds two — one in `cache.rs` and one in `embed.rs` — and there is no order between them,
because no path takes both. Each documents one acquisition per method, which is the whole of
what a caller needs.

That is a property of the code rather than a rule, so it is worth saying what the rule is: a
change introducing a path that takes both must state the order where it does, and a change that
needs a second order needs a better design instead.

## Not carried over

These are load-bearing in Java and meaningless here. Listed so a reader can tell a deliberate
omission from an oversight.

| Rule | Why not |
|---|---|
| `Cloneable`, `Serializable`, `readObject`/`writeObject` | No equivalent. `Clone` and serde derives are declarative and carry no comparable pitfalls. |
| `equals`/`hashCode` pairing | `PartialEq`/`Hash` are derived together; the compiler enforces coherence. |
| Assign `null` to unused references | No null, and no GC to hint. Ownership handles it. |
| Prefer `long` to `int`, `double` to `float` | Rust's integer types are explicit at the declaration; the ambiguity the rule addresses does not arise. |
| `Ifc` suffix on interfaces | Traits and protocols are already distinguishable at use. |
| `synchronized` methods and blocks | No such construct. Replaced by the `# Rely` and `# Atomic` requirements above. |
| Package `index.html` | `docs/` serves this. |

## Enforcement

The standard is CI's business, not review's, wherever a machine can decide.

| Rule | Gate |
|---|---|
| File headers | `scripts/lint/file-headers.sh` |
| Public items documented | `missing_docs` denied; ruff `D` |
| `# Rely` on `pub async fn`, `# Errors` on a public `Result` | `scripts/lint/doc-tags.sh` |
| Formatting | `cargo fmt --check`, `ruff format --check`, `shfmt`, `yamllint` |
| Lints | `cargo clippy -D warnings`, `ruff check`, `shellcheck` |
| Types | `mypy --strict` |
| One thing per function | Complexity gate |
| Tests | `cargo test`, `pytest`, behind a coverage ratchet |

Not mechanically checkable, and therefore review's job: whether a comment earns its place,
whether a function is cohesive rather than merely short, and whether a name says what the
thing is.

`# Panics` and `# Atomic` are in that list too, and the row above says so by naming only the two
tags a script decides. Whether a function touches shared state needs to know what a receiver
holds and what its callees reach; whether it can panic needs the same walk, since a panic hides
behind any callee. A gate that guessed at either would fail honest code or miss the cases that
matter, and a wrong gate is worse than a stated gap.

## Commits, pull requests, prose

Commit messages are imperative and explain why the change is being made; what it does is
already in the diff. The subject is one line. The body wraps at 80 columns and references its
issue.

No AI tooling is named in any commit message, pull request, code comment or document, and no
generated-by or co-authored-by trailer is added. The repository has one authorial voice.

Every pull request references an issue and changes at most 300 lines, excluding lockfiles and
vendored data. Work that runs over splits into a follow-up on the same branch, under a new
issue linked to the parent. Both are enforced; see `.github/workflows/pr-governance.yml`.

Prose documentation follows the same standard as code: say what is true, say why, and leave
out what the reader can see for themselves.
