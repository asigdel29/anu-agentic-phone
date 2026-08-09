# How the agent drives

`what-android-allows.md` says what the platform permits and what stops it. This says how this
agent uses any of it, which is a different question and the one the next dozen changes answer
to. It is written before them rather than after, because each of the six decisions below is
cheap to make now and expensive to reverse once eight tools depend on it.

Three of these came out of a design review that changed my mind, and the reasons are the part
worth keeping — a conclusion without its argument is a thing the next person re-litigates.

## The model never sees a coordinate

`dispatchGesture` takes pixels, and that is where the temptation is: a `swipe(x1,y1,x2,y2)`
tool is four lines and works the first time. It is also the failure #233 opens by warning
about — a coordinate remembered rather than re-read — handed to the model as an API.

So coordinates stay inside the service and never enter the model's vocabulary. Scrolling is by
handle. System gestures are by name. What the model holds instead is a **handle**, which is a
recipe for finding a node again:

| | |
|---|---|
| `viewId` | The resource id. Survives text changes, translations and font scaling. |
| `role` | The class, coarsely: button, field, list, text. |
| `text` | What it says, which is what a person would call it. |
| `desc` | The content description, when there is one. |
| `siblingIndex` | Where it sits among its siblings — the structural path, last. |

Ordered by durability, and re-resolved against a **freshly fetched tree before every action**.
Not against the tree the handle came from: between reading the screen and tapping it, the
screen has had time to change, and the whole point is to notice.

**Zero matches or more than one is a refusal that says which.** Not a best guess, not the
first match. `patch` already takes this posture on an ambiguous match (#133) and the reason
carries over exactly: a remembered coordinate breaks silently and taps whatever moved into
that spot, while a remembered recipe fails loudly and says it could not find the thing. One of
those is recoverable by the model. The other is how an agent buys something.

## The generation is a pair, not a counter

Every handle carries the generation of the tree it was read from, and an action against a stale
generation is refused rather than attempted.

A single counter is not enough, and this is the one hole in the design that produces a *wrong
tap* rather than a refusal. The service is killed and restarted by the system routinely — it is
bound rather than owned, and `what-android-allows.md` records that in-memory state does not
survive it. A counter that restarts at zero means a handle read before the kill can match a
tree read after it, by coincidence, and the coincidence is likeliest in exactly the case that
matters: early in a session, at low numbers.

So it is `(epoch, counter)`, where the epoch is fixed when the service starts and is not
reused. A handle from a previous life of the service fails to match, every time, by
construction.

**And the counter increments on a structural change, not on every event.** Content changes
constantly — a clock, a progress bar, an unread badge — and a generation that moved with them
would make every handle stale before the model could use one, which is a system that refuses
everything and is indistinguishable from a broken one. The counter follows a hash of the tree's
shape: what nodes exist, where, and what they are. Text moving inside a node it already had is
not a new screen.

## Restricted settings, which is how this silently does nothing

Since Android 13 an app installed outside a store cannot be granted an accessibility service
from the normal Settings screen. The toggle is visible and greyed out, with no explanation at
the point of failure. It is ungreyed only by opening **App info → ⋮ → Allow restricted
settings**, which nobody discovers by looking.

App stores are exempt — including F-Droid — because the exemption rides on the install session
rather than on the store's identity. A plain `adb install` does not get it, and neither does
opening an APK from a file manager.

This repository ships a sideloaded APK, so it is on the wrong side of that line by design.
Onboarding has to **detect the state and walk somebody through it**; if it does not, every
phone-driving tool is built, present, offered to the model, and does nothing at all, with no
error anywhere to point at. That is the worst shape a failure takes here, which is why this
paragraph exists before the code does.

## A blank screen and a hidden one are different answers

`FLAG_SECURE` windows — banking apps, password managers, DRM video — expose nothing through
accessibility. `what-android-allows.md` calls that a feature and it is.

But reporting it as *an empty screen* tells a model there is nothing there, and a model told
there is nothing there decides the page has not loaded and acts again. The read has to say the
application hides its contents, in those words, so that the only correct next step — ask the
person — is the one that follows.

## Nothing is logged

A snapshot of the tree is the contents of somebody's screen: their messages, their balance,
their address. It goes to the model because that is the point, and it goes nowhere else.

No node text to logcat, not at debug level and not behind a flag that defaults off. No
screenshot written to disk, including the app's own cache. Nothing in a crash breadcrumb. This
is a rule about what the code may contain rather than about what it does at runtime, because
the failure is somebody enabling verbose logging two years from now and not knowing.

## Safety is enforced in the service

Not in the tools. A rule that lives in a tool is a rule the ninth tool forgets, and the ninth
tool is written by somebody who has read the eight before it and inferred the pattern rather
than the reason. Enforced where the action is dispatched, a new tool cannot route around it
because there is no other way through.

What that covers is argued in its own change, and the shape is: this app is not driven by
itself; the accessibility, device-admin and permission Settings screens are not driven at all;
nothing happens while the keyguard is locked; a password field is not read; and a turn has an
action budget, because `Agent`'s eight-round cap bounds model round-trips and not effects.
