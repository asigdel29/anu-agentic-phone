// ShowingTest.kt — which way the agent shows itself, and on which phones.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM: an API level and a boolean in, an answer out. The case worth
// having is the newest one, where the permission is not declared at all and so
// its absence must not be read as a refusal.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowingTest {
    @Test
    fun aNewPhoneNeedsNothingGranted() {
        assertEquals(Showing.ATTACHED, showingOn(ATTACHABLE, allowed = true))
        assertEquals(Showing.ATTACHED, showingOn(36, allowed = true))
    }

    @Test
    fun andItsAnswerDoesNotDependOnAPermissionItDoesNotHave() {
        // The manifest declares SYSTEM_ALERT_WINDOW with maxSdkVersion, so on
        // 34 and later canDrawOverlays is false because there is nothing to
        // grant. Reading that as a refusal would leave the newest phones the
        // only ones that never show an overlay.
        assertEquals(Showing.ATTACHED, showingOn(ATTACHABLE, allowed = false))
        assertEquals(Showing.ATTACHED, showingOn(35, allowed = false))
    }

    @Test
    fun anOlderPhoneDrawsOverEverythingOnceItMay() {
        assertEquals(Showing.OVER_EVERYTHING, showingOn(ATTACHABLE - 1, allowed = true))
        assertEquals(Showing.OVER_EVERYTHING, showingOn(29, allowed = true))
    }

    @Test
    fun anOlderPhoneWithoutItIsNotYetRatherThanNever() {
        // The caller has somewhere to send the person. A silent no would leave
        // the agent driving invisibly, which is what an overlay exists to stop.
        assertEquals(Showing.NOT_YET, showingOn(ATTACHABLE - 1, allowed = false))
        assertEquals(Showing.NOT_YET, showingOn(29, allowed = false))
    }
}
