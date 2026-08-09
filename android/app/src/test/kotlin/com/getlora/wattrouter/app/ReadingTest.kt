// ReadingTest.kt — the two rows that are not a permission check.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. Both are functions of a string, and both fail in a way that
// sends somebody to switch on something already on, or hides the one step
// without which nothing works.

package com.getlora.wattrouter.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {
    private val app = "com.getlora.wattrouter"
    private val service = "com.getlora.wattrouter.app.DrivingService"

    private fun enabled(list: String?) = isEnabled(list, app, service)

    @Test
    fun theFullyQualifiedSpellingIsRecognised() {
        assertTrue(enabled("$app/$service"))
    }

    @Test
    fun andSoIsTheAbbreviatedOne() {
        // The platform writes either. A phone using the short form would read
        // as off, and the screen would send somebody to switch on a thing they
        // had already switched on.
        assertTrue(enabled("$app/.app.DrivingService"))
    }

    @Test
    fun itIsFoundAmongOthers() {
        assertTrue(enabled("com.other/.Service:$app/$service:com.third/.S"))
        assertTrue(enabled("$app/$service:com.other/.Service"))
    }

    @Test
    fun somethingThatMerelyContainsOurNameIsNotUs() {
        // A plain `contains` would match this. Splitting on the separator and
        // comparing whole entries costs nothing and removes the question.
        assertFalse(enabled("com.example.$app/$service" + "Extra"))
        assertFalse(enabled("com.example.$app/$service.Extra"))
    }

    @Test
    fun nothingEnabledIsNotUs() {
        // Null is what the setting reads before anything has ever been
        // enabled, rather than empty.
        assertFalse(enabled(null))
        assertFalse(enabled(""))
        assertFalse(enabled("com.other/.Service"))
    }

    @Test
    fun anInstallNobodyVouchedForIsSideloaded() {
        // adb install and a file manager alike: restricted settings applies,
        // and the accessibility toggle is greyed until somebody clears it.
        assertTrue(isSideloaded(null))
        assertTrue(isSideloaded(""))
        assertTrue(isSideloaded("  "))
    }

    @Test
    fun anInstallFromAStoreIsNot() {
        // Getting this backwards shows a store install a step it cannot
        // perform, or hides from a sideloaded one the step without which
        // nothing works.
        assertFalse(isSideloaded("com.android.vending"))
        assertFalse(isSideloaded("org.fdroid.fdroid"))
    }
}
