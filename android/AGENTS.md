# Working in `android/`

The routing core as an Android library. Read the root `AGENTS.md` first; this holds what is
true only here.

There is no app yet — this subtree is a library an app will link, and everything below is
about the two things that make it unusual: a test recipe that passes without running, and a
Kotlin file that a Rust test reads by path.

## Layout

`src/main/kotlin/com/getlora/wattrouter/` is the library. `Core.kt` is the core as Kotlin sees
it — `open`, `decide`, `close` over four JNI entry points. `Conversation.kt` is the state a
turn accumulates and the request body it becomes, encoded by hand with `buildJsonObject`
rather than by `@Serializable`: there is no serialization compiler plugin here, and adding one
to encode four message shapes is a dependency for something a file already does.

`src/test/kotlin/` runs on the JVM. `src/androidTest/kotlin/` runs on a device. The difference
is the whole of the next section.

`jniLibs/` holds `arm64-v8a/libwattrouter.so`, which `just android-core` builds from the same
crate the iOS xcframework comes from. It is build output, it is gitignored, and an AAR
assembled without it installs and fails to load.

`src/main/AndroidManifest.xml` is empty on purpose. A library that asks for no permission and
contributes no component has nothing to declare, and the accessibility, overlay and
foreground-service entries belong to the app that will hold them.

## The two test recipes, and what each may claim

`just android-test` runs on the JVM. It covers everything that touches nothing Android, which
today is `Conversation.kt` — the request a turn becomes. It never loads the library.

`just android-device-test` boots an emulator and is **the only recipe that can say the binding
works**. The `.so` is built for `aarch64-linux-android` and will not load on the host at all,
so no amount of JVM testing reaches `Core.kt`. Before #314 every check on the binding was a
string comparison between two files; the first run on a device found a real serialization bug
in the core within six tests.

So: a pull request touching `Core.kt` or `jni.rs` and claiming only `just android-test` has
claimed nothing about the change it made.

## `gradle test` passes without running

Gradle reports a cached test task as `UP-TO-DATE` and exits zero having run nothing, which on
a terminal reads exactly like a pass. `just android-test` passes `--rerun-tasks` for that
reason and it is not optional — anywhere, CI included. When counting tests, read the result
XML under `build/test-results/`, not the console.

## The symbol contract is checked from Rust

`router/src/jni.rs` holds a test that reads this directory's `Core.kt` **by path** through
`include_str!` and asserts that every `Java_com_getlora_wattrouter_Core_*` function it exports
has a matching `external fun` on the Kotlin side. That is what keeps a renamed native method
from becoming an `UnsatisfiedLinkError` on a device instead of an error at build.

It also means the source layout is load-bearing. Moving `Core.kt` edits `jni.rs` in the same
commit, or the test fails on a path rather than on a symbol and says nothing useful about why.

## No wrapper, and what follows from it

There is no `gradlew` and no `gradle/wrapper/`: a wrapper is a jar, and this repository does
not track binaries it cannot review. `just android` says how to install Gradle when it is
absent, the way `scripts/test-ios.sh` does for xcodegen.

Two consequences. Continuous integration must install Gradle and invoke the one on `PATH`;
`gradle/actions/setup-gradle` cannot validate a wrapper that does not exist. And the toolchain
is pinned by prose rather than by a file, so `android/build.gradle.kts` carries the reason each
version is what it is — AGP 9 because Gradle 9.6 removed an internal AGP 8.13 used, and no
`org.jetbrains.kotlin.android` because AGP 9 bundles Kotlin and applying it is an error rather
than a duplicate.

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
by full path, because `avdmanager` infers its SDK root from where it lives — a Homebrew copy
cannot see system images installed under `~/Library/Android/sdk`.

Name the emulator and the recipe in the pull request. "Six instrumented tests on
`system-images;android-35;google_apis;arm64-v8a`" is a claim a reviewer can weigh; "tests pass"
is not, and here it is ambiguous between two suites that check different things.
