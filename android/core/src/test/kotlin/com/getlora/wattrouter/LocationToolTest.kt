// LocationToolTest.kt — how much of a fix to claim, and how old it is.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against a scripted Whereabouts. Whether hardware answers is the
// conformance's claim; both of the decisions are here.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Held(private val answer: PermissionState) : Asking {
    var dialogs = 0

    override suspend fun state(of: Capability) = answer

    override suspend fun request(capability: Capability) = answer.also { dialogs++ }
}

private class Fixed(private val place: Place?) : Whereabouts {
    override suspend fun current() = place
}

class LocationToolTest {
    private val manchester = Place(53.480_759, -2.242_631, accuracy = 8f, at = 1_000)

    private fun tool(place: Place?, asking: Held, now: Long = 1_000L) =
        LocationTool(Fixed(place), Permission(asking), { now })

    @Test
    fun aRefusalIsASentenceRatherThanAThrow() = runTest {
        val said = tool(manchester, Held(PermissionState.PERMANENTLY_DENIED)).run("{}")

        assertTrue(said, said.contains("Settings > Apps"))
    }

    @Test
    fun noFixNamesBothReasonsThereMightNotBeOne() = runTest {
        // A model told only "no location" cannot tell an indoor phone from one
        // where location is off for everything, and only the second has an
        // answer the person can act on.
        val said = tool(null, Held(PermissionState.GRANTED)).run("{}")

        assertTrue(said, said.contains("no signal"))
        assertTrue(said, said.contains("Settings > Location"))
    }

    @Test
    fun aGoodFixIsPrintedToWhatItSupports() = runTest {
        val said = tool(manchester, Held(PermissionState.GRANTED)).run("{}")

        assertEquals("53.4808, -2.2426 (within 8 m, taken just now)", said)
    }

    @Test
    fun aCoarseFixIsNotPrintedAsADoorstep() {
        // The decision this file exists for. Coarse is what AndroidAsking asks
        // for, so a two-kilometre circle is the normal case — rendered to six
        // places it reads as a street address and the model repeats it.
        assertEquals(1, LocationTool.decimalsFor(2_000f))
        assertEquals(2, LocationTool.decimalsFor(900f))
        assertEquals(3, LocationTool.decimalsFor(100f))
        assertEquals(4, LocationTool.decimalsFor(10f))

        assertEquals(
            "53.5, -2.2 (within 2000 m, taken just now)",
            LocationTool.describe(manchester.copy(accuracy = 2_000f), now = 1_000),
        )
    }

    @Test
    fun anOldFixSaysSoRatherThanPassingAsCurrent() {
        // Providers answer instantly with whatever they last cached, so this
        // can otherwise be a car park somebody left an hour ago.
        val minutesLater = LocationTool.describe(manchester, now = 1_000 + 5 * 60)
        assertTrue(minutesLater, minutesLater.endsWith("taken 5 minutes ago)"))

        val stale = LocationTool.describe(manchester, now = 1_000L + LocationTool.STALE + 60)
        assertTrue(stale, stale.contains("possibly out of date"))

        val old = LocationTool.describe(manchester, now = 1_000 + 3 * 3600)
        assertTrue(old, old.contains("3 hours ago, so probably not where they are now"))
    }

    @Test
    fun aFixFromTheFutureIsAClockRatherThanAPlace() {
        val said = LocationTool.describe(manchester, now = 900)

        assertTrue(said, said.contains("timed oddly"))
    }

    @Test
    fun theSchemaNamesAnObjectEvenWithNothingInIt() {
        // A provider handed a schema with no `properties` rejects the tool
        // rather than the call, and every tool goes with it.
        val box = ToolBox(listOf(LocationTool(Fixed(null), Permission(Held(PermissionState.GRANTED)))))

        assertTrue(box.definitions().contains("\"properties\":{}"))
    }
}
