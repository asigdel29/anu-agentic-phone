# What Android allows

This repository is about to hold two phones and they are not the same phone. #137 opens by
writing down what iOS forbids; most of that list is permitted on Android, and the
difference is large enough that one "mobile" section would be wrong about one of them on
every line.

So this is #137 in the other direction. Every capability below carries a constraint that
decides its design, and three of them decide whether the thing can ship at all, which is
the part worth having written down before anything is built on top of it.

## The screen, which is the whole difference

#137's opening claim is an iOS claim:

> A third-party iOS app **cannot read or drive another app's interface**. There is no
> accessibility *control* API for third parties, no cross-app synthetic touch, and no
> background automation.

Android has all three, through `AccessibilityService`:

- **Read.** `AccessibilityNodeInfo` gives the live view tree of whatever is on screen: text,
  bounds, roles, and whether a node is clickable, refreshed by
  `TYPE_WINDOW_CONTENT_CHANGED` events.
- **Act.** `performAction(ACTION_CLICK)` on a node, and `dispatchGesture` for arbitrary taps,
  swipes and drags at raw coordinates.
- **Navigate.** `performGlobalAction` for back, home, recents and the notification shade.
- **See.** `takeScreenshot` from API 30, without `MediaProjection` and without the recording
  indicator.

That is "the agent takes control of the phone", and it is a real capability rather than a
wish. Four constraints on it, in the order they will bite:

**Play policy is the binding one, and it is not technical.** Accessibility APIs may be used
for accessibility. An agent that drives other apps is not that, and a Play submission
declaring the permission for this purpose is refused. A personally sideloaded build is
unaffected, which is what this repository is, but the moment the goal changes to
distribution, this feature is the reason it cannot.

**`FLAG_SECURE` windows are not blank, and this used to say they were.** Banking apps, password
managers and DRM video set it. They do appear as black rectangles in a screenshot, which that half
got right, and the node tree is untouched. `FLAG_SECURE` restricts screen *capture*, and it
has to leave accessibility alone: a screen reader is required to work in a banking app.

Measured rather than reasoned about, after this paragraph claimed otherwise for a milestone.
`SecureScreenDeviceTest` puts a `FLAG_SECURE` activity in front of the connected service and
reads it: the window comes back with its labels intact. That test exists to notice if a future
Android makes the old sentence true.

So `FLAG_SECURE` does not blind the agent on the apps where a mistake costs most. It sees them
as it sees anything else, and the protection that flag offers is none: the narrow one nearby is
that a node marked `isPassword` has its text withheld, which is why `type_text` refuses one
(#423). Everything else on a banking screen (the balance, the payee list, the last transaction)
is readable by anything the person has switched an accessibility service on for, including
this.

That is a fact about the platform rather than a decision this repository made, and it belongs
in the security posture rather than in a sentence calling it a feature.

**An application does have a way to hide, and it is the newer one.** This paragraph used to end
by saying the agent is not blind, full stop. That was over-broad. Since API 34 a view can carry
`accessibilityDataSensitive`, and the framework withholds it from every service that has not
declared `android:isAccessibilityTool`. `SensitiveScreenDeviceTest` puts one in front of the
service and reads: the ordinary button beside it comes back and the marked one does not.

`driving.xml` does not declare `isAccessibilityTool` and should not. This is an automation agent
rather than an accessibility tool, and the attribute is the framework asking a question it would
be answering falsely to see through a marker an application set deliberately. So an application
that has learned `FLAG_SECURE` leaves the node tree alone has a mechanism that does not, and it
works against this agent today.

**`takeScreenshot` is refused without `canTakeScreenshot`.** #439 says the call "needs no extra
capability". Measured false: the same test asks for one and the binder throws
`SecurityException: Services don't have the capability of taking the screenshot`, before the
callback is reached at all. The attribute is not added either, because nothing calls
`takeScreenshot` and a capability declared ahead of its caller is one nobody can weigh, which is
the rule `app/src/main/AndroidManifest.xml` already states about permissions. Vision adds it
beside its first call, and the estimate for that work is one attribute larger than #439 assumed.

**The service is killed and must survive it.** An accessibility service is restarted by the
system, at which point in-memory state is gone; and it is disabled outright by a settings
change or an update, silently.

**Coordinates are not stable.** `dispatchGesture` takes pixels. A node's bounds move between
devices, orientations, font scales and app versions, so anything that remembers a coordinate
rather than re-reading the tree is a script that breaks on the next update.

## Floating windows are two mechanisms, not one

Worth separating before either is built, because the politics differ more than the code.

**Bubbles** (API 30+) are the sanctioned route. A `Notification` with `BubbleMetadata` and a
conversation shortcut, and the system draws the bubble and its expanded view around one of
your activities. It survives Play review, it behaves like the platform, and the tradeoff is
that the chrome is the system's rather than yours.

**Overlays**, `SYSTEM_ALERT_WINDOW` with `TYPE_APPLICATION_OVERLAY`, are the classic chat
head and draw what you like where you like. The permission is granted from a Settings screen
rather than a dialog, so it costs a trip out of the app; it is blocked over Settings itself
and over `FLAG_SECURE` windows; and it is one of the permissions Play looks at hardest.

Both, defaulting to bubbles, is the shape that follows: the sanctioned one is enough for a
chat, and the overlay is what an agent driving another app needs to say what it is doing over
the top of it.

## Long work has somewhere to run

A foreground service with a persistent notification runs for as long as it is useful, with a
ceiling that depends on which kind it says it is, and the answer has changed since this was
written.

iOS has nothing equivalent (`beginBackgroundTask` buys seconds, not minutes), which is why
`TurnDriver.isLong` exists there to warn somebody before they walk away rather than to keep
working. On Android the same tier can simply finish, and the notification is where the turn
reports itself.

Android 14 requires a declared `foregroundServiceType`. This said `dataSync` was the honest
one for an agent turn, and Android 15 has partly overtaken that: `dataSync` is capped at six
hours in any twenty-four, after which `onTimeout()` fires and the service must stop or be
killed. `specialUse` is uncapped, and needs no Play justification for a build that is
sideloaded, so it is the honest one now.

Worth being precise about what the service buys, too, because it is less than the section
title suggests. What keeps a turn running is a process the system is not looking to reclaim;
what the service actually adds is a notification saying so, with somewhere to cancel from. An
`AccessibilityService` is bound by the system and outside background restrictions already, so
the phone-driving work in #233 does not need one of these to survive.

## A shell, and the constraint that shapes it

Since API 29 an app cannot `exec` a file it wrote into its own data directory. W^X is
enforced, and this is the single fact that decides how a terminal is built.

The routes that remain:

- **Ship executables as native libraries.** Anything in `jniLibs` lands in the app's native
  library directory, which is executable. It is not a general package manager, but it holds a
  shell and a fixed set of tools.
- **Target an older API.** What Termux does, and the reason it is not on Play.
- **Do not exec at all.** Implement the useful subset in-process, which is what the file
  tools already do on iOS.

The first is the one that fits: the tools an agent needs are a known list, and a package
manager the model can install arbitrary binaries with is a larger promise than a terminal.

## A browser is two features that are easy to conflate

**An embedded `WebView` is fully drivable.** `evaluateJavascript` reads the DOM, clicks,
fills forms and scrapes, with `WebViewClient` for navigation. Everything an agent needs, on
pages the app itself loads. It is also its own session: not signed in to anything the person
is signed in to, which is a privacy property worth keeping rather than working around.

**The person's Chrome is not drivable**, except through the accessibility route above, which
means driving it as a human does, by reading pixels and dispatching taps, with all four
constraints from that section.

Calling both "browser control" hides the difference between a tool that reads a page and an
agent that operates somebody's signed-in browser. They deserve separate names.

## What does not change

The routing core. `wattrouter` already cross-builds for `aarch64-apple-ios`, and there is
nothing Apple-specific in it: the tiers, the policy, the sticky cache and the chain walk are
plain Rust over a C ABI. `aarch64-linux-android` is another target of the same crate.

So the decision path is shared, not reimplemented. That is worth stating plainly because the
alternative is attractive and wrong: a Kotlin reimplementation of `policy.rs` would be a
second routing policy that agrees with the first until the day it does not, which is exactly
the argument `docs/decisions/retiring-the-second-harness.md` already made about a second
harness.

The seams above the core are the same shape too. `Inference`, `Tool`, `Permission` and the
turn loop are ideas rather than Swift, and the Android side should arrive at the same ones
rather than inventing different ones, with one honest exception: `Permission` assumes a
prompt shown once and never again, and Android's Settings-screen permissions do not behave
that way.
