# Roadmap

What has not happened yet, in the order it is meant to happen, and why that is the order.

One line per unit. The issue carries the problem, the candidate answers and the constraints;
this file carries only the sequence, because a second copy of an argument is a copy that goes
stale. Nothing here is a decision record: `docs/decisions/INDEX.md` is for decisions with no
diff to live in, and this is a statement about work that has not been done.

## The order

```
 3  Plan mode        done, and it was what gated 6 and 11
 9  MCP wired in     done, and it was written and tested long before
 4  Vision           the shape of a message changes, so it goes before what needs it
 5  Driving visuals  the same capture path as 4
 6  Write tools      dangerous before 3, which is why it is after it
11  Terminal         the largest new surface, and it needs nothing from the server
10  Voice            on-device capture; server transcription waits
 1 2 7 8             the day Railway is provisioned
13  the fork         continues throughout, one property at a time
```

Three things decide that sequence.

**Four units are blocked outside this repository.** Units 1, 2, 7 and 8 all need a database,
which needs Railway provisioned, which has not happened. They are grouped rather than
interleaved because none of them can start.

**Plan mode gated the two dangerous units, and it has landed.** Write-capable tools and a shell
are both cases where per-action confirmation is either meaningless or unusable, and where acting
unattended without having said what you intend is worse. Unit 3 was also the smallest piece on
the list, which is why it went first. Units 6 and 11 are unblocked by it.

**Vision changes the shape of a message.** `Tool.run` answers a `String`, `Conversation` builds
string content, and `Inference` has no notion of an attachment. Anything that carries an image
touches all three, so unit 4 goes before unit 5 rather than beside it.

## The units

| # | Unit | Issue | State |
|---|---|---|---|
| 1 | Server: accounts, key custody | [#539](https://github.com/asigdel29/anu-agentic-phone/issues/539) | Half. Token auth, container and cache merged; no database dependency yet. Blocked on Railway. |
| 2 | The phone talks to the server, not the provider | [#597](https://github.com/asigdel29/anu-agentic-phone/issues/597) | Half. `UPSTREAM_BASE_URL` is fixed at build time; sign-in is still a provider key. Blocked with 1. |
| 3 | Three autonomy modes | [#595](https://github.com/asigdel29/anu-agentic-phone/issues/595) | Done. All three are offered; Plan puts the first round to somebody once and then runs unattended. |
| 4 | Vision: capture, and an image crossing the wire | [#439](https://github.com/asigdel29/anu-agentic-phone/issues/439) | Not started. Three blockers in the message shape, and a fourth in `driving.xml` that #439 did not know about. |
| 5 | Driving visuals: a border, and a replay card | [#598](https://github.com/asigdel29/anu-agentic-phone/issues/598) | Not started. Shares a capture path with 4. |
| 6 | Write-capable tools | [#393](https://github.com/asigdel29/anu-agentic-phone/issues/393), [#467](https://github.com/asigdel29/anu-agentic-phone/issues/467) | Not started. Needs 3. |
| 7 | Conversations and memory on Postgres | [#599](https://github.com/asigdel29/anu-agentic-phone/issues/599) | Not started, zero code. Blocked with 1. |
| 8 | Scheduler and background tasks | [#600](https://github.com/asigdel29/anu-agentic-phone/issues/600) | Not started, zero code. The server half is blocked with 1; the phone half is not. |
| 9 | MCP into `ToolBox`, and a connections screen | [#596](https://github.com/asigdel29/anu-agentic-phone/issues/596) | Done. Servers are saved, asked at startup, and their tools folded in behind a `mcp_` prefix. |
| 10 | Voice | [#601](https://github.com/asigdel29/anu-agentic-phone/issues/601) | Not started, zero code. Server transcription is blocked with 1. |
| 11 | Terminal | [#602](https://github.com/asigdel29/anu-agentic-phone/issues/602) | Not started, zero code. Needs 3. |
| 12 | Tag, removals, README | | Done. |
| 13 | An agentic Android | [#603](https://github.com/asigdel29/anu-agentic-phone/issues/603) | Recorded, then built one property at a time. Runs alongside everything above. |

The numbers are fixed. An issue that references unit 4 should go on meaning unit 4, so nothing
here is renumbered when something lands.

## What is outside this file's control

Three things, and naming them is the point of having the file:

- The provider account has no credit, so a real turn returns 402 and `scripts/stub-model.py`
  carries the tool loop instead.
- Nothing in this repository has run on a physical phone. The README says so on its front page,
  and [#510](https://github.com/asigdel29/anu-agentic-phone/issues/510) is the checklist for
  the first time one is attached.
- Developer verification reaches certified devices on 30 September 2026, which is the clock
  unit 13 answers.

There is no link checker here and this does not add one. The links above were resolved by hand.
