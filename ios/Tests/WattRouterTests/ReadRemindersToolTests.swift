// ReadRemindersToolTests.swift — what a turn is told is outstanding.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Four of these are the same failure approached from four sides: a model told
// there is nothing to do while the list is full. That is the one this tool has
// to get right, because it is the answer a person acts on by doing nothing.

import Foundation
import XCTest

@testable import WattRouter

final class ReadRemindersToolTests: XCTestCase {
    /// A fixed instant, so "overdue" is decidable rather than a function of when
    /// the suite runs.
    private static let now = Date(timeIntervalSince1970: 1_786_000_000)

    private func tool(
        _ reminders: StubReminders, says: PermissionState = .granted
    ) -> ReadRemindersTool {
        ReadRemindersTool(
            reminders: reminders, permission: Permission(FixedAuthorizer(says: says)),
            zone: TimeZone(identifier: "UTC")!, now: { Self.now })
    }

    private func reminder(
        _ title: String, due: Date? = nil, isAllDay: Bool = false,
        priority: Reminder.Priority = .none
    ) -> Reminder {
        Reminder(title: title, due: due, isAllDay: isAllDay, list: "Personal", priority: priority)
    }

    func testAnUndatedReminderIsStillOutstanding() async throws {
        // The failure this tool exists to avoid. Most reminders have no due date,
        // and dropping them answers "what do I need to do" with "nothing".
        let said = try await tool(StubReminders([reminder("Call the plumber")]))
            .run(arguments: Data("{}".utf8))

        XCTAssertTrue(said.contains("Call the plumber"), said)
        XCTAssertTrue(said.contains("no date"), said)
    }

    func testACutoffNarrowsTheDatedAndKeepsTheUndated() async throws {
        // A cutoff says which deadlines matter, not which work exists.
        let reminders = StubReminders([reminder("Undated")])
        _ = try await tool(reminders).run(arguments: Data(#"{"due_before":"2026-08-09"}"#.utf8))

        // The cutoff reaches the seam, which is what filters. It is the exclusive
        // end of the ninth — midnight opening the tenth — because a bare day as
        // an end means the end of it. Read as the start of the ninth instead,
        // "due by the ninth" would drop everything due on the ninth.
        let cutoffs = await reminders.cutoffs
        let asked = try XCTUnwrap(cutoffs.first ?? nil)

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        XCTAssertEqual(calendar.component(.day, from: asked), 10)
        XCTAssertEqual(calendar.component(.hour, from: asked), 0)
    }

    func testSomethingPastDueSaysItIsOverdue() async throws {
        // A past date rendered as a date reads as upcoming, and the thing most
        // worth surfacing is the thing already missed.
        let overdue = Self.now.addingTimeInterval(-86_400)
        let said = try await tool(StubReminders([reminder("File the tax return", due: overdue)]))
            .run(arguments: Data("{}".utf8))

        XCTAssertTrue(said.contains("OVERDUE"), said)
    }

    func testSomethingDueLaterIsNotCalledOverdue() async throws {
        let later = Self.now.addingTimeInterval(86_400)
        let said = try await tool(StubReminders([reminder("Renew the passport", due: later)]))
            .run(arguments: Data("{}".utf8))

        XCTAssertFalse(said.contains("OVERDUE"), said)
        XCTAssertTrue(said.contains("2026-08-"), said)
    }

    func testADayDueDateIsNotRenderedAsMidnight() async throws {
        // Midnight reads as an overnight deadline that expired before breakfast.
        let day = Self.now.addingTimeInterval(86_400)
        let said = try await tool(StubReminders([reminder("Bins out", due: day, isAllDay: true)]))
            .run(arguments: Data("{}".utf8))

        XCTAssertFalse(said.contains("00:00"), said)
    }

    func testAnEmptyListSaysCompletedOnesWereNotCounted() async throws {
        // "Nothing outstanding" on its own reads as "nothing exists", and the
        // next thing a model does is congratulate somebody with forty done items.
        let said = try await tool(StubReminders()).run(arguments: Data("{}".utf8))

        XCTAssertTrue(said.contains("nothing outstanding"), said)
        XCTAssertTrue(said.contains("Completed"), said)
    }

    func testTheCountIsStatedAndTheListIsCapped() async throws {
        let many = (1...ReadRemindersTool.limit + 5).map { reminder("Item \($0)") }
        let said = try await tool(StubReminders(many)).run(arguments: Data("{}".utf8))

        XCTAssertTrue(said.contains("\(many.count) outstanding"), said)
        XCTAssertTrue(said.contains("5 more, not shown"), said)
        XCTAssertFalse(said.contains("Item \(many.count)"), "showed past the cap")
    }

    func testPriorityIsAWordRatherThanANumber() async throws {
        let said = try await tool(StubReminders([reminder("Pay rent", priority: .high)]))
            .run(arguments: Data("{}".utf8))

        XCTAssertTrue(said.contains("high priority"), said)
    }

    func testAMalformedDateIsRefusedBeforeThePermissionIsSpent() async throws {
        // One prompt per capability for the life of the app. A call the tool
        // cannot read must not be what spends it.
        let reminders = StubReminders()
        let tool = tool(reminders, says: .unasked)

        do {
            _ = try await tool.run(arguments: Data(#"{"due_before":"next tuesday"}"#.utf8))
            XCTFail("read it")
        } catch is PermissionError {
            XCTFail("asked before reading the arguments")
        } catch {
            let reads = await reminders.reads
            XCTAssertEqual(reads, 0)
        }
    }
}
