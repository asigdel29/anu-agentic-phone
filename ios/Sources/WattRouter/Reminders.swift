// Reminders.swift — what is outstanding, and the seam to whatever holds it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Reminder   One thing not yet done.
//   Reminders  The seam to whatever holds them.
//
// No EventKit here, as in Calendars.swift: the conformance is its own change,
// and holding it back is what makes this testable against a stub rather than
// against a device somebody has to grant something on first. The tool that reads
// these joins this file next, where ReadCalendarTool sits beside Calendars.
//
// A reminder is not a short calendar event. Written as one it fails in ways that
// all produce the same answer — a model saying there is nothing to do — and the
// shape of the type is where that starts.
//
// `due` is optional because most reminders have none, and it is not an omission.
// A seam that took a range rather than a cutoff would have nowhere to put them,
// and "what do I need to do" would answer "nothing" against a full list.
//
// `isAllDay` because a due date is often a day rather than a moment, and a day
// read as midnight is a deadline that expired before its owner woke up.
//
// And `priority` is a word rather than the framework's integer, because the
// number means nothing to a model and the word is what the person picked.

import Foundation

/// One thing not yet done.
public struct Reminder: Equatable, Sendable {
    /// How urgent, as the interface that sets it presents the choice.
    ///
    /// `EKReminder.priority` is an integer with holes: 0 is none, 1 high, 5
    /// medium, 9 low, and the values between are defined and not offered
    /// anywhere. Four cases rather than nine numbers, because the number means
    /// nothing to a model and the word means what the person chose.
    public enum Priority: String, Sendable, CaseIterable {
        case none, high, medium, low

        /// The framework's integer, as one of these.
        ///
        /// Static and over the raw value, so the whole mapping is testable
        /// without a device — the pattern `EventKitAuthorizer.entity(for:)` set.
        /// The bands are Apple's own: 1–4 high, 5 medium, 6–9 low.
        public static func read(_ raw: Int) -> Priority {
            switch raw {
            case 1...4: .high
            case 5: .medium
            case 6...9: .low
            default: .none
            }
        }
    }

    public let title: String
    /// When it is due, or `nil` — which is the common case and not an omission.
    public let due: Date?
    /// Whether the due date is a day rather than a moment.
    public let isAllDay: Bool
    /// Which list it is on. A model needs it to tell one context from another.
    public let list: String
    public let priority: Priority
    public let notes: String?

    public init(
        title: String, due: Date? = nil, isAllDay: Bool = false, list: String,
        priority: Priority = .none, notes: String? = nil
    ) {
        self.title = title
        self.due = due
        self.isAllDay = isAllDay
        self.list = list
        self.priority = priority
        self.notes = notes
    }
}

/// The seam to whatever holds the reminders.
///
/// # Rely
/// `outstanding` is called only after the reminders capability has been obtained.
public protocol Reminders: Sendable {
    /// Everything not yet done, soonest due first, undated last.
    ///
    /// - Parameter dueBefore: drop anything due at or after this, WHERE `nil`
    ///   keeps everything. Half-open, as `TimeRange` is, so a bare day given as
    ///   the cutoff arrives as the midnight that ends it and everything due that
    ///   day is kept. Undated reminders are kept either way: a cutoff says which
    ///   deadlines matter, not which work exists.
    func outstanding(dueBefore: Date?) async throws -> [Reminder]
}
