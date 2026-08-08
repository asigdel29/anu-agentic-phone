// Calendars.swift — what is on the calendar, and the seam to whatever knows.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   CalendarEvent  One thing on the calendar.
//   Calendars      The seam to whatever holds them.
//   ReadCalendarTool  Asking what is on it, as a tool.
//
// No EventKit here. The conformance is its own change, and holding it back is
// what makes this testable against a stub rather than against a device somebody
// has to grant something on first.
//
// Three things this gets right on purpose, each of which reads as a detail and
// is not. The range is parsed before the permission is obtained, because there
// is one prompt per capability for the life of the app and spending it on a
// malformed call spends it for good. An all-day event says so rather than
// rendering as midnight to midnight, which a model reads as a fifteen-minute gap
// and concludes the day is clear. And a capped list says how many it left out,
// because a truncated list read as a complete one produces "you have nothing
// after the fourth".

import Foundation

/// One thing on the calendar.
public struct CalendarEvent: Equatable, Sendable {
    public let title: String
    public let starts: Date
    public let ends: Date
    /// Whether it occupies days rather than hours.
    public let isAllDay: Bool
    /// Which calendar it is on — work, personal, a shared one. The model needs
    /// it to tell "busy" from "somebody else is busy".
    public let calendar: String
    public let location: String?

    public init(
        title: String, starts: Date, ends: Date, isAllDay: Bool = false,
        calendar: String, location: String? = nil
    ) {
        self.title = title
        self.starts = starts
        self.ends = ends
        self.isAllDay = isAllDay
        self.calendar = calendar
        self.location = location
    }
}

/// The seam to whatever holds the calendar.
///
/// # Rely
/// `events` is called only after the calendar capability has been obtained.
public protocol Calendars: Sendable {
    /// Everything overlapping the range, earliest first.
    func events(in range: TimeRange) async throws -> [CalendarEvent]

    /// Put one on the calendar.
    ///
    /// - Parameter event: its `calendar` names where to put it, or is empty for
    ///   wherever new events go.
    /// - Returns: the calendar it landed on, which is not always the one asked
    ///   for and is the thing worth saying back.
    func add(_ event: CalendarEvent) async throws -> String
}

/// What is on the calendar.
public struct ReadCalendarTool: Tool {
    /// At which point a list stops being an answer and becomes a transcript.
    static let limit = 50

    public let name = "read_calendar"

    public let purpose = """
        What is on the calendar over a span of time. Both dates may be left out, \
        which means the next twenty-four hours; the answer always states the \
        span it actually read, so use that rather than guessing what today is. \
        Write a day as 2026-08-09, or an instant as 2026-08-09T14:30. A day \
        given as the end means the end of that day.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "from": {"type": "string", "description": "When to start. Omitted means now."},
            "to": {"type": "string", "description": "When to stop. Omitted means a day after the start."}
          }
        }
        """

    private let calendars: any Calendars
    private let permission: Permission
    private let zone: TimeZone
    private let now: @Sendable () -> Date

    /// - Parameter now: read through a closure so a test does not depend on the
    ///   day it runs on.
    public init(
        calendars: any Calendars, permission: Permission, zone: TimeZone = .current,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.calendars = calendars
        self.permission = permission
        self.zone = zone
        self.now = now
    }

    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        // Before the prompt, deliberately. A malformed call must not spend the
        // one chance the app has to ask.
        let range = try TimeRange.read(
            from: request.from, to: request.to, now: now(), zone: zone)

        try await permission.obtain(.calendar)
        let events = try await calendars.events(in: range)

        let said = range.described(zone: zone)
        guard !events.isEmpty else { return "nothing on the calendar, \(said)" }

        var lines = [said]
        lines += events.prefix(Self.limit).map { describe($0) }
        if events.count > Self.limit {
            lines.append("and \(events.count - Self.limit) more, not shown")
        }
        return lines.joined(separator: "\n")
    }

    /// One event, on one line.
    private func describe(_ event: CalendarEvent) -> String {
        let clock = DateFormatter()
        clock.locale = Locale(identifier: "en_US_POSIX")
        clock.timeZone = zone
        clock.dateFormat = "yyyy-MM-dd HH:mm"

        // "all day" rather than a range: midnight to midnight reads as a gap.
        let when =
            event.isAllDay
            ? "all day \(clock.string(from: event.starts).prefix(10))"
            : "\(clock.string(from: event.starts)) to \(clock.string(from: event.ends))"

        let place = event.location.map { ", at \($0)" } ?? ""
        return "\(when)  \(event.title)  (\(event.calendar))\(place)"
    }

    private struct Request: Decodable {
        let from: String?
        let to: String?
    }
}
