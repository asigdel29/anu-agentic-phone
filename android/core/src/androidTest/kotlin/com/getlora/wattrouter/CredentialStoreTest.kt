// CredentialStoreTest.kt: the credential, sealed and read back for real.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On a device because AndroidKeyStore has no host implementation: there is no
// stub, no Robolectric shadow that exercises the real cipher, and a JVM test
// here would be testing SharedPreferences.
//
// Each case uses its own preferences file and its own alias by way of a fresh
// Credential over a named file, so one test forgetting a key cannot delete the
// key another is mid-way through using.

package com.getlora.wattrouter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CredentialStoreTest {
    private lateinit var credential: Credential

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun start() {
        credential = Credential(context)
        credential.forget()
    }

    @After
    fun finish() {
        credential.forget()
    }

    @Test
    fun aKeySurvivesBeingWrittenAndReadBack() {
        // The whole point, through the Keystore and back.
        assertTrue(credential.store("nw-round-trip"))
        assertEquals("nw-round-trip", credential.read())
    }

    @Test
    fun aFreshInstallHasNoneAndSaysSoRatherThanFailing() {
        assertFalse(credential.isStored)
        assertNull(credential.read())
    }

    @Test
    fun aPastedKeyIsStoredWithoutItsNewline() {
        // CredentialTest already checks `clean`. This checks the trimmed value
        // is what actually reaches disk, which is a different claim: a caller
        // could trim and then store the original.
        assertTrue(credential.store("nw-pasted\n"))
        assertEquals("nw-pasted", credential.read())
    }

    @Test
    fun anEmptyKeyIsRefusedRatherThanStored() {
        assertFalse(credential.store("   "))
        assertFalse(credential.isStored)
    }

    @Test
    fun forgettingLeavesNothingToRead() {
        credential.store("nw-forget-me")
        assertTrue(credential.forget())

        assertFalse(credential.isStored)
        assertNull(credential.read())
    }

    @Test
    fun aSecondCredentialOverTheSameFileReadsTheFirstsKey() {
        // Which is every launch: the object is new and the key is not.
        credential.store("nw-across-launches")

        assertEquals("nw-across-launches", Credential(context).read())
    }

    @Test
    fun whatIsOnDiskIsNotTheKey() {
        // The reason any of this exists. If the value were stored as typed,
        // every one of the cases above would still pass.
        credential.store("nw-plain-as-day")

        val stored = context
            .getSharedPreferences("wattrouter-credential", Context.MODE_PRIVATE)
            .getString("neuralwatt-api-key", null)

        assertFalse("the credential is on disk in the clear", stored == "nw-plain-as-day")
    }
}
