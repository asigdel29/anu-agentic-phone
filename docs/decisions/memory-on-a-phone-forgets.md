# Memory on a phone forgets

*What bounds the memory store on a phone, and why it is not a fork.*

## The problem

`zeromem` loads every turn at open. `ZeroMem::open` calls `build()`, `build()` calls
`Store::load_turns`, and that is `SELECT id, session_id, session_turn, speaker, text, ts FROM
turns ORDER BY id` with no limit. Each turn is then indexed: its text, a vector, a BM25
document, an entry in the entity graph and one in the temporal hierarchy. All of it is resident
before the first query.

That is O(all history) at launch, which a board tolerates and a phone does not.

Reading the database directly and skipping `build()` is not an escape. The retrieval path takes
those in-memory slices — `graph_view::retrieve` and `hier_view::retrieve` both borrow `&[Turn]`
and `&[Vec<f32>]` — so there is no query that does not need them. Avoiding `build()` means
reimplementing the pipeline, which is about a thousand lines of somebody else's work.

## The decision

**Bound it from outside, using only public SQL.**

Before `ZeroMem::open`, move turns past a horizon out of `turns` and into an archive table in
the same file. zeromem then sees a bounded table and behaves as it always has. The history stays
on disk. Nothing upstream changes, and there is nothing to keep in step with a moving upstream.

At 256 floats a turn, a horizon of two thousand turns is about two megabytes of vectors.

### The two options this was chosen over

**Fork and pin the fork.** Fixable in the sense that the fix is obvious, and it creates a fork
to maintain — for a dependency that is new and single-author, which is exactly the kind that
moves.

**Vendor a pinned copy under `router/vendor/`.** The diff becomes visible in review, which is
the argument for it, and the maintenance is the same as a fork with the version control taken
away.

Both make this repository responsible for somebody else's thousand lines. The horizon does not.

### And a better property

"Memory on a phone forgets, and the horizon is a number you can see" is a property somebody
chose. Silently unbounded is a property nobody chose, which is the difference that matters when
the phone is jetsammed a year from now and the question is why.

## What was unknown when this was argued, and is not now

#226 could not say whether `zeromem` crosses to iOS at all. It does.

Both slices build from the pinned commit `32ac538` with `--no-default-features` — which drops
`fastembed` and the ONNX runtime behind it — in under twenty seconds each. `rusqlite`'s bundled
SQLite compiles for `aarch64-apple-ios` and `aarch64-apple-ios-sim` without help. The crate is
the workspace's default member and separable from `zeromem-py`, so the PyO3 half never enters
an iOS build.

## Two consequences the app is built around

**Phone memory and board memory can never share a file.** `Store::init` clears the embeddings
table when the embedder tag changes. The board runs bge-small through ONNX; the phone cannot,
and hashes instead. Pointing both at one database would empty it on alternate opens, so they
are permanently separate stores. That is a fact to design around rather than one to discover.

**The SQLite `-shm` file must never be opened across an App Group container.** That constrains
where a memory database may live now that the share extension exists — #280 puts one text file
per item in that container and opens no database in it, deliberately.

File protection needs setting deliberately, as #111 did for the Keychain. A memory store is the
most sensitive thing this app writes.
