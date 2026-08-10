// AndroidCalendars.kt: the calendar as the provider holds it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Instances rather than Events. Events holds what was entered, so a weekly
// meeting is one row carrying a recurrence rule; Instances is that rule already
// expanded into the Tuesdays somebody actually has. Asking the wrong table
// returns a plausible answer that is wrong every week but the first.
//
// The one trap here is all-day. The provider stores a date with no time as
// midnight UTC, because a date has no zone to be midnight in, so read where the
// phone is, an all-day event west of Greenwich lands on the day before and the
// model reports a holiday ending the evening it began. Event.start is documented
// as local midnight for that case, and moving it there is this file's job.

package com.getlora.wattrouter.app

import android.content.Context
import android.provider.CalendarContract
import com.getlora.wattrouter.Calendars
import com.getlora.wattrouter.Event
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An all-day event, moved to the midnight it means.
 *
 * @param millis what the provider stored, which is midnight UTC.
 * @return seconds at midnight of the same date where [zone] is.
 */
internal fun allDayAt(millis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(zone)
        .toEpochSecond()

/** The calendars on this phone. */
class AndroidCalendars(
    private val context: Context,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Calendars {

    override suspend fun between(from: Long, to: Long): List<Event> =
        // The provider blocks, and the seam says moving it off the main thread
        // is this side's job rather than the caller's: a tool asked to know
        // which thread it is on is a tool that will one day be wrong.
        withContext(Dispatchers.IO) {
            val here = zone()
            val events = mutableListOf<Event>()

            // The bounds are the query, not a selection: Instances.query takes
            // them because expanding a recurrence needs to know how far.
            CalendarContract.Instances.query(
                context.contentResolver,
                COLUMNS,
                from * 1000,
                to * 1000,
            )?.use { row ->
                while (row.moveToNext()) {
                    val allDay = row.getInt(ALL_DAY) == 1
                    val begin = row.getLong(BEGIN)
                    val end = row.getLong(END)
                    events += Event(
                        // Untitled rather than empty. A blank line in a list of
                        // events reads as a rendering fault, not as an event
                        // somebody never named.
                        title = row.getString(TITLE)?.takeIf { it.isNotBlank() } ?: "untitled",
                        start = if (allDay) allDayAt(begin, here) else begin / 1000,
                        end = if (allDay) allDayAt(end, here) else end / 1000,
                        allDay = allDay,
                        location = row.getString(LOCATION),
                    )
                }
            }

            // Ordered here rather than by the query. Instances accepts a sort
            // order and silently ignores it on some providers, and the seam
            // promises earliest first.
            events.sortedBy { it.start }
        }

    private companion object {
        val COLUMNS = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        // By position, which is what a projection buys: a column looked up by
        // name once per row is a string comparison per row per query.
        const val TITLE = 0
        const val BEGIN = 1
        const val END = 2
        const val ALL_DAY = 3
        const val LOCATION = 4
    }
}
