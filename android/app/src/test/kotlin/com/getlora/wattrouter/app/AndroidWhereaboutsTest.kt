// AndroidWhereaboutsTest.kt — no accuracy is not perfect accuracy.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. The one decision in the conformance that is not a platform call:
// Location.accuracy holds 0 when hasAccuracy() is false, and 0 read as a radius
// is a claim to know exactly where somebody is.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.LocationTool
import com.getlora.wattrouter.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWhereaboutsTest {
    @Test
    fun aReportedRadiusIsKept() {
        assertEquals(40f, radiusOf(has = true, reported = 40f), 0f)
    }

    @Test
    fun noRadiusBecomesAWideOneRatherThanNone() {
        assertEquals(UNKNOWN_RADIUS, radiusOf(has = false, reported = 0f), 0f)
        // Zero with hasAccuracy true is the same claim by another route, and
        // some providers report it.
        assertEquals(UNKNOWN_RADIUS, radiusOf(has = true, reported = 0f), 0f)
    }

    @Test
    fun aFixWithNoRadiusIsNotRenderedAsADoorstep() {
        // The end the choice of number is for. Left at 0 this reads
        // "53.4808, -2.2426 (within 0 m)" — a doorstep asserted from nothing.
        val said = LocationTool.describe(
            Place(53.480_759, -2.242_631, radiusOf(has = false, reported = 0f), at = 0),
            now = 0,
        )

        assertEquals(1, LocationTool.decimalsFor(UNKNOWN_RADIUS))
        assertTrue(said, said.startsWith("53.5, -2.2 "))
    }
}
