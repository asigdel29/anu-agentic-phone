// PackagingTest.kt: the shared object survives the trip into the APK.
//
// History
//   2026-08-08  A. Sigdel  Created with the app module.
//
// The core module's own suite already proves the library loads. This is the
// different claim, and the one the split can break: the `.so` goes from
// core/jniLibs into the AAR and out again into lib/arm64-v8a of this APK. An
// abiFilter disagreeing across the two modules, or a jniLibs path left pointing
// at the old location, builds and installs and dies at launch.

package com.getlora.wattrouter.app

import org.junit.Assert.assertTrue
import org.junit.Test

class PackagingTest {
    @Test
    fun theCoreLibraryIsInThisApk() {
        assertTrue("the core did not load from the app's APK", coreLoads())
    }
}
