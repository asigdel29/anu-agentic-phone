// StartupTest.kt — the three states a cold launch can be in.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On a device: every case reaches Core.open, which needs the library.
//
// No case here signs in against the provider. The core does not call out at
// open, so a well-formed key proves only that a non-empty string crossed — and
// a key the provider would accept is not something a test can hold.

package com.getlora.wattrouter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun leaveNothingStored() {
        Credential(context).forget()
    }

    @Test
    fun aFreshInstallIsAskedToSignIn() {
        assertEquals(Startup.NoCredential, Startup.from(null))
    }

    @Test
    fun andSoIsOneWhoseStoredKeyHasGone() {
        // The keystore drops its entries when a screen lock is removed on some
        // devices, so `read` answering null is a state a real phone reaches
        // rather than only a fresh install.
        assertEquals(Startup.NoCredential, Startup.begin(context))
    }

    @Test
    fun aKeyTheCoreTakesGivesACoreToClose() {
        val begun = Startup.from("nw-well-formed")

        assertTrue("expected Ready, got $begun", begun is Startup.Ready)
        (begun as Startup.Ready).core.close()
    }

    @Test
    fun aRefusalIsNotTheSameAsHavingNoKey() {
        // The distinction the whole file exists for. Both answer null from
        // Core.open, and folding them together sends somebody who has already
        // signed in back to the sign-in screen to type the same key again.
        assertEquals(Startup.CoreRefused, Startup.from(""))
    }

    @Test
    fun aStoredKeyIsTheOneThatIsUsed() {
        // Otherwise `begin` could pass every case above by ignoring storage
        // entirely and always answering NoCredential.
        Credential(context).store("nw-from-disk")

        val begun = Startup.begin(context)
        assertTrue("expected Ready, got $begun", begun is Startup.Ready)
        (begun as Startup.Ready).core.close()
    }
}
