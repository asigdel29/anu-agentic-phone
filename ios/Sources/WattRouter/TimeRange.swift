// TimeRange.swift — turning what the model wrote into two absolute dates.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   TimeRangeError  Why a span could not be read.
//   TimeRange       Two absolute dates, and the parse that produces them.
//
// The calendar and reminder tools both take a span from the model and neither
// can trust what it gets. Three things make this more than a date formatter.
//
// Nothing tells the model what day it is. `Conversation` supports a system
// message and nothing sets one, so a tool asked about today is asked by
// something with no idea when that is. Hence a span that is optional, defaults
// to the next twenty-four hours, and says back the absolute range it used: a
// model that guessed learns the date from its own first call.
//
// A day is a span rather than an instant. `to: 2026-08-09` means the end of the
// ninth, and reading it as midnight at the start of the ninth makes "today to
// today" return nothing while looking exactly like an empty calendar.
//
// And a backwards range is a mistake worth naming rather than an empty result.
// Told which two values disagreed, the model fixes it; told nothing, it believes
// the answer.

import Foundation

/// Why a span could not be read.
public enum TimeRangeError: LocalizedError, Equatable, Sendable {
    /// Neither shape matched.
    case unreadable(String)
    /// The end is not after the start.
    case backwards(from: String, to: String)

    public var errorDescription: String? {
        switch self {
        case .unreadable(let written):
            """
            could not read "\(written)" as a date. Write either a day, as \
            2026-08-09, or an instant, as 2026-08-09T14:30 or \
            2026-08-09T14:30:00Z.
            """
        case .backwards(let from, let to):
            """
            the range ends before it starts: from "\(from)" to "\(to)". Swap \
            them, or leave one out — a start on its own means the day after it.
            """
        }
    }
}

/// Two absolute dates, half-open: `start` is included and `end` is not.
public struct TimeRange: Equatable, Sendable {
    public let start: Date
    public let end: Date

    /// A day, for the boundary rules. Twenty-four hours is not a day across a
    /// daylight-saving change, and the calendar knows that where arithmetic on
    /// seconds does not.
    private static let day = 60.0 * 60.0 * 24.0

    /// Read what the model wrote.
    ///
    /// - Parameters:
    ///   - from: the start, or `nil` for now.
    ///   - to: the end, or `nil` for a day after the start.
    ///   - now: the current instant. Passed rather than read, so a test does not
    ///     depend on the day it runs.
    ///   - zone: the zone a bare day is a day in.
    /// - Throws: [`TimeRangeError`], which reaches the model as something it can
    ///   correct rather than as an ended turn.
    public static func read(
        from: String?, to: String?, now: Date, zone: TimeZone = .current
    ) throws -> TimeRange {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone

        let start = try from.map { try instant($0, zone: zone, calendar: calendar, endOfDay: false) }
        // A bare day as an end means the end of it. Read as an instant it would
        // be midnight at its start, and "today to today" would be empty.
        let end = try to.map { try instant($0, zone: zone, calendar: calendar, endOfDay: true) }

        let resolved = start ?? now
        let finish = end ?? resolved.addingTimeInterval(day)
        guard finish > resolved else {
            throw TimeRangeError.backwards(from: from ?? "now", to: to ?? "a day later")
        }
        return TimeRange(start: resolved, end: finish)
    }

    /// The range, said back so a model that guessed learns what it asked for.
    public func described(zone: TimeZone = .current) -> String {
        let shown = Self.formatter("yyyy-MM-dd HH:mm", zone: zone)
        return "\(shown.string(from: start)) to \(shown.string(from: end)) (\(zone.identifier))"
    }

    /// One written date, as an instant, reading a bare day as its start.
    ///
    /// Shared with whatever schedules rather than searches. Those want the same
    /// three shapes and none of the range rules, and a second copy of the shapes
    /// is a second thing to keep in step.
    ///
    /// - Returns: the instant, and whether it was written as a bare day. A day
    ///   is midnight, which is right for the start of a search and is a silent
    ///   mistake for the start of a meeting, so the caller is told rather than
    ///   left to guess.
    static func instant(_ written: String, zone: TimeZone = .current) throws -> (
        date: Date, wasBareDay: Bool
    ) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let date = try instant(written, zone: zone, calendar: calendar, endOfDay: false)
        return (date, parse("yyyy-MM-dd", written.trimmingCharacters(in: .whitespaces), zone: zone) != nil)
    }

    /// One written date, as an instant.
    ///
    /// Three shapes, because models write all three and accepting one produces a
    /// tool that works until it does not.
    private static func instant(
        _ written: String, zone: TimeZone, calendar: Calendar, endOfDay: Bool
    ) throws -> Date {
        let text = written.trimmingCharacters(in: .whitespaces)

        if let midnight = parse("yyyy-MM-dd", text, zone: zone) {
            guard endOfDay else { return midnight }
            // The start of the next day, which is the exclusive end of this one.
            return calendar.date(byAdding: .day, value: 1, to: midnight)
                ?? midnight.addingTimeInterval(day)
        }
        // A zone written into the text wins over the one passed in: the model
        // said it, and overriding that would move an instant it was explicit
        // about.
        if let exact = ISO8601DateFormatter().date(from: text) { return exact }
        for shape in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm"] {
            if let local = parse(shape, text, zone: zone) { return local }
        }
        throw TimeRangeError.unreadable(written)
    }

    private static func parse(_ shape: String, _ text: String, zone: TimeZone) -> Date? {
        formatter(shape, zone: zone).date(from: text)
    }

    private static func formatter(_ shape: String, zone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        // POSIX rather than the device's: these are shapes a model wrote, not
        // text anybody reads, and a phone set to another calendar would read
        // them into a different year.
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone
        formatter.dateFormat = shape
        return formatter
    }
}
