# Pushing from a phone

`git-without-a-subprocess.md` ends by deferring this:

> The network half (fetch, push, credentials, pull request creation) is a separate
> decision, taken separately. Splitting it is not caution for its own sake: it is that the
> first half has no privacy story to argue and the second is almost entirely privacy story.

This is that decision. #467 asked it as three questions, and one of them turns out to be
answerable only by changing what the crate links, so the cost is recorded here before any
code depends on it.

The local half is done: #635 gave the agent a way to make a repository, #639 gave a commit
somebody to sign it, and #642 gave that somebody a place to be typed. What is missing is
everything that leaves the phone.

## SSH, and what it costs

The credential is an SSH key rather than an HTTPS token, and the reason is what happens to
each one in flight.

A token is a bearer credential. It goes to the forge on every request, and anything that
holds a copy can push as you until it is revoked. A key is not sent at all: the server
issues a challenge and the phone signs it, so the secret never crosses the wire and never
sits in a server's logs. On a device whose whole purpose is running an agent that reads
screens and calls tools written by other people, a secret that is never transmitted is a
materially smaller thing to lose than one that is transmitted constantly.

That is the argument. Here is the bill, and it is not small.

`router/Cargo.toml` turns both transports off on purpose, and says so:

> default-features off drops https and ssh, which pull openssl and libssh2 and are the half
> this does not do

The feature graph confirms it. `git2/ssh` implies `libgit2-sys/ssh`, which depends on
`libssh2-sys`, which depends on `openssl-sys` on every target but Windows. So SSH means
cross-compiling **OpenSSL and libssh2 for `aarch64-linux-android`**, through
`vendored-openssl`, into a shared object that is 4.6M today. HTTPS would have cost OpenSSL
too, so the marginal cost of choosing SSH over a token is libssh2 and the host-key policy
below; the shared cost is OpenSSL either way.

**That build is unproven and it is allowed to fail.** It is measured before anything
depends on it, in the shape #472 established: a build, a number, and this file corrected to
whatever it says. If it cannot be made to work, the local half stands and the network half
does not happen, and that is a better outcome than discovering it four changes later.

An `https://` remote is refused with words rather than attempted. Turning `git2/https` on
as well is nearly free once OpenSSL is in the build, so this is a scope line and not a
permanent one. It is written down so that whoever wants it knows it is a decision and not
an oversight.

## The key is made here and stays here

Generated on the device. The private half is sealed with `Keystore.seal` under a second
alias beside the provider credential; only the public half is ever shown, to be pasted into
a forge.

The alternative was a field somebody pastes an existing private key into, which is one
screen instead of two. It is rejected because a key that arrives by paste has been in a
clipboard, and on this phone the clipboard is something the agent can read. A key that is
generated in the process that will use it has never been anywhere a tool could reach.

#467 worried that a second secret is a second thing to lose. It is, and the answer is that
it is lost the same way and in the same place: `Keystore` is already parameterised by alias,
so this costs a constant rather than a mechanism. What is genuinely new is that this secret
is one a forge can revoke without the phone's help, which the provider key is not.

## An unknown host is pinned, and a changed one is refused

#467 called both honest answers "more work than accepting whatever answers", and they are.
Accepting whatever answers is still wrong: the first push would teach the phone to trust
anything, permanently and invisibly.

So the host key is pinned on first use and kept beside the private key. A host whose key
has changed is refused, in words, and **never with a prompt**. A dialog at that moment is a
dialog somebody dismisses on a train, and the one time it matters is the one time it is
indistinguishable from every other time.

This is weaker than a known-hosts file somebody curated and stronger than nothing, and the
weakness is stated rather than buried: the first connection is trusted blind. On a phone
with no shell there is nothing else to trust, and moving the trust decision to first use at
least makes every later connection checkable.

## A rejected push is not a problem to solve

A non-fast-forward push means somebody else's work is on the branch. It is not a transient
failure and it is not something to retry.

**`force` is not an argument in any schema.** Not defaulted to false, not hidden, not
present. An argument a model can set is one it will set, eventually, on the turn where
setting it makes the error go away. The tool reports what happened and stops, and whoever
is holding the phone decides whose work survives.

This is the largest instance of a rule the tools already follow: #402, #432 and #438 each
refuse an ambiguity rather than picking a side, and the cost of picking wrongly here is the
only one on that list that nobody can undo.

`pull` is fast-forward only, for the same reason from the other direction. A merge needs
conflict resolution, conflict resolution needs a diff surface, and there is no diff surface.
A pull that cannot fast-forward says so.

## What none of this fixes

The limits in `git-without-a-subprocess.md` all survive. Hooks still do not run, so a
repository pushed from here is one whose `pre-commit` never fired, and the commit looks
identical to one where it did. LFS pointers are still pointers. `gh` is still not available,
so a pull request is still the GitHub REST API and still a separate decision.

And one that is new here. A push sends repository contents to a third party the person
running this chose, which is a different privacy question from the provider and a larger
one: the provider sees a conversation, a forge keeps the code. Nothing in this repository
makes that decision for anybody, which is why the remote is typed in rather than configured
at build time.
