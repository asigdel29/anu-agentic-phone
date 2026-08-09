// AndroidCalendarsTest.kt — the day an all-day event is actually on.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM, because the conversion is arithmetic and a provider would add
// nothing to it. That the provider answers at all is the device test's claim.

package com.getlora.wattrouter.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCalendarsTest {
    // A date with no time, as the provider stores one: midnight UTC.
    private val stored = Instant.parse("2026-08-11T00:00:00Z").toEpochMilli()

    @Test
    fun midnightMovesWestAndEast() {
        // New York is four hours behind in August, Tokyo nine ahead. Left
        // alone, the first would render as the evening of the 10th.
        assertEquals(
            Instant.parse("2026-08-11T04:00:00Z").epochSecond,
            allDayAt(stored, ZoneId.of("America/New_York")),
        )
        assertEquals(
            Instant.parse("2026-08-10T15:00:00Z").epochSecond,
            allDayAt(stored, ZoneId.of("Asia/Tokyo")),
        )
    }

    @Test
    fun theDateIsWhatSurvives() {
        // The property the arithmetic exists for, over the widest offsets there
        // are: whatever the phone's zone, rendering the answer in it gives back
        // the date somebody wrote down.
        listOf("America/New_York", "Asia/Tokyo", "UTC", "Pacific/Kiritimati", "Pacific/Niue")
            .forEach { name ->
                val zone = ZoneId.of(name)

                val rendered = Instant.ofEpochSecond(allDayAt(stored, zone))
                    .atZone(zone)
                    .toLocalDate()

                assertEquals(name, LocalDate.of(2026, 8, 11), rendered)
            }
    }
}
