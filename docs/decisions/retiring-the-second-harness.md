# The coding harness

This repository ran two agents. Hermes held the conversation and delegated coding to OpenCode
through a bundled skill. That is now one agent: Hermes does the coding too, and OpenCode is
retired.

This file records why, because the reasoning is worth more than the diff — and because two of
the arguments made along the way were wrong, and a decision record that only keeps the winning
arguments is a record nobody can audit.

## What OpenCode actually provided

Its tool loop, read out of the 1.18.13 binary:

```
bash  edit  write  read  patch  list  glob  grep  todowrite  webfetch  task  question  skill
```

Every one of those has a Hermes equivalent, name for name. Hermes additionally has seven remote
execution backends (Docker, SSH, Modal, Daytona, Singularity and two more), LSP diagnostics
wired into every write with baseline-diffing so only newly introduced errors surface,
checkpoints with `/undo` over a shared shadow-git store, in-repo pull request creation,
programmatic tool calling where the model writes Python that calls tools over a socket so
intermediate results never enter the context, browser automation, vision, session search, and a
durable async-delegation rail with crash recovery.

So the split was not a division of labour. It was the same work, twice, in two processes.

## The cost of keeping it

A second process, a second agent loop, and a second context window paying tokens over the same
repository. Two binaries on the machine at different versions — 1.18.13 and 1.18.4 were both
installed here, and the skill that drove them carried a section warning about which one `PATH`
would pick. Iterative work went through a pseudo-terminal, with the documented exit being to
send `\x03`.

And a second routing policy. `opencode/opencode.jsonc` pinned a model per agent, which the file
itself flagged as a problem:

> Routing decisions belong in one place, and pinning models per agent here would be a second,
> silently competing policy.

## Two arguments that were made and were wrong

Recorded because they were stated confidently, and because the conclusion survives without
them.

**"OpenCode cannot carry a per-request session id."** False. Verified in the binary: a provider
whose id does not begin with `opencode` takes the `else` branch and already sends
`X-Session-Id`, `x-session-affinity`, and `x-parent-session-id`. Routed through the router it
would have supplied session stickiness for free, with no plugin and no configuration.

**"A static session header would ratchet every request to the highest tier."** True of a static
one, but the premise was wrong — see above. The header is per-request.

The case for retirement rests on redundancy alone. It is enough, but it is one argument rather
than three.

## What is lost

**A second, independent harness.** Two agents with different implementations catch mistakes
that one does not. This is a real cost and the mitigation is weak: the binary stays installed
and can still be driven by hand.

**LSP navigation.** Hermes's LSP client handles `publishDiagnostics` and nothing else — it
advertises `documentSymbol` capability and never issues the request. OpenCode exposed
definition, references, hover and workspace-symbol. Closing that is tracked separately; the
groundwork is already there, since `client.py` has a generic request method and `manager.py`
already spawns a server per file.

**Formatter-on-write.** Accepted: this repository's CI already gates `cargo fmt --check` and
`ruff format --check`.

## What is not affected

The OpenCode configuration in a developer's own `~/.config/opencode/` is untouched. It goes
directly to the provider and always has, which is why nothing in this repository can be
bypassed by it — there is no longer any router-facing OpenCode configuration for it to bypass.
It remains available as a standalone tool.
