# Security

This repository builds an application that, with one switch turned on, reads and acts on other
applications' screens. That is the whole of what it is for, and it is also the whole of why this
file is not boilerplate.

Read the threat model before installing it on a phone you use for real things.

## Reporting something

Open a **private security advisory** through GitHub: the *Security* tab, then *Report a
vulnerability*. That reaches the maintainer without the finding being public first.

Please do not open an ordinary issue for a vulnerability. Everything else belongs in one.

This is a personal project with one author. There is no response-time commitment beyond an
honest one: you will get an acknowledgement, and if the finding is real it will be fixed or the
limitation will be written down here.

### In scope

The code in this repository: the router, the two phone applications, the routing core they
share, and the build and deployment scripts.

### Not in scope

- **What a token does not protect.** Since #533 the router refuses `/v1/models`,
  `/v1/chat/completions` and `/metrics` without a bearer token it issued; `/healthz` stays
  open because a platform health check arrives with no credential, and it answers only
  whether the process is alive. What a token buys is that a stranger cannot spend the
  provider credit behind it. It is **not** authorisation: every valid token can do
  everything, there are no quotas, and a leaked token is a leaked account until it is
  removed from the list. Metering arrives with accounts.
- **NeuralWatt, Hermes Agent and zeromem.** Report those to the people who wrote them.
- **The platform's own behaviour.** What an accessibility service is allowed to do is Android's
  decision; the section below records it rather than disputing it.

## The threat model

### Screen content is untrusted input, and it steers the agent

`read_screen` turns whatever is on another application's screen into model input. `tap`,
`type_text` and `scroll` turn model output into presses and keystrokes. So anything that can put
text on the screen (a received message, a web page, a notification) is writing into the
context of a model that is about to act on the phone.

This is the largest risk here and it is not fully mitigated. What stands against it:

- **The model never sees a coordinate.** It holds a *handle*, which is re-resolved against a
  freshly fetched tree before every action, and zero matches or more than one is a refusal that
  says which. Text on screen cannot name a target that is not there.
- **A turn cannot act more than 25 times** (`Budget.kt`). Reading is free; acting is not.
- **Some screens cannot be acted on at all** (`Barred.kt`): a locked phone, the permission
  screens, the accessibility settings, the device-admin screens, and this application's own.
- **A password field cannot be typed into.**
- **A banner names what the agent is doing, over the application it is doing it to, with a stop
  button one tap away.**

What does not stand against it: **there is no confirmation prompt**. Every candidate rule for
when one should fire is a guess, and the argument is in
[#452](https://github.com/asigdel29/anu-agentic-phone/issues/452). The decision taken is a
per-person setting defaulting to off, which is not built yet.

### What the agent can read is more than you would expect

**`FLAG_SECURE` windows are readable.** Banking applications, password managers and DRM video
set that flag; it stops screen *capture* and leaves the accessibility node tree alone, because a
screen reader has to work in a banking application.

Two decision records in this repository used to say the opposite and one called it a feature.
They were wrong and are corrected:
[#472](https://github.com/asigdel29/anu-agentic-phone/issues/472) has the measurement, and
`SecureScreenDeviceTest` is the test that would notice if a future Android made the old claim
true.

So: with the accessibility service enabled, this application can read a banking application's
balance and a password manager's entry list. The only protection the framework offers here is
narrow: a node marked `isPassword` has its text withheld.

### What leaves the device

A tool result becomes a message, and the next request carries it to the model. So calendar
entries, contacts, a location fix, repository contents and **anything read off another
application's screen** reach `api.neuralwatt.com` when a turn needs them.

They go there because answering the question requires it, and they go nowhere else. Nothing is
logged: `how-the-agent-drives.md` makes that a rule about what the code may contain rather than
about what it does at runtime: no node text to the system log at any level, no screenshot to
disk including the application's own cache, nothing in a crash breadcrumb. The failure that rule
exists to prevent is somebody enabling verbose logging two years from now.

### Credentials

One: `NEURALWATT_API_KEY`.

- On the board it comes from the environment or a systemd `EnvironmentFile`, never a tracked
  file. `.env` is gitignored; `.env.example` carries names and no values.
- On the phone it is in the Android Keystore. Nothing else is stored.
- `allowBackup="false"` on Android is deliberate: the store this application holds is somebody's
  conversations, and cloud backup would copy it somewhere none of the decisions about it apply.

The signing keystore for a release build is the person's own and is never tracked.

### The build is sideloaded, on purpose

Play policy forbids an accessibility service used for general automation, so this is not
distributable through the store and is not trying to be. A sideloaded build pays for it in
restricted settings: the accessibility toggle is greyed out until they are allowed, and the
application's own checklist screen walks through that because nothing else on the phone explains
why the switch does not work.

One consequence worth stating: **you are trusting a build you made yourself from this source**,
which is the right way round for something with these capabilities.

## What has and has not been verified

Nothing in this repository has run on a physical phone. Every claim above is a host suite or an
emulator, and
[#510](https://github.com/asigdel29/anu-agentic-phone/issues/510) is the checklist for the first
time one is attached. Treat the mitigations as implemented and untested in the field.

What the emulator has settled is narrower than it sounds and worth naming: the release build
loads its native library under R8, a turn reaches the provider, the tool loop drives another
application, and the overlay reaches the display. What it cannot settle is a real screen, a
real calendar, and a person deciding whether they are comfortable.
