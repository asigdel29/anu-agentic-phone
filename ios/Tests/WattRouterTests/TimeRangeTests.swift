// TimeRangeTests.swift — the day boundary, and the shapes models write.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Every case passes a fixed instant in, so none of this depends on the day it
// runs. The one to read first is the day boundary: `to: 2026-08-09` meaning the
// end of the ninth rather than midnight at its start is the difference between
// "today to today" returning the day and returning nothing, and the second
// reads exactly like an empty calendar.

import Foundation
import XCTest

@testable import WattRouter

final class TimeRangeTests: XCTestCase {
    /// A zone with no daylight saving, so a case about day boundaries is about
    /// day boundaries.
    private let zone = TimeZone(identifier: "UTC")!

    /// 2026-08-07 09:30 UTC.
    private let now = Date(timeIntervalSince1970: 1_786_095_000)

    private func at(_ text: String) -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.date(from: text)!
    }

    func testADayAsAnEndMeansTheEndOfIt() throws {
        // The whole reason this type exists. Read as an instant, this range is
        // empty and looks like a calendar with nothing on it.
        let range = try TimeRange.read(from: "2026-08-09", to: "2026-08-09", now: now, zone: zone)

        XCTAssertEqual(range.start, at("2026-08-09 00:00"))
        XCTAssertEqual(range.end, at("2026-08-10 00:00"))
    }

    func testADayAsAStartMeansTheStartOfIt() throws {
        let range = try TimeRange.read(from: "2026-08-09", to: "2026-08-11", now: now, zone: zone)

        XCTAssertEqual(range.start, at("2026-08-09 00:00"))
        XCTAssertEqual(range.end, at("2026-08-12 00:00"), "the last day was cut off")
    }

    func testNothingWrittenMeansTheNextDay() throws {
        // Nothing tells the model what day it is, so a tool asked with no range
        // has to answer about something rather than refuse.
        let range = try TimeRange.read(from: nil, to: nil, now: now, zone: zone)

        XCTAssertEqual(range.start, now)
        XCTAssertEqual(range.end, now.addingTimeInterval(60 * 60 * 24))
    }

    func testAStartOnItsOwnMeansTheDayAfterIt() throws {
        let range = try TimeRange.read(from: "2026-08-09T14:30", to: nil, now: now, zone: zone)

        XCTAssertEqual(range.start, at("2026-08-09 14:30"))
        XCTAssertEqual(range.end, at("2026-08-10 14:30"))
    }

    func testAnEndOnItsOwnMeansFromNow() throws {
        let range = try TimeRange.read(from: nil, to: "2026-08-09", now: now, zone: zone)

        XCTAssertEqual(range.start, now)
        XCTAssertEqual(range.end, at("2026-08-10 00:00"))
    }

    func testAnInstantWithAZoneKeepsIt() throws {
        // The model was explicit. Overriding it with the device's zone would
        // move the instant it named.
        let range = try TimeRange.read(
            from: "2026-08-09T14:30:00+02:00", to: nil, now: now,
            zone: TimeZone(identifier: "America/New_York")!)

        XCTAssertEqual(range.start, at("2026-08-09 12:30"))
    }

    func testTheThreeShapesModelsWriteAreAllRead() throws {
        let same = at("2026-08-09 14:30")
        for written in ["2026-08-09T14:30", "2026-08-09 14:30", "2026-08-09T14:30:00Z"] {
            let range = try TimeRange.read(from: written, to: nil, now: now, zone: zone)
            XCTAssertEqual(range.start, same, "did not read \(written)")
        }
    }

    func testATimeIsNotReadAsABareDay() throws {
        // The day shape is tried first. A parser that matched a prefix would
        // read this as midnight and silently lose the time of day.
        let range = try TimeRange.read(from: "2026-08-09T14:30", to: nil, now: now, zone: zone)
        XCTAssertNotEqual(range.start, at("2026-08-09 00:00"), "the time of day was dropped")
    }

    func testABackwardsRangeIsNamedRatherThanEmptied() {
        // An end before a start is an argument the model can fix once it is told
        // which two values disagreed. Returning nothing teaches it the calendar
        // is empty.
        XCTAssertThrowsError(
            try TimeRange.read(from: "2026-08-11", to: "2026-08-09", now: now, zone: zone)
        ) { error in
            XCTAssertEqual(
                error as? TimeRangeError, .backwards(from: "2026-08-11", to: "2026-08-09"))
            XCTAssertTrue(error.localizedDescription.contains("2026-08-11"))
        }
    }

    func testAnUnreadableDateSaysWhatWouldWork() {
        XCTAssertThrowsError(
            try TimeRange.read(from: "next tuesday", to: nil, now: now, zone: zone)
        ) { error in
            XCTAssertEqual(error as? TimeRangeError, .unreadable("next tuesday"))
            XCTAssertTrue(error.localizedDescription.contains("2026-08-09"), "no example given")
        }
    }

    func testTheRangeSaysItselfBackWithItsZone() {
        // A model with no idea what day it is learns from its own first call.
        let range = TimeRange(start: at("2026-08-09 00:00"), end: at("2026-08-10 00:00"))
        let said = range.described(zone: zone)

        XCTAssertTrue(said.contains("2026-08-09 00:00"), said)
        XCTAssertTrue(said.contains("2026-08-10 00:00"), said)
        // `zone.identifier` rather than "UTC": Foundation normalises that one to
        // GMT, and a test that hard-codes the spelling it was given is testing
        // Foundation's table rather than this type.
        XCTAssertTrue(said.contains(zone.identifier), said)
    }

    func testTheSameInstantsReadDifferentlyElsewhere() {
        // The zone is not decoration. Said back in Tokyo, the same range names
        // different hours and a different name, and a model reading it back is
        // reading the phone's day rather than a notional one.
        let range = TimeRange(start: at("2026-08-09 00:00"), end: at("2026-08-10 00:00"))
        let tokyo = range.described(zone: TimeZone(identifier: "Asia/Tokyo")!)

        XCTAssertTrue(tokyo.contains("Asia/Tokyo"), tokyo)
        XCTAssertTrue(tokyo.contains("2026-08-09 09:00"), tokyo)
        XCTAssertNotEqual(tokyo, range.described(zone: zone))
    }
}
