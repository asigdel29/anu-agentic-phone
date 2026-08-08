// ScheduledEventTests.swift — where scheduling and searching disagree.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Every case here is a rule that is right in `TimeRange` and wrong when what is
// being read is a thing to put on somebody's calendar. Reading a span wrongly
// wastes a turn; writing one wrongly leaves something behind.

import Foundation
import XCTest

@testable import WattRouter

final class ScheduledEventTests: XCTestCase {
    private let zone = TimeZone(identifier: "UTC")!

    private func at(_ text: String) -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.date(from: text)!
    }

    func testAnOmittedEndIsAnHourRatherThanADay() throws {
        // A start on its own means the day after it when searching a span.
        // Nobody means an all-day event by leaving the end off.
        let event = try CalendarEvent.scheduled(
            title: "Standup", starts: "2026-08-09T09:00", zone: zone)

        XCTAssertEqual(event.starts, at("2026-08-09 09:00"))
        XCTAssertEqual(event.ends, at("2026-08-09 10:00"))
        XCTAssertFalse(event.isAllDay)
    }

    func testABareDayIsRefusedRatherThanScheduledAtMidnight() {
        // Midnight is right for the start of a search and almost never what
        // somebody meant by a meeting, and the mistake is invisible until the
        // event is already on the calendar.
        XCTAssertThrowsError(
            try CalendarEvent.scheduled(title: "Sprint", starts: "2026-08-09", zone: zone)
        ) { error in
            XCTAssertEqual(error as? AddEventError, .dayWithoutATime("2026-08-09"))
            XCTAssertTrue(error.localizedDescription.contains("all_day"), "no way out offered")
        }
    }

    func testABareDayIsFineWhenItReallyIsAllDay() throws {
        let event = try CalendarEvent.scheduled(
            title: "Leave", starts: "2026-08-09", allDay: true, zone: zone)

        XCTAssertTrue(event.isAllDay)
        XCTAssertEqual(event.starts, at("2026-08-09 00:00"))
        XCTAssertEqual(event.ends, at("2026-08-10 00:00"), "an all-day event lasted an hour")
    }

    func testADayAsAnEndStillMeansTheEndOfIt() throws {
        // The one rule that carries over. A conference booked to the eleventh
        // and ending at 00:00 on the eleventh loses its last day.
        let event = try CalendarEvent.scheduled(
            title: "Conference", starts: "2026-08-09", ends: "2026-08-11", allDay: true,
            zone: zone)

        XCTAssertEqual(event.ends, at("2026-08-12 00:00"), "the last day was cut off")
    }

    func testABackwardsEventIsRefusedRatherThanEmptied() {
        // Reading a backwards range returns nothing, which is defensible.
        // Writing one is not.
        XCTAssertThrowsError(
            try CalendarEvent.scheduled(
                title: "Backwards", starts: "2026-08-09T14:00", ends: "2026-08-09T13:00",
                zone: zone)
        ) { error in
            XCTAssertEqual(
                error as? AddEventError,
                .backwards(starts: "2026-08-09T14:00", ends: "2026-08-09T13:00"))
        }
    }

    func testAnUnreadableStartIsTheSameMistakeAsAnywhereElse() {
        // One parser, so the three written shapes and their failure read the
        // same way here as in a searched span.
        XCTAssertThrowsError(
            try CalendarEvent.scheduled(title: "Sprint", starts: "next week", zone: zone)
        ) { error in
            XCTAssertEqual(error as? TimeRangeError, .unreadable("next week"))
        }
    }

    func testWhatWasWrittenIsCarriedThrough() throws {
        let event = try CalendarEvent.scheduled(
            title: "Review", starts: "2026-08-09T14:00", calendar: "Work",
            location: "Elm Street", zone: zone)

        XCTAssertEqual(event.title, "Review")
        XCTAssertEqual(event.calendar, "Work")
        XCTAssertEqual(event.location, "Elm Street")
    }

    func testAnInstantEntryPointSaysWhetherItWasADay() throws {
        // The flag is the whole reason this is not just a date parse: the same
        // text means different things to a search and to a meeting.
        XCTAssertTrue(try TimeRange.instant("2026-08-09", zone: zone).wasBareDay)
        XCTAssertFalse(try TimeRange.instant("2026-08-09T14:30", zone: zone).wasBareDay)
        XCTAssertEqual(
            try TimeRange.instant("2026-08-09", zone: zone).date, at("2026-08-09 00:00"))
    }
}
