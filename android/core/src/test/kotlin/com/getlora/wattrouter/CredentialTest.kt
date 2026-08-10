// CredentialTest.kt: what is worth storing from what somebody typed.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On the JVM, which is why `clean` is a separate function. Everything else in
// Credential needs AndroidKeyStore and so needs a device, and the decision with
// a bug in it should not be the one that only runs on an emulator.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialTest {
    @Test
    fun aPastedKeyLosesItsTrailingNewline() {
        // The case this function exists for. A key copied out of a terminal or
        // a password manager carries one, the provider answers 401, and nothing
        // in that answer mentions whitespace.
        assertEquals("nw-abc123", Credential.clean("nw-abc123\n"))
    }

    @Test
    fun soDoesOneWithSpacesAroundIt() {
        assertEquals("nw-abc123", Credential.clean("  nw-abc123  "))
    }

    @Test
    fun nothingTypedIsNothingStored() {
        assertNull(Credential.clean(""))
    }

    @Test
    fun whitespaceIsNothingTyped() {
        // Distinct from the case above only in that somebody has to have tried.
        // Storing it would produce a credential that exists and cannot work.
        assertNull(Credential.clean("   \n\t "))
    }

    @Test
    fun whatIsInsideIsLeftAlone() {
        // Only the ends are trimmed. A key is opaque and this must not decide
        // that some character in the middle of one is wrong.
        assertEquals("nw-a b-c", Credential.clean("  nw-a b-c\n"))
    }
}
