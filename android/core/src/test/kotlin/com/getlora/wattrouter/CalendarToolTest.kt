// CalendarToolTest.kt — the order the prompt is asked in, and what is read back.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against a scripted diary and a scripted Asking. The two things that
// need a real calendar — that a provider answers, and that its columns mean what
// they are read as — are the conformance's to prove on a device.

package com.getlora.wattrouter

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Answers(private val answer: PermissionState) : Asking {
    var dialogs = 0

    override suspend fun state(of: Capability) = answer

    override suspend fun request(capability: Capability) = answer.also { dialogs++ }
}

private class Diary(private val events: List<Event> = emptyList()) : Calendars {
    var window: Pair<Long, Long>? = null

    override suspend fun between(from: Long, to: Long): List<Event> {
        window = from to to
        return events
    }
}

private fun at(text: String) = Instant.parse(text).epochSecond

private val utc = ZoneId.of("UTC")

class CalendarToolTest {
    private fun tool(
        diary: Diary,
        asking: Answers,
        zone: ZoneId = utc,
        now: Long = at("2026-08-09T12:00:00Z"),
    ) = CalendarTool(diary, Permission(asking), { zone }, { now })

    @Test
    fun aMalformedCallDoesNotSpendAPrompt() = runTest {
        // The convention every tool after this one inherits: a dialog spent on
        // a call that could not have worked teaches somebody to refuse.
        val diary = Diary()
        val asking = Answers(PermissionState.UNASKED)

        val said = tool(diary, asking).run("""{"start_day":0,"days":99}""")

        assertTrue(said, said.contains("days must be between 1 and 14"))
        assertEquals(0, asking.dialogs)
        assertNull("the calendar should not have been read either", diary.window)
    }

    @Test
    fun argumentsThatAreNotThereAreAnsweredInWords() = runTest {
        val said = tool(Diary(), Answers(PermissionState.GRANTED)).run("not json at all")

        assertTrue(said, said.contains("start_day"))
    }

    @Test
    fun aRefusalIsASentenceRatherThanAThrow() = runTest {
        // ToolBox would otherwise render this as "read_calendar failed … its
        // arguments must match this schema", blaming them for a person's answer.
        val said = tool(Diary(), Answers(PermissionState.PERMANENTLY_DENIED))
            .run("""{"start_day":0,"days":1}""")

        assertTrue(said, said.contains("Settings > Apps"))
    }

    @Test
    fun theWindowIsWholeDaysWhereThePhoneIs() = runTest {
        // London is an hour ahead of UTC in August, so tomorrow starts at 23:00
        // today. A model filling in epoch seconds would have missed that.
        val diary = Diary()

        tool(diary, Answers(PermissionState.GRANTED), zone = ZoneId.of("Europe/London"))
            .run("""{"start_day":1,"days":2}""")

        assertEquals(
            at("2026-08-09T23:00:00Z") to at("2026-08-11T23:00:00Z"),
            diary.window,
        )
    }

    @Test
    fun aDayIsReadFromItsOwnMidnight() = runTest {
        val diary = Diary()

        tool(diary, Answers(PermissionState.GRANTED)).run("""{"start_day":0,"days":1}""")

        assertEquals(at("2026-08-09T00:00:00Z") to at("2026-08-10T00:00:00Z"), diary.window)
    }

    @Test
    fun anEventKeepsItsDayItsHoursAndItsPlace() {
        val said = CalendarTool.describe(
            listOf(
                Event(
                    "Standup",
                    start = at("2026-08-10T08:00:00Z"),
                    end = at("2026-08-10T08:15:00Z"),
                    location = "Kitchen",
                ),
                Event(
                    "Bank holiday",
                    start = at("2026-08-11T00:00:00Z"),
                    end = at("2026-08-12T00:00:00Z"),
                    allDay = true,
                ),
            ),
            utc,
        )

        assertEquals(
            "2026-08-10 Mon  08:00-08:15  Standup (Kitchen)\n" +
                "2026-08-11 Tue  all day  Bank holiday",
            said,
        )
    }

    @Test
    fun anEmptyWindowSaysSoRatherThanNothing() {
        // A model told only "nothing" cannot tell an empty day from a locked
        // door, and answers as though it had read one.
        assertEquals(
            "nothing on the calendar in that window",
            CalendarTool.describe(emptyList(), utc),
        )
    }

    @Test
    fun whatDoesNotFitIsSaidRatherThanDropped() {
        val many = (1..CalendarTool.LIMIT + 3).map { Event("event $it", 0, 60) }

        val said = CalendarTool.describe(many, utc)

        assertEquals(CalendarTool.LIMIT + 1, said.lines().size)
        assertTrue(said, said.endsWith("and 3 more not shown"))
    }
}
