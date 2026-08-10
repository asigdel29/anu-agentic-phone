// AndroidContactsTest.kt: a name that is not a wildcard.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM, because the pattern is a string function and a provider would add
// nothing to it. What matters is the one case that fails open: a name of "%"
// looks up everybody, which is the single answer Contacts.kt exists to prevent.

package com.getlora.wattrouter.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidContactsTest {
    @Test
    fun anOrdinaryNameIsWrappedAndNothingElse() {
        assertEquals("%Ada%", asPattern("Ada"))
    }

    @Test
    fun aWildcardIsSomebodysName() {
        // Unescaped, the first of these matches every contact on the phone.
        assertEquals("%\\%%", asPattern("%"))
        assertEquals("%50\\%%", asPattern("50%"))
        assertEquals("%a\\_b%", asPattern("a_b"))
    }

    @Test
    fun theEscapeCharacterIsEscapedFirst() {
        // Done last, the backslashes added for % and _ would themselves be
        // escaped, and the pattern would look for a literal backslash where it
        // meant to hide one.
        assertEquals("%a\\\\b%", asPattern("a\\b"))
        assertEquals("%\\\\\\%%", asPattern("\\%"))
    }
}
