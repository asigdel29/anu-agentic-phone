// ScheduledEvent.swift — reading what the model wrote as something to schedule.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   AddEventError            Why an event could not be scheduled.
//   CalendarEvent.scheduled  Reading one out of what was written.
//
// Scheduling is not searching, and two of `TimeRange`'s three rules are wrong
// here. Separated from the tool that calls it because the tool is a decode, a
// permission and a format, and this is the part with the mistakes in it.
//
// An omitted end is an hour rather than a day. "A start on its own means the day
// after it" is right for a span being searched; nobody means an all-day event by
// leaving the end off.
//
// A bare day is not a start. `2026-08-09` is midnight, which is correct for the
// start of a search and is almost never what somebody meant by a meeting. Read
// silently as 00:00 it produces an event nobody notices until it is on the
// calendar, so a day is either declared all-day or refused.
//
// A bare day as an *end* still means the end of it. The one rule that carries
// over: an event ending "on the eleventh" does not end at 00:00 on the eleventh,
// and a conference booked that way loses its last day.
//
// And a backwards event is refused rather than emptied. Reading a backwards
// range returns nothing, which is a defensible answer; writing one is not.

import Foundation

/// Why an event could not be scheduled.
public enum AddEventError: LocalizedError, Equatable, Sendable {
    /// A day where a time was needed.
    case dayWithoutATime(String)
    /// The end is not after the start.
    case backwards(starts: String, ends: String)

    public var errorDescription: String? {
        switch self {
        case .dayWithoutATime(let written):
            """
            "\(written)" is a day rather than a time, and an event needs one. \
            Write 2026-08-09T14:30, or set all_day if it really does take the \
            whole day.
            """
        case .backwards(let starts, let ends):
            """
            the event ends before it starts: \(starts) to \(ends). Leave the end \
            out for an hour.
            """
        }
    }
}

extension CalendarEvent {
    /// What an event lasts when nobody said.
    static let defaultLength: TimeInterval = 60 * 60
    private static let dayLength: TimeInterval = 60 * 60 * 24

    /// Read what the model wrote.
    ///
    /// - Parameters:
    ///   - starts: when it begins, as a day only if `allDay`.
    ///   - ends: when it stops, or `nil` for an hour — a day for an all-day one.
    ///   - calendar: where to put it, or empty for wherever new events go.
    /// - Throws: [`AddEventError`] for a day where a time was meant or an end
    ///   before a start, and [`TimeRangeError`] for something unreadable. Both
    ///   reach the model as arguments it can rewrite.
    public static func scheduled(
        title: String, starts: String, ends: String? = nil, allDay: Bool = false,
        calendar: String = "", location: String? = nil, zone: TimeZone = .current
    ) throws -> CalendarEvent {
        let start = try TimeRange.instant(starts, zone: zone)
        guard !start.wasBareDay || allDay else {
            throw AddEventError.dayWithoutATime(starts)
        }

        let finish: Date
        if let written = ends {
            let read = try TimeRange.instant(written, zone: zone)
            finish = read.wasBareDay ? read.date.addingTimeInterval(dayLength) : read.date
        } else {
            finish = start.date.addingTimeInterval(allDay ? dayLength : defaultLength)
        }

        guard finish > start.date else {
            throw AddEventError.backwards(starts: starts, ends: ends ?? "an hour later")
        }

        return CalendarEvent(
            title: title, starts: start.date, ends: finish, isAllDay: allDay,
            calendar: calendar, location: location)
    }
}
