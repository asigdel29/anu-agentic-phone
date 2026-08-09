// settings.gradle.kts — the Android build, as one module.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// One module rather than the app/library pair a template produces. There is no
// app yet — what exists is the core and a binding over it — and a second module
// with nothing in it is a directory people have to be told to ignore.
//
// No Gradle wrapper. A wrapper is a jar, and this repository does not track
// binaries it cannot review; `just android` reports the Gradle it will use, the
// way scripts/test-ios.sh reports the xcodegen it needs.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wattrouter"
