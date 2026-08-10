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

## The hedge that was wrong

The plan listed simulator incompatibility as a risk to find out about early and rated the
row "doubtful". It is not doubtful and it never was; it is the first line of the smallest
possible case. Recorded because the plan's instinct, probing the risky layer on day one,
was right, and only its confidence was wrong. The probe cost an afternoon and moved two
phases of work ahead of one that could not start.
