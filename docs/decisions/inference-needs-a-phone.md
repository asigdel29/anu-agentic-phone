# Local inference needs a phone

The tier table in `README.md` has a local half. Nothing in this repository has ever run it,
and nothing in this repository can: the simulator does not get far enough to produce a signal
of any kind about local inference.

This is a standing record rather than a pull request body because it is a fact about the
machines this is developed on, and no diff records it. What is still work moved to #188.

## What was measured

A scratch package (`mlx-swift` 0.31.6, one `MLXArray([1,2,3]) + MLXArray([10,20,30])`, no
model) builds and links cleanly for `arm64-apple-ios-simulator` against the iPhoneOS 26.5
SDK. It aborts at runtime on the iPhone 17 simulator:

```
exception:   EXC_CRASH (SIGABRT), Abort trap: 6
  libc++.1.dylib   std::__1::__libcpp_verbose_abort(char const*, ...)
  ProbeTests       mlx::core::metal::Device::Device()
  ProbeTests       mlx::core::metal::device(mlx::core::Device)
  ProbeTests       mlx::core::metal::MetalAllocator::MetalAllocator()
  ProbeTests       mlx::core::metal::allocator()
```

The frames are the whole argument. It dies constructing the Metal device, before any array
exists and before any weights are read, below the level at which a model choice can
influence anything. A small build fails identically to a large one, because neither reaches
allocation.

## What that settles

**No local number in this repository is measured.** Not tokens per second, not the thermal
curve, not the jetsam ceiling, and not "does it run". Any of them appearing in a document
here is a guess until #188 is done.

**The tier map is unvalidated in its local half.** Whether the largest build stays resident
across a backgrounding decides the ladder, and that cannot be learned here.

## What it does not touch

Everything above inference. The decision path, the turn loop, tool dispatch, the transcript
fold, the file layer and the workspace boundary are all exercised on a simulator today and
none of them need Metal. That was the useful half of the finding, and it is why the tree has
223 passing tests despite the local tier being unreachable.

## The sequencing it produced, which was the point

The choice at the time was between stopping until hardware arrived and building everything
above the blocked layer first. The second was taken, and it forced `Inference` to be a
protocol rather than a class with a network inside it. Three conformances exist:
`ScriptedInference` for tests and previews, `NeuralWattInference` for the provider, and a
local one that does not. `ChainWalk` walks `Step`s that already distinguish a local backend
from a remote one.

So the work a phone unblocks is a third conformance behind a seam that is already load
bearing, rather than a rewrite. A backend seam invented under pressure from a mock is a seam
that was tested against two implementations before it shipped, which is one more than most
get.

## What survived the harness that produced it

Everything above was measured on an iOS simulator against `mlx-swift`, and that tree is retired:
there is no `ios/` directory, no `Inference` protocol and no `ScriptedInference`. So the paragraphs
above describe machines this project no longer builds for, and this section is what is true of the
one it does.

**The sequencing argument survived, and it is the reason any of this is cheap.** The seam it forced
is still a seam, one language along: `ChainWalk` walks `Step`s that distinguish a local backend from
a remote one, `Backend::Local` is a type in `router/src/backend.rs`, and `router/src/config.rs`
parses `local` from the environment. A `phone()` fixture in `chain.rs` asserts that a code-tier
chain never leaves the device. All of that is chain *construction* and all of it works.

**What does not exist is the executor, and it is one line.** `ChainWalk.kt`:

```kotlin
if (step.backend != Backend.REMOTE) {
    last = InferenceError.Unavailable(step.model, "${step.backend} runs nothing here")
    continue
}
```

A non-remote step is counted as an attempt and skipped, deliberately, so the exhausted message stays
honest about how many models were considered. `backend.rs` still calls `Local` "an MLX build in the
app's own address space", which is the sentence above's tree rather than this one's.

## The runtime that is already here, and is not this

Worth stating because the crate does link an ONNX runtime and it would be reasonable to assume that
is a start.

`head.rs` scores a prompt's difficulty with a dot product and a sigmoid over a few kilobytes of
JSON, and calls that "the entire model". `embed.rs`'s ONNX path is bge-small-en-v1.5 through
fastembed: a 384-dimension sentence embedder for routing and memory retrieval, about 130 MB
resident, and `scripts/build-android-core.sh` compiles it out of the phone build with
`--no-default-features`. The board embeds and the phone hashes.

There is no tokenizer, no KV cache, no decoding loop and no generative weights anywhere in
`router/src/`. Generation on the phone is not a smaller version of what is here; it is a different
thing that shares a crate.

## What measuring it would cost now

Named rather than estimated, because the last confident number in this file was wrong.

The probe is the same shape and a different platform: the smallest possible case, on hardware,
before any model choice. What is unknown is not tokens per second but whether the runtime
initialises at all under the app's own address space, which is exactly what the simulator answered
no to last time and for a reason specific to that simulator.

It needs a phone, which makes it #510's checklist rather than a thing to plan around, and it needs
a candidate runtime chosen for Android rather than inherited from the Swift attempt.

## The hedge that was wrong

The plan listed simulator incompatibility as a risk to find out about early and rated the
row "doubtful". It is not doubtful and it never was; it is the first line of the smallest
possible case. Recorded because the plan's instinct, probing the risky layer on day one,
was right, and only its confidence was wrong. The probe cost an afternoon and moved two
phases of work ahead of one that could not start.
