// KeystoreTest.kt: sealing and opening, against the real keystore.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On a device because AndroidKeyStore has no host implementation: there is no
// stub and no shadow that runs the real cipher, so a JVM test here would be
// testing Base64.
//
// Each case uses its own alias. Sharing one would make a test that discards a
// key able to break a test that is mid-way through using it, and the failure
// would land in whichever ran second.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeystoreTest {
    private fun alias(what: String) = "wattrouter-test-$what"

    @Test
    fun whatWasSealedOpens() {
        val a = alias("round-trip")
        val sealed = Keystore.seal(a, "nw-secret")

        assertNotNull("sealing failed", sealed)
        assertEquals("nw-secret", Keystore.open(a, sealed!!))
        Keystore.discard(a)
    }

    @Test
    fun theSealedFormIsNotThePlaintext() {
        // The reason this file exists. Without it every other case here would
        // pass over an implementation that stored the string as typed.
        val a = alias("opaque")
        val sealed = Keystore.seal(a, "nw-plain-as-day")

        assertNotEquals("nw-plain-as-day", sealed)
        Keystore.discard(a)
    }

    @Test
    fun sealingTheSameStringTwiceGivesTwoDifferentCiphertexts() {
        // GCM needs a distinct nonce per encryption under one key, and the
        // provider supplies it. Equal ciphertexts would mean a fixed nonce,
        // which is the failure mode that makes GCM catastrophic rather than
        // merely weak, and it would be invisible in a round-trip test.
        val a = alias("nonce")
        val once = Keystore.seal(a, "nw-same")
        val twice = Keystore.seal(a, "nw-same")

        assertNotEquals(once, twice)
        assertEquals("nw-same", Keystore.open(a, twice!!))
        Keystore.discard(a)
    }

    @Test
    fun openingSomethingThatIsNotSealedGivesNothingRatherThanThrowing() {
        // A caller reading a corrupted preference should see "no credential",
        // not an exception out of a getter.
        assertNull(Keystore.open(alias("absent"), "not base64 at all"))
    }

    @Test
    fun discardingTheKeyLeavesTheCiphertextUnreadable() {
        // Which is what makes `forget` mean something: the bytes may survive in
        // a backup or an old preferences file, and without the key they are
        // noise.
        val a = alias("discard")
        val sealed = Keystore.seal(a, "nw-forget-me")!!
        Keystore.discard(a)

        assertNull(Keystore.open(a, sealed))
    }
}
