// build.gradle.kts — the app the core is reached through.
//
// History
//   2026-08-08  A. Sigdel  Created, empty of everything but a screen. The
//                          credential, the turn loop and the tools follow.
//   2026-08-09  A. Sigdel  A release build, and the one R8 rule without which
//                          it installs and dies at the first native call.
//
// The two SDK levels below are decisions, not defaults, and both are the kind
// somebody tidies up. Each carries its reason at the line.

plugins {
    id("com.android.application")

    // Required whenever `compose = true`, and separate from the Kotlin AGP 9
    // bundles. Version in settings.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.getlora.wattrouter.app"
    // 37 because Compose 1.12 and later require it. Two properties for one
    // platform: Android ships minor platform releases now and the SDK installs
    // this one as `android-37.1`, so `compileSdk = 37` alone fails with "Failed
    // to find target with hash string 'android-37'" while the directory sits
    // there.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.getlora.wattrouter"
        minSdk = 29

        // 35 while the phone-driving layer is built. Targeting 36 opts into
        // Android 16's enforced edge-to-edge, which cannot be turned off, and
        // that puts node bounds in *driven* apps under the system bars — a tap
        // at an element's centre can land on the navigation bar. Nothing forces
        // the bump: this app is sideloaded and has no store deadline.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // arm64-v8a only, matching the core. A second ABI without a second .so
        // is an APK that installs and cannot load.
        ndk { abiFilters += "arm64-v8a" }
    }

    // From the environment, and the keystore is never tracked. A repository
    // that will not track a Gradle wrapper jar is not going to track a signing
    // key. Absent the environment there is no config, the release build
    // assembles unsigned, and `just android-release` says which happened.
    val store = System.getenv("WATTROUTER_KEYSTORE")
    if (store != null) {
        signingConfigs {
            create("release") {
                storeFile = file(store)
                storePassword = System.getenv("WATTROUTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WATTROUTER_KEY_ALIAS")
                keyPassword = System.getenv("WATTROUTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // On, which is the point of this build type: it is what makes the
            // JNI keep rule in proguard-rules.pro load-bearing rather than
            // decorative.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    // One version for every Compose artefact, which is what the bill of
    // materials is for: the compiler and the runtime disagreeing is a class of
    // failure that reports itself as a missing method at runtime.
    val compose = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(compose)
    androidTestImplementation(compose)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")



    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
