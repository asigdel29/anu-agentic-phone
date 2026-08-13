# An agentic Android

`what-android-allows.md` reads as a list of separate constraints. It is one constraint listed
several times: the agent is an ordinary application, and Android does not trust an ordinary
application with the phone.

That record says what an unprivileged app may do. This one says what stops being true if the
app is not unprivileged, what that would cost, and which of the constraints a fork does not
fix. It exists because the answer sounds like "fork AOSP" and mostly is not, and because
somebody will otherwise start one.

## What the constraint costs today

Five things, and each is worked around rather than solved:

- **The service is switched on by hand and switched off silently.** A settings change or an
  update disables it, and the app finds out by reading `Settings.Secure` on resume. `Reading.kt`
  exists for that, and the readiness screen recomputes rather than caching because a one-way
  wizard would lie the first time anything was switched off.
- **The overlay cannot be drawn where it matters most.** `SYSTEM_ALERT_WINDOW` is blocked over
  Settings and over `FLAG_SECURE` windows, so the banner naming what the agent is doing is
  absent on the screens where somebody would most want it.
- **A shell cannot run what it writes.** W^X since API 29, measured in #666 rather than assumed.
  What it costs is narrower than this line used to claim: the platform's own shell and its 214
  toybox applets run from an ordinary app, so the tool list is fixed at build time only for
  tools the platform does not already have.
- **Play policy forbids the whole premise.** An accessibility service used for automation is
  refused at submission, so distribution is sideloading and only sideloading.
- **Sideloading itself has a date on it.** Developer verification reaches certified devices on
  30 September 2026. What that changes for a build signed by an unverified developer is not
  something this repository controls.

## Three things that look like they need a fork and do not

Worth stating first, because they are the ones that would justify starting one.

**Reading and driving another app's screen.** `AccessibilityService` already gives the live
node tree, `performAction`, `dispatchGesture` and `performGlobalAction`. That is the whole
capability, on a stock phone, granted by a person at a settings screen. Nothing is withheld
from an unprivileged service here.

**Reading a `FLAG_SECURE` window.** Measured in #472 and recorded in `what-android-allows.md`:
the node tree of a secure window is intact, because a screen reader has to work in a banking
application. The flag restricts capture, not accessibility. An earlier version of that record
claimed the opposite for a whole milestone.

**Surviving in the background.** An `AccessibilityService` is bound by the system and outside
background restrictions already. The foreground service adds a notification and somewhere to
cancel from, which is worth having, and it is not what keeps the work alive.

## Two things that sound like they help and need checking

**`EXECUTE_APP_FUNCTIONS`** reads like a privileged route to driving other applications. It is
not that: it reaches functions an application has chosen to expose, so its coverage is the set
of apps that opted in, which on a real phone is close to empty. It does not replace the
accessibility route and it is not a reason to be privileged.

**Privileged screen capture** sounds like it removes the `MediaProjection` consent dialog and
the recording indicator. Whether it does, and at what signature level, is a claim to measure
rather than to design around. `takeScreenshot` from an accessibility service already avoids
both, which may make the whole question moot.

Both are written down as open rather than assumed, for the reason #472 exists.

## Privileged, not platform-signed

If the constraints above are worth removing, the smallest thing that removes them is a
privileged application: an APK in `/system/priv-app` on an image that allowlists the permissions
it is granted. That is meaningfully weaker than platform-signed, and weaker is the point.

Platform-signed means holding the platform key, and anybody running that image is trusting
whoever holds it with everything, not with this application. A privileged app is granted a
named list, and the list is reviewable.

The allowlist is also the thing that fails loudly. A privileged permission a privileged app
requests and the image does not allowlist is **a boot loop**, not a log line. So this is learned
one property at a time on hardware that can be reflashed, rather than designed up front and
discovered at first boot.

## GrapheneOS or LineageOS, not raw AOSP

Raw AOSP is the version that sounds principled and is the most work: no device trees for real
hardware, no drivers, and a build that boots on nothing anybody owns.

GrapheneOS and LineageOS both already do the part that is not this project's problem. Both take
a privileged application. The difference that matters is that GrapheneOS is a security posture
with opinions, several of which cut against an agent that reads every screen, so it may refuse
this on purpose. That is a reason to check before choosing rather than a reason to prefer the
other.

## The uncomfortable half

A phone without Google Play Services is a phone some applications refuse to run on. Banking
applications check, several messaging applications degrade, and no engineering in this
repository changes that.

This matters more here than it would elsewhere, because the whole premise is driving the
applications somebody already has. An agent that can operate every app except the ones its owner
actually uses is a demonstration rather than a product.

So this is not a plan to ship a phone. It is the direction the constraints point, taken one
property at a time, with each property answering a constraint that was costing something first.

## The first buildable step

`scripts/lint/no-google.sh`. `AndroidWhereabouts.kt:7` declines `play-services-location` and
takes `LocationManager` with `FUSED_PROVIDER` instead, and it declines it as the fourth
application of a habit: hand-written AES-GCM over Tink, `buildJsonObject` over the
serialization plugin, `HttpURLConnection` over OkHttp. Nothing checks that any of it stays
declined.

A dependency that arrives transitively is a decision nobody made, and it is the one thing on
this page that can be held today rather than argued about.
