// EventKitReminders.swift — the reminders seam, against the framework that owns it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   EventKitReminders  Outstanding reminders out of the store, as the tool's type.
//
// An actor for the reason EventKitCalendars is one: EKEventStore is not
// Sendable, and actor isolation is the version of that claim the compiler
// enforces. It does not share the calendar's store — a value in two isolation
// domains is exactly what the isolation is being used to rule out.
//
// The one thing here that must not be simplified later: the fetch asks for every
// incomplete reminder, with both ends of the predicate nil, and the cutoff is
// applied afterwards in Swift.
//
// `predicateForIncompleteReminders(withDueDateStarting:ending:)` takes a range,
// and a reminder with no due date is not in any range. Handing the cutoff to the
// predicate therefore drops every undated reminder — which is most of them, and
// which is precisely the answer the seam and the tool are shaped to avoid. A
// cutoff decides which deadlines matter, not which work exists, and that is only
// true if the filtering happens where undated can be kept on purpose.
//
// Both mappings that follow are static over Foundation types rather than methods
// over EKReminder, so a simulator reaches all of them. Nothing here can test
// that a granted store returns anything, and nothing pretends to.

import EventKit
import Foundation

/// Outstanding reminders out of the store.
public actor EventKitReminders: Reminders {
    private let store: EKEventStore

    public init(store: EKEventStore = EKEventStore()) {
        self.store = store
    }

    /// When a reminder is due, and whether that is a day rather than a moment.
    ///
    /// `EKReminder` has no `dueDate`. It has `dueDateComponents`, and a reminder
    /// set for a day carries only year, month and day — so the absence of an
    /// hour is what says all-day, rather than a flag the framework does not have.
    ///
    /// - Returns: the instant, or `nil` where there is no due date at all, and
    ///   whether it is a whole day.
    static func due(from components: DateComponents?, zone: TimeZone) -> (Date?, Bool) {
        guard let components else { return (nil, false) }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone

        // A day has no time of day. Read as midnight and rendered as one, it is a
        // deadline that expired before its owner woke up.
        let isAllDay = components.hour == nil && components.minute == nil
        return (calendar.date(from: components), isAllDay)
    }

    /// Soonest due first, undated last.
    ///
    /// Undated last rather than first: sorting them to the top puts everything
    /// with no deadline above the thing due in an hour. Sorting them out entirely
    /// is the mistake this file's header is about.
    static func ordered(_ reminders: [Reminder]) -> [Reminder] {
        reminders.sorted { left, right in
            switch (left.due, right.due) {
            case (let a?, let b?): a < b
            case (.some, nil): true
            case (nil, .some): false
            case (nil, nil): left.title < right.title
            }
        }
    }

    /// Keep what is due before the cutoff, and everything undated.
    static func within(_ cutoff: Date?, _ reminders: [Reminder]) -> [Reminder] {
        guard let cutoff else { return reminders }
        // Half-open, matching `TimeRange`: a bare day arrives as the midnight
        // that ends it, so everything due that day is kept.
        return reminders.filter { $0.due.map { $0 < cutoff } ?? true }
    }

    /// # Rely
    /// The reminders capability has been obtained. Without it the store answers
    /// with nothing rather than refusing, which reads as an empty list.
    public func outstanding(dueBefore: Date?) async throws -> [Reminder] {
        // Both ends nil. See the header: a range here would drop the undated.
        let predicate = store.predicateForIncompleteReminders(
            withDueDateStarting: nil, ending: nil, calendars: nil)

        // Mapped inside the callback rather than after it. `EKReminder` is not
        // Sendable, so an array of them crossing the continuation is a data race
        // the compiler refuses; `Reminder` is a value and crosses freely.
        let zone = TimeZone.current
        let found: [Reminder] = await withCheckedContinuation { resume in
            store.fetchReminders(matching: predicate) { reminders in
                resume.resume(returning: (reminders ?? []).map { Self.read($0, zone: zone) })
            }
        }

        return Self.ordered(Self.within(dueBefore, found))
    }

    /// One reminder, as this app's type.
    private static func read(_ reminder: EKReminder, zone: TimeZone) -> Reminder {
        let (due, isAllDay) = due(from: reminder.dueDateComponents, zone: zone)
        return Reminder(
            title: reminder.title ?? "(untitled)",
            due: due,
            isAllDay: isAllDay,
            list: reminder.calendar?.title ?? "(no list)",
            priority: Reminder.Priority.read(reminder.priority),
            notes: reminder.notes)
    }
}
