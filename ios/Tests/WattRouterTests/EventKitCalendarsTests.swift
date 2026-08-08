// EventKitCalendarsTests.swift — the mappings, which is all of this that a
// simulator can reach.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Nothing here grants anything. A test cannot answer a system prompt, so the
// parts that need one — that a granted store returns events, that a refusal
// reaches the person — are not testable and are not pretended to be.
//
// What is testable is every decision this file makes on its own: which entity a
// capability is, what each authorization status becomes, and what an event with
// nothing filled in turns into. Those are where the mistakes are.

import EventKit
import Foundation
import XCTest

@testable import WattRouter

final class EventKitCalendarsTests: XCTestCase {
    func testOnlyWhatEventKitOwnsMapsToAnEntity() {
        XCTAssertEqual(EventKitAuthorizer.entity(for: .calendar), .event)
        XCTAssertEqual(EventKitAuthorizer.entity(for: .reminders), .reminder)

        // Not a placeholder. An app wiring this as its whole authorizer really
        // cannot obtain these, and `state(of:)` says unavailable for that reason
        // rather than for want of an implementation.
        XCTAssertNil(EventKitAuthorizer.entity(for: .contacts))
        XCTAssertNil(EventKitAuthorizer.entity(for: .location))
    }

    func testWriteOnlyAccessIsNotEnoughToRead() {
        // iOS 17 split calendar access in two, and both halves are "not denied".
        // A read against write-only access returns an empty calendar rather than
        // an error, so treating it as granted tells a model the day is clear.
        XCTAssertEqual(EventKitAuthorizer.state(from: .writeOnly), .refused)
    }

    func testEveryStatusMeansExactlyOneThing() {
        XCTAssertEqual(EventKitAuthorizer.state(from: .fullAccess), .granted)
        XCTAssertEqual(EventKitAuthorizer.state(from: .denied), .refused)
        XCTAssertEqual(EventKitAuthorizer.state(from: .restricted), .unavailable)
        XCTAssertEqual(EventKitAuthorizer.state(from: .notDetermined), .unasked)
    }

    func testAnUnknownStatusAsksRatherThanAssumes() throws {
        // The framework has already grown a case since this app's floor. A value
        // outside the enum cannot always be built, so this asserts only when it
        // can — the mapping's `@unknown default` is the thing under test and it
        // is unreachable from here otherwise.
        let future = try XCTUnwrap(
            EKAuthorizationStatus(rawValue: 99), "no unknown status to build; nothing asserted")
        XCTAssertEqual(EventKitAuthorizer.state(from: future), .unasked)
    }

    func testAnEventWithNothingFilledInStillReads() {
        // `title` and `location` are implicitly unwrapped and genuinely nil for
        // an event somebody left blank, and an event built outside a granted
        // store has no calendar at all. Each absence has to become something a
        // model can read rather than a crash.
        let event = EKEvent(eventStore: EKEventStore())
        event.startDate = Date(timeIntervalSince1970: 1_786_095_000)
        event.endDate = event.startDate.addingTimeInterval(3600)

        let read = EventKitCalendars.event(event)

        XCTAssertEqual(read.title, "(untitled)")
        XCTAssertEqual(read.calendar, "(unknown calendar)")
        XCTAssertNil(read.location)
        XCTAssertEqual(read.starts, event.startDate)
    }

    func testAnUnmatchedCalendarOffersTheOnesThatWouldWork() {
        // The alternatives, not just the mistake — the same contract `ToolBox`
        // keeps when a tool name does not match one it knows. A refusal the
        // model cannot act on costs the same turn twice.
        let said = EventKitError.noSuchCalendar(asked: "Wrok", available: ["Work", "Personal"])
            .localizedDescription

        XCTAssertTrue(said.contains(#""Wrok""#), said)
        XCTAssertTrue(said.contains("Work, Personal"), said)
    }

    func testNowhereToWriteSaysNothingHappened() {
        // A failure that does not say whether it half-succeeded leaves the model
        // to guess, and it guesses that it worked.
        let said = EventKitError.nowhereToWrite.localizedDescription
        XCTAssertTrue(said.contains("Nothing was added"), said)
    }

    func testABlankLocationIsAnAbsenceRatherThanAnEmptyLine() {
        // The framework spells "no location" both ways, and only one of them is
        // nil. The other renders as ", at " with nothing after it.
        let event = EKEvent(eventStore: EKEventStore())
        event.startDate = Date(timeIntervalSince1970: 1_786_095_000)
        event.endDate = event.startDate.addingTimeInterval(3600)
        event.title = "Standup"
        event.location = "   "

        XCTAssertNil(EventKitCalendars.event(event).location)
    }
}
