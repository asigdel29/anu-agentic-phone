# Working in `android/`

The phone app, and the routing core wrapped for Kotlin. Read the root `AGENTS.md` first; this
holds what is true only here.

Three things make this subtree unusual, and each has cost somebody an afternoon: a test recipe
that passes without running, a Kotlin file that a Rust test reads by path, and three versions
in the build that look like defaults and are not.

## Layout

Two modules. `core/` is the library and `app/` is the application. The core stays a library
because it is what an instrumented test loads, and what a second surface (a share target, a
tile, a service in its own process) links without also taking on the app's manifest.

`core/src/main/kotlin/com/getlora/wattrouter/` holds `Core.kt`, the core as Kotlin sees it
(`open`, `decide`, `close` over four JNI entry points), and `Conversation.kt`, the state a turn
accumulates and the request body it becomes. That encoding is hand-written with
`buildJsonObject` rather than `@Serializable`: what a message leaves out is a decision rather
than a default, and the compiler plugin would be a version to keep in step with AGP for
nothing.

`core/src/main/jniLibs/` holds `arm64-v8a/libwattrouter.so`, which `just android-core` builds
from the crate in `router/`. It is build output, it is gitignored, and an
AAR assembled without it installs and fails to load. It sits in AGP's default location rather
than a declared one: the `sourceSets` accessor that used to point at `core/jniLibs` broke on the
AGP 9.3 bump, and the default needs no accessor at all.

`core/src/main/AndroidManifest.xml` is empty on purpose. A library asking for no permission and
contributing no component has nothing to declare; the accessibility, overlay and
foreground-service entries belong to `app/`, which is where the launcher and the permissions
live.

Plugin versions are in `settings.gradle.kts`, not a root build file. Both Android plugins come
from one artefact, and a version declared in two subprojects puts it on the classpath twice, and
Gradle then fails with a message naming neither module.

## Three versions that are decisions

All in `app/build.gradle.kts` unless said otherwise, and all three the kind somebody tidies up.

**`compileSdk = 37` needs `compileSdkMinor = 1` beside it.** Android ships minor platform
releases now and the SDK installs this one as `android-37.1`; `compileSdk = 37` alone fails
with *Failed to find target with hash string 'android-37'* while the directory sits there. 37
rather than 35 because Compose 1.12 and later require it.

**`targetSdk = 35`, deliberately**, while the phone-driving layer is built. Targeting 36 opts
into Android 16's enforced edge-to-edge, which cannot be turned off, and that puts node bounds
in *driven* apps under the system bars: a tap at an element's centre can land on the
navigation bar. Nothing forces the bump: this app is sideloaded and has no store deadline. It
is a bump to make once the gesture planner is inset-aware.

**The Compose compiler plugin is pinned to the Kotlin AGP bundles**, 2.2.10 for AGP 9.0.0,
which is not the newest published. AGP bundling Kotlin does not bring this plugin with it, and
`compose = true` without it fails at configuration time saying so.

## The two test recipes, and what each may claim

`just android-test` runs on the JVM. It covers everything that touches nothing Android, which
today is `Conversation.kt`, the request a turn becomes. It never loads the library.

`just android-device-test` boots an emulator and is **the only recipe that can say the binding
works**. The `.so` is built for `aarch64-linux-android` and will not load on the host at all,
so no amount of JVM testing reaches `Core.kt`. Before #314 every check on the binding was a
string comparison between two files; the first run on a device found a real serialization bug
in the core within six tests.

It runs both modules' suites and they make different claims. `core`'s says the library loads
and the ABI is right. `app`'s says the `.so` survived the trip into the APK, out of
`core/src/main/jniLibs` into the AAR into `lib/arm64-v8a/`, which `core`'s suite cannot see,
because it packages its own test APK.

So: a pull request touching `Core.kt` or `jni.rs` and claiming only `just android-test` has
claimed nothing about the change it made.

## `gradle test` passes without running

Gradle reports a cached test task as `UP-TO-DATE` and exits zero having run nothing, which on
a terminal reads exactly like a pass. `just android-test` passes `--rerun-tasks` for that
reason and it is not optional anywhere, CI included. When counting tests, read the result
XML under `build/test-results/`, not the console.

## The symbol contract is checked from Rust

`router/src/jni.rs` holds a test that reads `core/src/main/kotlin/…/Core.kt` **by path**
through `include_str!` and asserts that every `Java_com_getlora_wattrouter_Core_*` function it
exports has a matching `external fun` on the Kotlin side. That is what keeps a renamed native
method from becoming an `UnsatisfiedLinkError` on a device rather than an error at build.

It also means the source layout is load-bearing. Moving `Core.kt` edits `jni.rs` in the same
commit, or the test fails on a path rather than on a symbol and says nothing useful about why.
Splitting this subtree in two was exactly that move, and #326 changed both together.

## No wrapper, and what follows from it

There is no `gradlew` and no `gradle/wrapper/`: a wrapper is a jar, and this repository does
not track binaries it cannot review. `just android` says how to install Gradle when it is
absent, the way `scripts/test-android.sh` does for a system image.

Two consequences. Continuous integration installs Gradle and invokes the one on `PATH`, and it
names the full release, `9.7.0`, because `9.7` is not a version `setup-gradle` resolves. That
action's wrapper validation stays on and passes over nothing, which is the right way round: a
wrapper jar arriving later should be unchecked as well as unwanted.

And the toolchain is pinned by prose rather than by a properties file, so `settings.gradle.kts`
carries the reason each version is what it is: AGP 9 because Gradle 9.6 removed an internal
AGP 8.13 used, and no `org.jetbrains.kotlin.android` because AGP 9 bundles Kotlin and applying
it is an error rather than a duplicate.

## The core is built differently here than for iOS

`scripts/build-android-core.sh` builds `--no-default-features --features git,memory,android`
and passes `--crate-type cdylib` on the command line. The crate type is not in `Cargo.toml`
deliberately: putting it there makes *every* target build a dylib, and the iOS slices fail to
link one.

## Saying what ran

`just android-device-test` finds an emulator called `wattrouter-tests` and creates it from
`system-images;android-35;google_apis;arm64-v8a` if there is none. `WATTROUTER_AVD` picks a
different one.

Three things it learned the hard way, so you do not have to. It waits on
`getprop sys.boot_completed` rather than on `adb devices`, because a shutting-down emulator is
still listed and the script would decline to boot one and hand Gradle nothing. It stops an
emulator only if it started it. And it calls the SDK's own `cmdline-tools/latest/bin/avdmanager`
by full path, because `avdmanager` infers its SDK root from where it lives, and a Homebrew copy
cannot see system images installed under `~/Library/Android/sdk`.

Name the emulator and the recipe in the pull request. "Seven instrumented tests on
`system-images;android-35;google_apis;arm64-v8a`" is a claim a reviewer can weigh; "tests pass"
is not, and here it is ambiguous between three suites that check different things.

Continuous integration runs `gradle test` and nothing else Android. It cannot run the
instrumented suites: the image is `arm64-v8a` and the runner is x86_64 with no KVM for it. So
the only suites that can load the library are ones a person runs, and saying which is not a
courtesy.
