// Calendar.kt: what is on the calendar, and asking to see it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Event         One thing on a calendar.
//   Calendars     Where events come from, as a seam.
//   CalendarTool  What the model calls.
//
// CalendarContract is not here, for the reason Conversation.kt gave first: a
// query against a content provider needs a device, and none of what is worth
// arguing about does. The window, the rendering, and the order the permission is
// asked in are all decided on this side of the seam and checked on the JVM.
//
// The window is whole days rather than timestamps. A model asked to produce an
// epoch second produces one wrong by a timezone about as often as not, and the
// question is always today, tomorrow, this week. Days from local midnight are
// the units people ask in and the only ones the phone can resolve for them.

package com.getlora.wattrouter

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One thing on a calendar. */
data class Event(
    val title: String,
    /** Seconds. Local midnight when [allDay]. */
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    val location: String? = null,
)

/** Where events come from. */
interface Calendars {
    /**
     * Everything overlapping the window, earliest first.
     *
     * # Rely
     * Called from the turn loop with the capability already obtained. Reads a
     * content provider, so it blocks and belongs off the main thread, and moving it
     * there is the conformance's job, not the caller's.
     *
     * @param from seconds, inclusive.
     * @param to seconds, exclusive.
     */
    suspend fun between(from: Long, to: Long): List<Event>
}

/** Read the calendar. */
class CalendarTool(
    private val calendars: Calendars,
    private val permission: Permission,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : Tool {
    override val name = "read_calendar"

    override val purpose =
        "Read what is on the person's calendar. Use it before answering anything " +
            "that turns on when they are free or what they have on. There is no " +
            "other way to know. The window is whole days in their own timezone."

    override val schema = """
        {"type":"object","properties":{
        "start_day":{"type":"integer",
        "description":"Days from today. 0 is today, 1 tomorrow, 7 a week away."},
        "days":{"type":"integer",
        "description":"How many days to read from there, 1 to $SPAN."}},
        "required":["start_day","days"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains CALENDAR, so it may put a dialog on screen and wait for a person
     *  to answer it. Everything before that point runs without one. */
    override suspend fun run(arguments: String): String {
        val startDay = Tools.field(arguments, "start_day").toIntOrNull()
            ?: return "start_day was missing or was not a whole number of days"
        val days = Tools.field(arguments, "days").toIntOrNull()
            ?: return "days was missing or was not a whole number"
        if (startDay < 0 || startDay > FURTHEST) {
            return "start_day must be between 0 and $FURTHEST; $startDay is outside it"
        }
        if (days < 1 || days > SPAN) {
            return "days must be between 1 and $SPAN; $days is outside it"
        }

        // Arguments first, dialog second: a malformed call that spent a prompt
        // teaches somebody to refuse the next one.
        try {
            permission.obtain(Capability.CALENDAR)
        } catch (e: PermissionError) {
            // Returned, not thrown: ToolBox's catch-all would blame the
            // arguments for a decision a person made.
            return e.message.orEmpty()
        }

        val here = zone()
        val today = Instant.ofEpochSecond(now()).atZone(here).toLocalDate()
        val from = today.plusDays(startDay.toLong()).atStartOfDay(here).toEpochSecond()
        val to = today.plusDays((startDay + days).toLong()).atStartOfDay(here).toEpochSecond()

        return describe(calendars.between(from, to), here)
    }

    companion object {
        /** The longest window. Past a fortnight this is a summary of a summary. */
        const val SPAN = 14
        /** The furthest ahead it will start. Two months is somebody's planning. */
        const val FURTHEST = 60
        /** Most events shown. Past this the model is reading a diary. */
        const val LIMIT = 20

        // Locale.ENGLISH rather than the default: the reader is a model, and a
        // date that changes shape with the phone's region is one whose test
        // passes wherever it was written.
        private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.ENGLISH)
        private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /** Events as the model reads them. Separate from [run] so the rendering,
         *  which is all of the decisions, is exercised without a provider. */
        fun describe(events: List<Event>, zone: ZoneId): String {
            // Distinguishable from a refusal and from a failure. A model told
            // only "nothing" cannot tell an empty day from a locked door.
            if (events.isEmpty()) return "nothing on the calendar in that window"

            val shown = events.take(LIMIT).joinToString("\n") { event ->
                val starts = Instant.ofEpochSecond(event.start).atZone(zone)
                val hours = if (event.allDay) {
                    "all day"
                } else {
                    "${clock.format(starts)}-" +
                        clock.format(Instant.ofEpochSecond(event.end).atZone(zone))
                }
                val where = event.location?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "${day.format(starts)}  $hours  ${event.title}$where"
            }

            // Said rather than dropped: a model that cannot tell a full window
            // from a truncated one answers as though it read all of it.
            val rest = events.size - LIMIT
            return if (rest > 0) "$shown\nand $rest more not shown" else shown
        }
    }
}
