// AndroidAskingTest.kt — the two answers that mean the same thing.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM, over the pure reading of what the system said. The calls that
// produce those answers need a device and are checked there; what needs checking
// here is the table, because its whole reason for existing is that
// shouldShowRequestPermissionRationale answers false in two opposite situations.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.Capability
import com.getlora.wattrouter.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndroidAskingTest {
    private fun read(
        present: Boolean = true,
        granted: Boolean = false,
        everAsked: Boolean = false,
        rationale: Boolean = false,
    ) = stateFrom(present, granted, everAsked, rationale)

    @Test
    fun noRationaleMeansUnaskedBeforeAndDeniedAfter() {
        // The whole reason this app keeps a record of having asked. Android
        // gives one signal for two opposite situations, and a caller that read
        // it alone would either nag somebody the system has stopped asking for
        // or give up on somebody nobody has asked yet.
        assertEquals(PermissionState.UNASKED, read(everAsked = false, rationale = false))
        assertEquals(
            PermissionState.PERMANENTLY_DENIED,
            read(everAsked = true, rationale = false),
        )
    }

    @Test
    fun rationaleIsTheSystemSayingItWillStillAsk() {
        assertEquals(PermissionState.REFUSED, read(everAsked = true, rationale = true))
    }

    @Test
    fun grantedOutranksTheRecord() {
        // The record is cleared on a grant, but a stale one must not turn a
        // permission somebody holds into a refusal.
        assertEquals(
            PermissionState.GRANTED,
            read(granted = true, everAsked = true, rationale = false),
        )
    }

    @Test
    fun somethingAbsentIsNotSomethingHeld() {
        // Before granted: a permission held on a phone with nothing to use it
        // on is still nothing to offer, and Unavailable is the case whose prose
        // says carry on rather than pointing at Settings.
        assertEquals(PermissionState.UNAVAILABLE, read(present = false, granted = true))
    }

    @Test
    fun everyCapabilityIsSpelledAsADistinctPermission() {
        // Two capabilities sharing a permission would coalesce in the system
        // and not in Permission, which asks per capability: the second would
        // report a state the first had just set and never show a dialog.
        val spelled = Capability.entries.map(::permissionFor)

        assertEquals(spelled.toString(), Capability.entries.size, spelled.toSet().size)
        spelled.forEach { assertNotEquals("", it) }
    }
}
