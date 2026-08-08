// StubReminders.swift — a reminders list that is not one.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// It records rather than pretends, as StubCalendars does: the cutoff reaching
// the seam is the assertion, because filtering is the seam's job and a tool that
// filtered instead would pass a test that only read the output.

import Foundation

@testable import WattRouter

/// A reminders list that remembers what it was asked.
actor StubReminders: Reminders {
    private(set) var reads = 0
    private(set) var cutoffs: [Date?] = []
    private(set) var added: [Reminder] = []
    private let answer: [Reminder]
    private let lands: String

    init(_ answer: [Reminder] = [], lands: String = "Personal") {
        self.answer = answer
        self.lands = lands
    }

    func outstanding(dueBefore: Date?) async throws -> [Reminder] {
        reads += 1
        cutoffs.append(dueBefore)
        return answer
    }

    func add(_ reminder: Reminder) async throws -> String {
        added.append(reminder)
        // Deliberately not the list it was asked for. Where a reminder landed and
        // where it was aimed are different things, and a stub returning the
        // argument cannot tell whether the tool says the right one.
        return lands
    }
}
