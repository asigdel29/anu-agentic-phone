# What computer use means

*Which of three products "the agent takes control of a computer" is, and what the other two are
for.*

## Why this is a record

There is no diff. Choosing among the three decides what several later changes are, and none of
those changes is the place to argue which one they are. [#233](https://github.com/asigdel29/anu-agentic-phone/issues/233)
said it should be settled before item 6 lands rather than after, and this is that.

## The three

**The phone as a client.** The agent runs on the phone and drives a desktop over SSH, ADB or a
remote-desktop protocol. Mostly a transport and a screen.

**The phone as a relay.** The desktop does the work; the phone is where the conversation
happens.

**The desktop as another deployment.** A fourth place the same core runs (board, iOS, Android,
desktop) with the same turn loop and tools that happen to be a real shell.

## The decision

**The desktop as another deployment**, and the other two are not stepping stones to it.

The reason is not that it is the biggest. It is that the other two put the agent somewhere it
cannot think. A client sends keystrokes and reads back a screen; the model's context is a
terminal's scrollback, which is the worst representation of a machine's state there is:
unstructured, lossy, and mostly redraw. A relay is better and still divides the work from the
conversation, so every tool result crosses a network before the model sees it and every failure
has two places to be.

A deployment has neither problem. `wattrouter` already crosses to three targets with nothing
platform-specific in it. The turn loop, the transcript, the routing and the tool protocol are
the same ideas everywhere, and a desktop differs from a phone in what its tools may do rather
than in what a turn is. That is the same argument
[what-android-allows.md](what-android-allows.md) makes for Android, and it held.

## What the other two are for, and it is not nothing

**The client is what you want when the computer is not yours.** A server you have an account on,
a build machine, somebody else's laptop. A deployment needs the core installed; a client needs
a login. That is a real difference and it is why the client is not simply the worse version:
it is the version that works where you cannot install anything.

**The relay is what a phone is for once the desktop deployment exists.** Not a third
architecture: a desktop deployment plus a way to reach its conversation from a phone. That is a
transport problem over an existing turn, which is much smaller than it sounds when written as
one of three "computer use" designs, and it should be described that way rather than as a
product.

So the order is: deployment first, relay second and cheaply, client if and when somebody
actually needs to drive a machine they cannot install on.

## The question Android forces

[#229](https://github.com/asigdel29/anu-agentic-phone/issues/229) records that an Android agent
can drive the phone through `AccessibilityService`. "The agent takes control of a computer" and
"the agent takes control of the phone" then become the same sentence pointed at different
hardware, and the temptation is to make them the same mechanism.

They should not be. Driving a phone through the accessibility tree is a *last resort*: it
exists because Android offers no other way in, and #229 records what it costs: coordinates that
break on the next app update, `FLAG_SECURE` windows that come back blank, and a distribution
story that ends at Play policy. A desktop has shells, files and processes. Reaching a desktop
through a screen when it has a shell would be choosing the accessibility tree's problems on a
platform that does not have them.

One core, two tool sets, and the tool set is where the platform lives. That is already how
`router/`, `ios/` and the board relate, and it is what stops this becoming a second agent.

## What this does not settle

Whether the desktop deployment is a fourth crate, a binary in `router/`, or something that
reuses `wattrouter serve`. That is a design question with a diff, and it belongs in the pull
request that makes it.
