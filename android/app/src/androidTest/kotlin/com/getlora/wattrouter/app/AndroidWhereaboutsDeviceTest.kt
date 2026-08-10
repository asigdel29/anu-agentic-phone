// AndroidWhereaboutsDeviceTest.kt: coarse permission is enough for this call.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// The claim the JVM cannot make, and the one this conformance could plausibly
// get wrong: asking the wrong provider with only ACCESS_COARSE_LOCATION throws
// a SecurityException out of the framework, which on a phone arrives as a turn
// that died rather than as a sentence. Whether a fix comes back is a different
// question and not one an emulator with no location set can answer.

package com.getlora.wattrouter.app

import android.Manifest
import android.location.LocationManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class AndroidWhereaboutsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun allow() {
        // Coarse only, which is what AndroidAsking asks for. Granting fine here
        // would test a permission the app never holds.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test
    fun theLocationManagerIsReachable() {
        assertNotNull(context.getSystemService(LocationManager::class.java))
    }

    @Test
    fun askingWithCoarsePermissionAnswersRatherThanThrowing() {
        // Null is a pass. A bare emulator has no location set, so the honest
        // claim is that the call completes: the provider chosen is one coarse
        // permission may read, the callback resolves the coroutine, and nothing
        // in the framework objected. The timeout is the assertion that it
        // resolves at all: getCurrentLocation that never calls back would
        // otherwise hang this suite rather than fail it.
        //
        // A block rather than an expression body: `= runBlocking { … }` returns
        // the fix, and JUnit rejects a test method that returns anything.
        runBlocking {
            withTimeout(30_000) { AndroidWhereabouts(context).current() }
        }
    }
}
