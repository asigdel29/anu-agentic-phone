# Contributing

A map rather than a rulebook. The rules live in [`AGENTS.md`](AGENTS.md) and
[`docs/coding-standard.md`](docs/coding-standard.md), and a second copy here would be free to
disagree with them, which is the argument that made `CLAUDE.md` a symlink rather than a file.

What this page is for is the four things that will fail your first pull request if nobody tells
you, and where to go for the rest.

## The four

1. **Open the issue first, and reference it.** The issue is where the problem is stated; the
   pull request is only where it is solved. `Closes #12` or `Refs #12` in the body. A pull
   request without one fails the governance job; that guard is *hard*.
2. **At most 300 changed lines**, added plus removed, excluding lockfiles and vendored data.
   Work that runs over splits into a follow-up on the same branch under a new issue linked to
   the parent; the failure message spells out the steps. If you build a stack that way, read
   the next section before you merge any of it.
3. **Branch as `<issue-number>-<short-description>`.** That is what `gh issue develop` produces
   and what the guard recognises.
4. **No tooling is named** in a commit, a pull request, a comment or a document, and no
   attribution trailer is added. The repository has one authorial voice.

Commit subjects are imperative and one line. The body says *why*, wraps at 80 columns, and
references the issue.

## Merging a stack, in the one order that works

A branch is deleted when its pull request merges. That is what keeps 193 branches from becoming
400, and it has one consequence that is not recoverable: **a pull request whose base branch is
deleted is closed, and GitHub will not reopen it**, because reopening needs a base that still
exists. It has to be raised again as a new pull request, under a new number, without its
review history.

So a stack merges bottom-up, and each step happens before the merge below it, not after:

```sh
gh pr edit <child> --base main          # retarget, while the parent still exists
git rebase --onto main <parent-tip>     # the parent will be squashed, so drop your copy
git push --force-with-lease
gh pr merge <parent> --squash           # only now
```

Skipping the retarget costs a pull request. Skipping the rebase costs a review: the child's
diff will show the parent's changes again, and its size will fail the guard for work that has
already merged.

## Before you push

```sh
just toolchain    # says what is missing before a build fails deep inside one
just guards       # the pull request guards, against the default branch
```

`just guards` runs the same scripts CI runs, so a failure here is a failure there. It reports
one guard as *advisory*: read what it says and decide, rather than routing around it.

Then the suites for whatever you touched. `just --list` is current and any list here would not
be. Note that Android has two and they make different claims; `android/AGENTS.md` is emphatic
about which may say what.

## Say where it ran

This is the repository's own rule and it is worth arriving knowing it. A change that compiled in
CI, a change that passed a host suite, a change that ran on a simulator or an emulator, and a
change that was watched on a phone are four different claims. The pull request should say which
one it is making. "Tests pass" is not one of them.

## Where the reasoning is

- **[`docs/decisions/INDEX.md`](docs/decisions/INDEX.md)** routes a question (why is a round
  committed atomically, what can reach the default branch) to the argument that answered it.
- **Merged pull request bodies** are where most of that reasoning actually is. `gh pr view <n>`.
- **File headers.** Every source file opens with its purpose and a history line; `head -20` is
  usually faster than reading the file.

## Reporting a vulnerability

Not here. [`SECURITY.md`](SECURITY.md) has the private route.
