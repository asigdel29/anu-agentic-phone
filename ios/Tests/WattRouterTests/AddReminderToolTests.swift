// AddReminderToolTests.swift — what actually gets written down.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The two that matter are about a date that was not given and a date that was
// given as a day. Both are ordinary here and neither is ordinary for an event,
// which is why this tool is thinner than add_event rather than less careful.

import Foundation
import XCTest

@testable import WattRouter

final class AddReminderToolTests: XCTestCase {
    private static let now = Date(timeIntervalSince1970: 1_786_000_000)

    private func tool(
        _ reminders: StubReminders, says: PermissionState = .granted
    ) -> AddReminderTool {
        AddReminderTool(
            reminders: reminders, permission: Permission(FixedAuthorizer(says: says)),
            zone: TimeZone(identifier: "UTC")!, now: { Self.now })
    }

    func testAReminderWithNoDateGetsNoDate() async throws {
        // "Remind me to call the plumber" has no date and must not acquire one.
        // Inventing today's would put it in the overdue pile tomorrow morning.
        let reminders = StubReminders()
        let said = try await tool(reminders)
            .run(arguments: Data(#"{"title":"Call the plumber"}"#.utf8))

        let added = await reminders.added
        let written = try XCTUnwrap(added.first)
        XCTAssertNil(written.due)
        XCTAssertFalse(written.isAllDay)
        XCTAssertTrue(said.contains("no date"), said)
    }

    func testABareDayIsStoredAsADayRatherThanAsMidnight() async throws {
        // The only real decision in this tool. Stored as an instant it is due at
        // 00:00 and reads as overdue before its owner is awake.
        let reminders = StubReminders()
        let said = try await tool(reminders)
            .run(arguments: Data(#"{"title":"Bins out","due":"2026-08-09"}"#.utf8))

        let added = await reminders.added
        let written = try XCTUnwrap(added.first)
        XCTAssertTrue(written.isAllDay)
        // Said back as a day too, so the model is not told a time it did not give.
        XCTAssertTrue(said.contains("due 2026-08-09"), said)
        XCTAssertFalse(said.contains("00:00"), said)
    }

    func testAnInstantIsStoredAsAnInstant() async throws {
        let reminders = StubReminders()
        let said = try await tool(reminders)
            .run(arguments: Data(#"{"title":"Call back","due":"2026-08-09T14:30"}"#.utf8))

        let added = await reminders.added
        let written = try XCTUnwrap(added.first)
        XCTAssertFalse(written.isAllDay)
        XCTAssertTrue(said.contains("14:30"), said)
    }

    func testTheAnswerSaysWhereItLandedRatherThanWhereItWasAimed() async throws {
        // The stub lands everything on Personal whatever it is asked for, so a
        // tool echoing its own argument fails here.
        let said = try await tool(StubReminders(lands: "Personal"))
            .run(arguments: Data(#"{"title":"Pay rent","list":"Work"}"#.utf8))

        XCTAssertTrue(said.contains("Personal"), said)
        XCTAssertFalse(said.contains("Work"), said)
    }

    func testAPriorityIsCarriedThroughAndAnAbsentOneIsNone() async throws {
        let reminders = StubReminders()
        _ = try await tool(reminders)
            .run(arguments: Data(#"{"title":"Rent","priority":"high"}"#.utf8))
        _ = try await tool(reminders).run(arguments: Data(#"{"title":"Milk"}"#.utf8))

        let written = await reminders.added
        XCTAssertEqual(written.map(\.priority), [.high, .none])
    }

    func testAPriorityNobodyDefinedIsNoneRatherThanARefusal() async throws {
        // The schema constrains it, and a model can still write something else.
        // None is the honest reading; refusing the whole call over it loses the
        // reminder somebody asked for.
        let reminders = StubReminders()
        _ = try await tool(reminders)
            .run(arguments: Data(#"{"title":"Rent","priority":"urgent"}"#.utf8))

        let added = await reminders.added
        let written = try XCTUnwrap(added.first)
        XCTAssertEqual(written.priority, .none)
    }

    func testAnEmptyTitleIsSaidRatherThanWritten() async throws {
        let reminders = StubReminders()
        let said = try await tool(reminders).run(arguments: Data(#"{"title":"  "}"#.utf8))

        XCTAssertTrue(said.contains("needs something"), said)
        let written = await reminders.added
        XCTAssertTrue(written.isEmpty, "wrote it anyway")
    }

    func testAnUnreadableDateIsRefusedBeforeThePermissionIsSpent() async throws {
        let reminders = StubReminders()

        do {
            _ = try await tool(reminders, says: .unasked)
                .run(arguments: Data(#"{"title":"Something","due":"next tuesday"}"#.utf8))
            XCTFail("read it")
        } catch is PermissionError {
            XCTFail("asked before reading the arguments")
        } catch {
            let written = await reminders.added
            XCTAssertTrue(written.isEmpty)
        }
    }
}
