// ReadinessTest.kt: the list, and the order it puts things in.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. The case worth having is the first row: somebody sent to the
// accessibility switch before being told it is greyed out has been sent to a
// dead end by the screen that was meant to help.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    private fun state(
        driving: Boolean = true,
        notifying: Boolean = true,
        calendar: Boolean = true,
        contacts: Boolean = true,
        location: Boolean = true,
        sideloaded: Boolean = true,
    ) = Readiness.of(driving, notifying, calendar, contacts, location, sideloaded)

    @Test
    fun restrictedSettingsComesBeforeTheSwitchItUnblocks() {
        // Sent to the accessibility screen first, somebody finds the toggle
        // greyed and no explanation anywhere: a dead end handed out by the
        // screen that exists to prevent one.
        val first = state(driving = false).steps.first()

        assertTrue(first.what, first.what.contains("restricted settings"))
        assertTrue(first.where, first.where.contains("Allow restricted settings"))
        assertEquals(first, state(driving = false).next)
    }

    @Test
    fun andItGoesAwayOnceTheServiceIsOn() {
        // It is only ever a blocker: switched on, the step it unblocked is
        // done, and leaving it on the list is a row nobody can act on.
        assertTrue(state(driving = true).steps.none { it.what.contains("restricted") })
    }

    @Test
    fun aStoreInstallNeverSeesIt() {
        // A step nobody needs makes the other four look optional too.
        assertTrue(
            state(driving = false, sideloaded = false).steps.none {
                it.what.contains("restricted")
            },
        )
    }

    @Test
    fun theServiceIsRequiredAndTheCalendarIsNot() {
        // Otherwise five amber rows read as five equal problems, and the one
        // that stops everything looks like the one about a diary.
        val steps = state().steps

        assertTrue(steps.first { it.what.contains("screen") }.isRequired)
        assertFalse(steps.first { it.what.contains("calendar") }.isRequired)
    }

    @Test
    fun drivingIsPossibleWithoutTheOptionalOnes() {
        assertTrue(state(calendar = false, contacts = false, location = false).canDrive)
        assertFalse(state(driving = false).canDrive)
    }

    @Test
    fun theRequiredOneIsWhatItAsksForFirst() {
        // Even when something optional is also off and comes earlier in the
        // list somebody is reading.
        val next = state(driving = false, notifying = false, calendar = false).next

        assertTrue("$next", next!!.isRequired)
    }

    @Test
    fun andWhenTheRequiredOnesAreDoneItAsksForTheRest() {
        val next = state(calendar = false).next

        assertTrue("$next", next!!.what.contains("calendar"))
        assertFalse("$next", next.isRequired)
    }

    @Test
    fun nothingLeftIsNothingToAskFor() {
        assertNull(state().next)
        assertTrue(state().canDrive)
    }

    @Test
    fun everyStepSaysExactlyWhereRatherThanSettings() {
        // "Settings" on its own is advice nobody can act on, which is the call
        // Capability already made for its own prose.
        state(driving = false).steps.forEach {
            assertTrue(it.where, it.where.startsWith("Settings > "))
            assertTrue(it.where, it.where.count { c -> c == '>' } >= 2)
        }
    }
}
