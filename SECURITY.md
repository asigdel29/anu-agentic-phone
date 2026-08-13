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
- **What a server you connected does.** An MCP server is somebody else's code on somebody
  else's machine, reached because you asked for it. What this repository owes you is that
  connecting one cannot displace a compiled tool and cannot happen without you typing an
  address; the section below says what it does not owe you.

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
balance and a password manager's entry list. Two things stand against that and both are narrow.
A node marked `isPassword` has its text withheld.

**And a view marked `accessibilityDataSensitive` is withheld entirely.** That is the newer
mechanism, from API 34, and it works: the framework hides such a view from every service that
has not declared `android:isAccessibilityTool`. This application does not declare it and will
not, because it is an automation agent rather than an accessibility tool and declaring
otherwise would be seeing through a marker an application set on purpose.
`SensitiveScreenDeviceTest` is the measurement and the tripwire: it fails if the declaration is
ever added, which is a request to come back and rewrite this paragraph rather than a bug.

So an application that wants to hide from this agent has a way. `FLAG_SECURE` is not it, and
that is the whole of the correction #472 made.

**And since #439 the agent can capture the screen as well as read it.** `driving.xml` declares
`android:canTakeScreenshot`, which #610 measured is required, and a captured screen crosses to
the provider the way anything else a tool produced does. `FLAG_SECURE` windows *are* black
rectangles in a capture, which is the half of the old claim that was always true, so a banking
screen is readable through the node tree and not through a picture. Nothing is written to disk,
including the application's own cache, which `how-the-agent-drives.md` already makes a rule about
what the code may contain rather than about what it does at runtime.

### A connected server writes into the model's context

An MCP server (#596) offers tools, and the agent runs them the way it runs the compiled ones.
Two things about that are worth stating plainly, because neither is obvious from a settings
screen that says `Connected servers`.

**A tool's description is model input written by somebody else.** The name and the sentence
saying what a tool does are passed through as the server wrote them, on every turn, into the
context of a model that is about to act on the phone. They are not sanitised, and that is
deliberate: editing them would be pretending the risk is textual when the risk is that the
server is trusted at all. It is the same class of problem as the section above about screen
content, arriving through a channel somebody chose to open.

**What it cannot do is take a name that already means something.** Every remote tool is offered
as `mcp_<yourlabel>_<name>`, so a server offering `tap` is offered as `mcp_desk_tap` and the
compiled `tap` that actually touches the screen is never displaced. The label is the one you
typed rather than one the server chose, so a server cannot decide how it appears in the list you
used to decide whether to trust it. `McpTest` and `ConnectedTest` hold both ends of that.

Two smaller properties. A server is reached over https only, because a released build cannot
send cleartext at all and a plain-http server would work for whoever built the APK and fail for
everybody else. And a server that is unreachable contributes no tools and stops nothing: the
turn runs with what it has.

What is not mitigated is the first paragraph. A server you connect can describe its tools in
whatever words it likes, and those words reach the model. Connect ones you trust, which is what
the screen says and the whole of what can honestly be claimed.

### What leaves the device

A tool result becomes a message, and the next request carries it to the model. So calendar
entries, contacts, a location fix, repository contents and **anything read off another
application's screen** reach `api.neuralwatt.com` when a turn needs them.

They go there because answering the question requires it, and they go nowhere else. Nothing is
logged: `how-the-agent-drives.md` makes that a rule about what the code may contain rather than
about what it does at runtime: no node text to the system log at any level, no screenshot to
disk including the application's own cache, nothing in a crash breadcrumb. The failure that rule
exists to prevent is somebody enabling verbose logging two years from now.

**The microphone adds nothing to that list.** Recognition happens on the phone, because the code
calls `createOnDeviceSpeechRecognizer` rather than the ordinary factory. The ordinary one binds
whichever service holds `RecognitionService`, which on most phones recognises over somebody's
network; the on-device one matches here or answers an error, so no audio is sent anywhere. The
transcript is put in the message field and reaches the model only when you send it, exactly as
typed text does.

The microphone is open only while you have asked to be listened to. There is no wake word and
nothing here listens on its own. Server-side transcription would change this paragraph, which is
the reason it is not being added quietly: it is deferred with the server work rather than
treated as an implementation detail of a button that already exists.

**Reading an answer back adds nothing either, and it is off until you turn it on.** `TextToSpeech`
synthesises on the phone, so the text of an answer does not leave it to be spoken; the answer had
already reached the model's side of the wire by being answered, and speaking it sends nothing
further. What is read out is the answer alone. Tool results are not spoken, which is a decision
rather than a limit: what a tool printed can be a file, a screen or a repository, and a phone
reading that aloud in a room is a disclosure the person did not ask for.

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

That sentence is true of an APK and would stop being true on the phone
[`docs/decisions/an-agentic-android.md`](docs/decisions/an-agentic-android.md) describes. A
privileged application is not sideloaded, not built by the person running it, and holds the
permissions an image allowlisted rather than ones they granted one at a time from a Settings
screen. What is being trusted moves from a build to a whole operating system and the key that
signed it, and the checklist screen this document keeps pointing at stops being where the
capabilities are decided.

Nothing here runs that way today and no such image exists. It is written down now because the
sentence above is the kind that goes on being quoted after it stops being true, and because a
document about what you are trusting should be the first to say when the answer changes.

## What has and has not been verified

Nothing in this repository has run on a physical phone. Every claim above is a host suite or an
emulator, and
[#510](https://github.com/asigdel29/anu-agentic-phone/issues/510) is the checklist for the first
time one is attached. Treat the mitigations as implemented and untested in the field.

What the emulator has settled is narrower than it sounds and worth naming: the release build
loads its native library under R8, a turn reaches the provider, the tool loop drives another
application, and the overlay reaches the display. What it cannot settle is a real screen, a
real calendar, and a person deciding whether they are comfortable.

The microphone is narrower again, and has had less than the rest of this document. Nothing about
it has run on an emulator or a phone: it was checked by a suite on the host and by reading the
code. The claim that no audio leaves the device is a claim about which factory that code calls,
which is the kind a reader can check for themselves and is not the kind anybody has watched.
