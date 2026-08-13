// ReachingDeviceTest.kt: the key survives being sealed, and is made once.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// On a device for KeystoreTest's reason: AndroidKeyStore has no host
// implementation, so the sealing half of Reaching cannot be reached from the
// JVM at all. The making and the encoding are SshTest's, on the JVM, because
// nothing in Ssh.kt touches the platform.
//
// Its own preferences file per case, as KeystoreTest uses its own alias per
// case: a test that forgets a key must not be able to break one mid-way
// through using it.
//
// The one to read first is that nothing partial is written. A public half
// stored without its private half would show somebody a key to authorise that
// this phone could never use, and a forge holding it would be the only place
// the mistake was visible.

package com.getlora.wattrouter

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<String>()

    private fun reaching(what: String): Reaching {
        val file = "wattrouter-test-reaching-$what"
        files += file
        return Reaching(context.getSharedPreferences(file, Context.MODE_PRIVATE))
    }

    @After
    fun clear() {
        files.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun aPhoneWithNoKeyHasNothingToShow() {
        val store = reaching("empty")

        assertEquals(false, store.isMade)
        assertNull(store.shown)
        assertNull(store.secret())
    }

    @Test
    fun whatWasMadeIsWhatIsShownAndWhatIsSealed() {
        val store = reaching("round-trip")

        val shown = store.ensure()

        assertNotNull("no key was made", shown)
        assertTrue(shown!!, shown.startsWith("ssh-rsa "))
        assertEquals(shown, store.shown)
        assertEquals(true, store.isMade)
        assertTrue(store.secret()!!, store.secret()!!.startsWith("-----BEGIN PRIVATE KEY-----"))
        store.forget()
    }

    @Test
    fun askingTwiceDoesNotMakeASecondKey() {
        // A forge holds the half it was given. A second key would make every
        // push fail with a key the phone believes is authorised.
        val store = reaching("once")

        val first = store.ensure()
        val second = store.ensure()

        assertEquals(first, second)
        store.forget()
    }

    @Test
    fun forgettingLeavesNothingBehindThatReadsAsAKey() {
        val store = reaching("forget")
        store.ensure()

        store.forget()

        assertEquals(false, store.isMade)
        assertNull(store.shown)
        assertNull(store.secret())
    }

    @Test
    fun theSealedHalfIsNotThePlaintext() {
        // Reaching's own version of KeystoreTest's central case, because this
        // is the file that decides which alias seals what.
        val store = reaching("opaque")
        val shown = store.ensure()!!
        val raw = context
            .getSharedPreferences(files.last(), Context.MODE_PRIVATE)
            .getString("ssh-private-key", null)

        assertNotNull("nothing was stored", raw)
        assertTrue(raw!!, !raw.contains("BEGIN PRIVATE KEY"))
        // The public half is stored as itself, which is deliberate: showing it
        // should not have the failure modes of opening a secret.
        assertEquals(shown, store.shown)
        store.forget()
    }
}
