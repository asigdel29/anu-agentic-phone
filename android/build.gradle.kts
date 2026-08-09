// build.gradle.kts — the core, as an Android library.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// What this module is: `Core.kt` and the shared object it declares. The library
// itself is built by `just android-core` from the same crate iOS links, and is
// not built by Gradle — cargo already knows how, and an NDK build driven from
// here would be a second way to produce one artefact.
//
// So `jniLibs.srcDirs` points at what that script wrote. A build with nothing
// there produces an AAR that compiles and fails to load, which is why
// `just android` runs the core build first.
//
// The source layout is the repository's rather than Gradle's default: Core.kt
// was written before this file existed and moving it would break the parity test
// in router/src/jni.rs, which reads it by path. Pointing the source set here is
// one line; the alternative is a moved file and a changed include_str!.

plugins {
    // AGP 9 brings Kotlin support with it, and applying
    // org.jetbrains.kotlin.android alongside is an error rather than a
    // duplicate. AGP 9 rather than 8: Homebrew's Gradle is 9.7, and AGP 8.13
    // relies on a Gradle internal removed in 9.6.
    id("com.android.library") version "9.0.0"
}

android {
    namespace = "com.getlora.wattrouter"
    compileSdk = 35

    defaultConfig {
        // 29 rather than lower, and #229 is why: since API 29 an app cannot
        // exec a file it wrote into its own data directory, which decides how
        // the terminal in #233's item 5 is built. Supporting a phone where that
        // is not true is supporting a different app.
        minSdk = 29

        // arm64-v8a only, matching scripts/build-android-core.sh. A second ABI
        // here without a second .so is an AAR that installs and cannot load.
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["main"].jniLibs.srcDirs("jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


