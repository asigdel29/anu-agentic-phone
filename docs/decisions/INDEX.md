# Decisions

Why the code is the way it is.

Most of the reasoning here lives in the pull request that made the change, and that is the right
place for it: it is written while the alternatives are still fresh, and it is reviewed alongside
the diff it justifies. What was missing is a way in. Thirty-nine merged pull requests carry
arguments better than anything else in `docs/`, and every one of them was reachable only by
already knowing the number.

So this routes a question to the argument that answers it.

## Standing records

A decision earns a file here when it is about the repository rather than about a change —
nothing in a diff records it, so no pull request body can either.

| Record | The question it answers |
|---|---|
| [retiring-the-second-harness.md](retiring-the-second-harness.md) | Why is there one agent rather than two? |
| [inference-needs-a-phone.md](inference-needs-a-phone.md) | Why is the local tier unmeasured, and why was it built around rather than waited on? |
| [git-without-a-subprocess.md](git-without-a-subprocess.md) | What does a phone give up by using a library instead of `git`, and why is libgit2 a Rust dependency? |
| [what-android-allows.md](what-android-allows.md) | What can an Android agent do that an iOS one cannot, and what stops each of those things? |
| [memory-on-a-phone-forgets.md](memory-on-a-phone-forgets.md) | What bounds the memory store on a phone, and why is it not a fork? |
| [what-computer-use-means.md](what-computer-use-means.md) | Which of three products is "the agent takes control of a computer", and what are the other two for? |

## Decisions argued in a pull request

Curated, not generated. A list of all thirty-nine would be a list; what makes this an index is
that somebody chose which questions get asked. `gh pr view <n>` reads one.

### The turn, and the tools it runs

| Question | Argued in |
|---|---|
| Why is a round committed atomically, and why do tools run in order rather than at once? | [#149](https://github.com/asigdel29/anu-agentic-stack/pull/149) |
| Why do the tools have a workspace, and where does it come from on a phone? | [#121](https://github.com/asigdel29/anu-agentic-stack/pull/121) |
| Why does a write go through a temporary and a rename? | [#125](https://github.com/asigdel29/anu-agentic-stack/pull/125) |
| Why does `patch` refuse an ambiguous match instead of picking one? | [#133](https://github.com/asigdel29/anu-agentic-stack/pull/133) |
| Why does a tool call carry an id, and a result answer it? | [#147](https://github.com/asigdel29/anu-agentic-stack/pull/147) |

### The router

| Question | Argued in |
|---|---|
| Why is the embedding backend chosen at startup rather than compiled in? | [#96](https://github.com/asigdel29/anu-agentic-stack/pull/96) |
| Why is there no lock acquisition order? | [#163](https://github.com/asigdel29/anu-agentic-stack/pull/163) |
| Why does a chain walk try each model rather than one? | [#107](https://github.com/asigdel29/anu-agentic-stack/pull/107) |
| Why does the credential start the core in one particular order? | [#111](https://github.com/asigdel29/anu-agentic-stack/pull/111) |

### The gates

| Question | Argued in |
|---|---|
| Why is `slopgate` advisory rather than hard? | [#152](https://github.com/asigdel29/anu-agentic-stack/pull/152) |
| Why do the guards share a library, and why are pathspecs anchored? | [#156](https://github.com/asigdel29/anu-agentic-stack/pull/156) |
| Why is `CLAUDE.md` a symlink to `AGENTS.md`? | [#159](https://github.com/asigdel29/anu-agentic-stack/pull/159) |
| Why does `doc-tags` check two tags and not the four the standard lists? | [#161](https://github.com/asigdel29/anu-agentic-stack/pull/161) |

## When a question is not here

`gh pr list --state merged --search <term>` searches titles and bodies. The titles are written
to be searched — they say what the change does, not which files it touches.

## Adding to this

Add a row when a decision turns out to be one people ask about, not when it is made. An index
that grows by one row per pull request stops being an index on the day it is complete.

Write a standing record instead when the decision has no diff to live in: a rule about the
repository, a tool retired, a convention adopted. Those go in a file here and get a row above.
