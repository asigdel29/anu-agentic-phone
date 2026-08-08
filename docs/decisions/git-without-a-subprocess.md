# Git without a subprocess

Everything on the board shells out to `git`. `hermes_cli/web_git.py:9` says so outright.
A phone has no shell, so the operations have to come from a library, and this records what
that costs before anything depends on it — because most of the cost is invisible until
somebody trusts the result.

## The route in, and the one that was assumed

#139 named SwiftGit2 and described it as "a package dependency that has to resolve and
link, which needs Xcode". It is not a package dependency. It cannot be:

```
$ swift package resolve
Fetching https://github.com/SwiftGit2/SwiftGit2.git
error: the package manifest at '/Package.swift' cannot be accessed
       (/Package.swift doesn't exist in file system)
```

The repository root holds `SwiftGit2.xcodeproj`, a `Cartfile.private`, a `libgit2` git
submodule and no manifest. The newest tag is 0.6.0. So the plan to try resolving it first
and fall back if it failed took one command, and the fallback is the plan.

**libgit2 comes in as a Rust dependency of the crate that is already here.**
`scripts/build-ios-core.sh` already cross-builds `router/` for `aarch64-apple-ios` and
`aarch64-apple-ios-sim` and packages both slices as an xcframework Swift links. `git2`
builds for both, with default features off:

```
Finished `release` profile [optimized] target(s) in 15.34s   aarch64-apple-ios
Finished `release` profile [optimized] target(s) in 11.41s   aarch64-apple-ios-sim
```

No CMake, no submodule, no second build system, and no third-party Swift wrapper to keep
alive. `libgit2-sys` vendors the C and compiles it with `cc`, which is the toolchain the
router already crosses with. One crate, one static library per slice, one xcframework —
the alternative was two of each, and two Rust static libraries in one binary means two
copies of the standard library.

## What libgit2 does not do

None of this is a bug and none of it is fixable here. It is what the library is.

**Credential helpers are not run.** They are programs, and libgit2 does not exec programs.
Credentials are supplied through a callback instead. Anything a developer has configured
in `credential.helper` — a keychain helper, a corporate SSO helper — does not happen.

**Hooks are not run, by design.** This is the one with a consequence #139 does not state.
A repository the agent commits to on a phone is a repository whose `pre-commit` never ran.
If a team relies on hooks for formatting, linting, secret scanning or signing, none of it
happened — and the commit looks identical to one where it did. The failure surfaces in
somebody else's CI, attributed to the author.

**LFS pointers clone as pointer files.** A repository using LFS will check out text files
containing an object id where the model expects content, and the model will read them as
the file.

**Submodules have edge cases**, and the ones that matter are the recursive and relative-URL
cases that `git` handles in shell logic that has no libgit2 equivalent.

**`gh` is not available.** Creating a pull request becomes the GitHub REST API written out
in Swift, which is a second off-device endpoint carrying repository contents. That is a
different privacy question from the provider, and it belongs behind an explicit opt-in
rather than arriving as a side effect of a tool called `commit`.

## What is in scope, and what is deferred

Local git first: status, diff, add, commit, log, branch. Every limit above except hooks
belongs to the *network* half, and the local half is testable today against a repository a
test creates in a temporary directory.

The network half — fetch, push, credentials, pull request creation — is a separate
decision, taken separately. Splitting it is not caution for its own sake: it is that the
first half has no privacy story to argue and the second is almost entirely privacy story.

## The honest summary

This is fine for a personal repository and wrong for anything with team tooling. The
distinction is not about scale. It is that a personal repository's owner knows their hooks
did not run, and a team's does not.
