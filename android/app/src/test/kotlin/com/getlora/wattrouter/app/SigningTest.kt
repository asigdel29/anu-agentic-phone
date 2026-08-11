// SigningTest.kt: what a saved identity reads back as, and what the row says.
//
// History
//   2026-08-11  A. Sigdel  Created with #636.
//
// On the JVM, in ModesTest's shape and for its reason. The store itself is
// SharedPreferences and needs a device; what does not is the decision either
// side of it, which here is when two saved strings amount to a person and how
// that person is shown back.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningTest {

    @Test
    fun nothingSavedIsNobody() {
        // There is no sensible default to have instead. Everything this phone
        // could reach is a guess at a person's name, and a guess is a
        // fabricated author in a history that outlives the phone.
        assertNull(whoFrom(null, null))
    }

    @Test
    fun halfAnIdentityIsNobodyRatherThanHalfAPerson() {
        // The core refuses a half-filled identity anyway. Answering a Who here
        // would send a commit all the way down to be refused for something
        // this already knew.
        assertNull(whoFrom("Ada", null))
        assertNull(whoFrom(null, "ada@example.com"))
        assertNull(whoFrom("Ada", "   "))
        assertNull(whoFrom("", "ada@example.com"))
    }

    @Test
    fun whatWasTypedIsTrimmedOnTheWayIn() {
        // A name pasted with a trailing space is in every commit forever
        // otherwise, and it is not a distinction anybody meant to draw. Trimmed
        // here as well as in the core, because this is what decides whether
        // there is anybody at all and " " is not somebody.
        assertEquals(
            Who("Ada", "ada@example.com"),
            whoFrom("  Ada  ", " ada@example.com "),
        )
    }

    @Test
    fun anIdentityIsShownTheWayGitWillWriteIt() {
        // Name <address>, which is what appears in the log. Somebody checking
        // it should be checking the thing itself rather than a rendering of it.
        assertTrue(
            signature(Who("Ada", "ada@example.com")).contains("Ada <ada@example.com>"),
        )
    }

    @Test
    fun nobodySaysSoAndSaysWhatItCosts() {
        // Not just "unsigned". A line stating a fact nobody can act on is one
        // people stop reading, and the fact worth carrying is that a commit
        // will be refused until somebody answers it.
        val said = signature(null)

        assertTrue(said, said.contains("unsigned"))
        assertTrue(said, said.contains("refused"))
    }
}
