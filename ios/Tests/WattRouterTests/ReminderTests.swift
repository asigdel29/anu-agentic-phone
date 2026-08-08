// ReminderTests.swift — the framework's priority scale, as four words.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The only thing in `Reminder` with a decision in it. Testable here rather than
// on a device because the mapping is a static function over the raw value, which
// is the pattern `EventKitAuthorizer.entity(for:)` set for the same reason.

import Foundation
import XCTest

@testable import WattRouter

final class ReminderTests: XCTestCase {
    func testEachOfApplesBandsMapsOntoTheWordForIt() {
        // 0 is none, 1-4 high, 5 medium, 6-9 low. The bands have holes because
        // the interface that sets them offers four choices and stores nine.
        XCTAssertEqual(Reminder.Priority.read(0), .none)
        XCTAssertEqual(Reminder.Priority.read(1), .high)
        XCTAssertEqual(Reminder.Priority.read(4), .high)
        XCTAssertEqual(Reminder.Priority.read(5), .medium)
        XCTAssertEqual(Reminder.Priority.read(6), .low)
        XCTAssertEqual(Reminder.Priority.read(9), .low)
    }

    func testAValueOutsideTheScaleIsNoneRatherThanAGuess() {
        // Another app can write anything into the field. None is the honest
        // reading; guessing "high" from 99 puts a priority on somebody's list
        // that they did not set.
        XCTAssertEqual(Reminder.Priority.read(-1), .none)
        XCTAssertEqual(Reminder.Priority.read(10), .none)
        XCTAssertEqual(Reminder.Priority.read(99), .none)
    }

    func testAReminderWithNoDueDateIsOrdinaryRatherThanIncomplete() {
        // The default, and it is deliberate: most reminders have no date, and a
        // type that made one compulsory would push a guess into every caller.
        let reminder = Reminder(title: "Call the plumber", list: "Personal")

        XCTAssertNil(reminder.due)
        XCTAssertFalse(reminder.isAllDay)
        XCTAssertEqual(reminder.priority, .none)
    }
}
