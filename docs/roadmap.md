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
 4  Vision           done, and it was what changed the shape of a message
 5  Driving visuals  done, on the capture path 4 built
10  Voice            the phone half is done; server transcription waits
 6  Write tools      dangerous before 3, which is why it is after it
11  Terminal         the largest new surface, and it needs nothing from the server
 1 2 7 8             the day Railway is provisioned
13  the fork         continues throughout, one property at a time
```

Four things decide that sequence.

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

**Voice moved up because nothing gated its phone half.** A capability, a seam over the platform
recognizer and a button wait on neither plan mode nor a shell, so unit 10 went before 6 and 11
rather than after them. What is gated is its server half, which is why the row below reads half
rather than done.

## The units

| # | Unit | Issue | State |
|---|---|---|---|
| 1 | Server: accounts, key custody | [#539](https://github.com/asigdel29/anu-agentic-phone/issues/539) | Half. Token auth, container and cache merged; no database dependency yet. Blocked on Railway. |
| 2 | The phone talks to the server, not the provider | [#597](https://github.com/asigdel29/anu-agentic-phone/issues/597) | Half. `UPSTREAM_BASE_URL` is fixed at build time; sign-in is still a provider key. Blocked with 1. |
| 3 | Three autonomy modes | [#595](https://github.com/asigdel29/anu-agentic-phone/issues/595) | Done. All three are offered; Plan puts the first round to somebody once and then runs unattended. |
| 4 | Vision: capture, and an image crossing the wire | [#439](https://github.com/asigdel29/anu-agentic-phone/issues/439) | Done. A message carries parts, a tool may answer one, and `look` captures a screen. |
| 5 | Driving visuals: a border, and a replay card | [#598](https://github.com/asigdel29/anu-agentic-phone/issues/598) | Done. A frame while it drives, and the last six screens afterwards. |
| 6 | Write-capable tools | [#393](https://github.com/asigdel29/anu-agentic-phone/issues/393), [#467](https://github.com/asigdel29/anu-agentic-phone/issues/467) | Most. `init_repository`, `stage_paths` and `commit` are tools signed by whoever the phone says it is, and `set_remote` and `fetch` reach a host with a key the phone made and a host key it pinned on first sight. `push` and `pull` have no tool over them yet, and nothing here has reached a real forge. |
| 7 | Conversations and memory on Postgres | [#599](https://github.com/asigdel29/anu-agentic-phone/issues/599) | Not started, zero code. Blocked with 1. |
| 8 | Scheduler and background tasks | [#600](https://github.com/asigdel29/anu-agentic-phone/issues/600) | Not started, zero code. The server half is blocked with 1; the phone half is not. |
| 9 | MCP into `ToolBox`, and a connections screen | [#596](https://github.com/asigdel29/anu-agentic-phone/issues/596) | Done. Servers are saved, asked at startup, and their tools folded in behind a `mcp_` prefix. |
| 10 | Voice | [#601](https://github.com/asigdel29/anu-agentic-phone/issues/601) | Half. A `MICROPHONE` capability, an on-device `Listening` seam, a press-to-talk control writing into the message field rather than into `send`, and a checklist row. No speaking back and no wake word. None of it has run on an emulator or a phone. Server transcription is blocked with 1. |
| 11 | Terminal | [#602](https://github.com/asigdel29/anu-agentic-phone/issues/602) | Done. A `Terminal` seam over `/system/bin/sh` answering three outcomes, a `Shown` gate putting the command itself in front of somebody in Ask and in Plan, and `run_command` wired into the `ToolBox`. Its own surface, with scrollback of its own, is not built and waits on #641. |
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
