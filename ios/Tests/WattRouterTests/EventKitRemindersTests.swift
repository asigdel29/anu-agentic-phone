// EventKitRemindersTests.swift — the mappings, which is all of this a simulator
// can reach.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Nothing here grants anything, as in EventKitCalendarsTests: a test cannot
// answer a system prompt, so "a granted store returns reminders" is not testable
// and is not pretended to be.
//
// What is testable is every decision the file makes on its own — when a reminder
// is due, whether that is a day, what order they come in, and which survive a
// cutoff. The last is the one that matters: the framework's own predicate would
// drop the undated, and this is the code that exists so it does not.

import Foundation
import XCTest

@testable import WattRouter

final class EventKitRemindersTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!

    private func reminder(_ title: String, due: Date? = nil) -> Reminder {
        Reminder(title: title, due: due, list: "Personal")
    }

    private func instant(_ day: Int, hour: Int = 12) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar.date(
            from: DateComponents(year: 2026, month: 8, day: day, hour: hour))!
    }

    func testComponentsWithATimeAreAMomentRatherThanADay() {
        let (due, isAllDay) = EventKitReminders.due(
            from: DateComponents(year: 2026, month: 8, day: 9, hour: 14, minute: 30), zone: utc)

        XCTAssertEqual(due, instant(9, hour: 14).addingTimeInterval(30 * 60))
        XCTAssertFalse(isAllDay)
    }

    func testComponentsWithNoTimeAreADay() {
        // EKReminder has no all-day flag. The absence of an hour is what says it,
        // and reading it as midnight makes a deadline that expired overnight.
        let (due, isAllDay) = EventKitReminders.due(
            from: DateComponents(year: 2026, month: 8, day: 9), zone: utc)

        XCTAssertNotNil(due)
        XCTAssertTrue(isAllDay)
    }

    func testNoComponentsAtAllIsNoDueDate() {
        let (due, isAllDay) = EventKitReminders.due(from: nil, zone: utc)

        XCTAssertNil(due)
        XCTAssertFalse(isAllDay)
    }

    func testACutoffKeepsTheUndatedAndDropsWhatIsDueAfterIt() {
        // The whole reason this filtering is in Swift rather than in the
        // predicate. `predicateForIncompleteReminders(withDueDateStarting:ending:)`
        // takes a range, and nothing undated is in a range.
        let kept = EventKitReminders.within(
            instant(10),
            [reminder("Undated"), reminder("Before", due: instant(9)),
             reminder("After", due: instant(11))])

        XCTAssertEqual(kept.map(\.title), ["Undated", "Before"])
    }

    func testTheCutoffIsHalfOpenSoTheInstantItselfIsOut() {
        // Matching TimeRange, whose end is exclusive: a bare day arrives as the
        // midnight that ends it, and everything due that day is kept.
        let kept = EventKitReminders.within(instant(10), [reminder("On it", due: instant(10))])

        XCTAssertTrue(kept.isEmpty)
    }

    func testNoCutoffKeepsEverything() {
        let all = [reminder("Undated"), reminder("Dated", due: instant(9))]
        XCTAssertEqual(EventKitReminders.within(nil, all).count, 2)
    }

    func testSoonestFirstAndUndatedLast() {
        // Undated first would put everything with no deadline above the thing due
        // in an hour, which is the ordering nobody wants and the easiest to write.
        let ordered = EventKitReminders.ordered([
            reminder("Undated"), reminder("Later", due: instant(11)),
            reminder("Sooner", due: instant(9)),
        ])

        XCTAssertEqual(ordered.map(\.title), ["Sooner", "Later", "Undated"])
    }

    func testUndatedRemindersHaveAStableOrderAmongThemselves() {
        // By title, so the same list does not shuffle between two calls and read
        // as having changed.
        let ordered = EventKitReminders.ordered([reminder("b"), reminder("a"), reminder("c")])
        XCTAssertEqual(ordered.map(\.title), ["a", "b", "c"])
    }
}
