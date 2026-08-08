// ReadCalendarToolTests.swift — the order things happen in, and what is said.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The listing is the obvious half. The half worth pinning is that a malformed
// call never reaches the permission prompt, that a refusal never reaches the
// calendar, that an all-day event does not read as a gap at midnight, and that a
// capped list says so.

import Foundation
import XCTest

@testable import WattRouter

private actor StubCalendars: Calendars {
    private(set) var asked = 0
    private let answer: [CalendarEvent]

    init(_ answer: [CalendarEvent] = []) {
        self.answer = answer
    }

    func events(in range: TimeRange) async throws -> [CalendarEvent] {
        asked += 1
        return answer
    }
}

private struct FixedAuthorizer: Authorizer {
    let says: PermissionState
    func state(of capability: Capability) async -> PermissionState { says }
    func request(_ capability: Capability) async -> PermissionState { says }
}

final class ReadCalendarToolTests: XCTestCase {
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

    private func tool(
        _ calendars: StubCalendars, allowed: PermissionState = .granted
    ) -> ReadCalendarTool {
        // Captured out of `self` first: the closure is `@Sendable` and an
        // XCTestCase is not.
        let instant = now
        return ReadCalendarTool(
            calendars: calendars, permission: Permission(FixedAuthorizer(says: allowed)),
            zone: zone, now: { instant })
    }

    private func call(_ json: String) -> Data { Data(json.utf8) }

    func testEachEventIsOneLineUnderTheSpanItWasRead() async throws {
        let calendars = StubCalendars([
            CalendarEvent(
                title: "Standup", starts: at("2026-08-09 09:00"), ends: at("2026-08-09 09:15"),
                calendar: "Work"),
            CalendarEvent(
                title: "Dentist", starts: at("2026-08-09 14:00"), ends: at("2026-08-09 15:00"),
                calendar: "Personal", location: "Elm Street"),
        ])

        let said = try await tool(calendars).run(
            arguments: call(#"{"from": "2026-08-09", "to": "2026-08-09"}"#))

        XCTAssertTrue(said.contains("2026-08-09 00:00 to 2026-08-10 00:00"), said)
        XCTAssertTrue(said.contains("2026-08-09 09:00 to 2026-08-09 09:15  Standup  (Work)"), said)
        XCTAssertTrue(said.contains("Dentist  (Personal), at Elm Street"), said)
    }

    func testAnEmptyCalendarSaysSoAndStillNamesTheSpan() async throws {
        // Without the span this is "nothing", and a model that guessed the wrong
        // week has no way to notice.
        let said = try await tool(StubCalendars()).run(arguments: call("{}"))

        XCTAssertTrue(said.hasPrefix("nothing on the calendar"), said)
        XCTAssertTrue(said.contains("2026-08-07 09:30 to 2026-08-08 09:30"), said)
    }

    func testAnAllDayEventDoesNotReadAsAGapAtMidnight() async throws {
        // Midnight to midnight is what an instant range makes of it, and a model
        // reasoning about free time concludes the day is clear.
        let calendars = StubCalendars([
            CalendarEvent(
                title: "Bank holiday", starts: at("2026-08-09 00:00"),
                ends: at("2026-08-10 00:00"), isAllDay: true, calendar: "Personal")
        ])

        let said = try await tool(calendars).run(arguments: call("{}"))

        XCTAssertTrue(said.contains("all day 2026-08-09  Bank holiday"), said)
        XCTAssertFalse(said.contains("00:00 to"), said)
    }

    func testAMalformedSpanNeverReachesThePrompt() async throws {
        // One prompt per capability for the life of the app. Spending it on a
        // call the model can rewrite spends it for good.
        let calendars = StubCalendars()
        let tool = self.tool(calendars, allowed: .unasked)

        do {
            _ = try await tool.run(arguments: call(#"{"from": "next tuesday"}"#))
            XCTFail("read a date nothing can read")
        } catch {
            XCTAssertEqual(error as? TimeRangeError, .unreadable("next tuesday"))
        }

        let asked = await calendars.asked
        XCTAssertEqual(asked, 0, "went to the calendar after failing to read the span")
    }

    func testARefusalNeverReachesTheCalendar() async throws {
        let calendars = StubCalendars()
        let tool = self.tool(calendars, allowed: .refused)

        do {
            _ = try await tool.run(arguments: call("{}"))
            XCTFail("read a calendar it was refused")
        } catch {
            XCTAssertEqual(error as? PermissionError, .refused(.calendar))
        }

        let asked = await calendars.asked
        XCTAssertEqual(asked, 0, "read the calendar anyway")
    }

    func testACappedListSaysHowManyItLeftOut() async throws {
        // A truncated list read as a complete one produces "you have nothing
        // after the fourth", which is worse than saying there is too much.
        let many = (0..<(ReadCalendarTool.limit + 7)).map { index in
            CalendarEvent(
                title: "Event \(index)", starts: at("2026-08-09 09:00"),
                ends: at("2026-08-09 09:15"), calendar: "Work")
        }

        let said = try await tool(StubCalendars(many)).run(arguments: call("{}"))
        let lines = said.split(separator: "\n")

        // The span, the capped events, and the notice.
        XCTAssertEqual(lines.count, ReadCalendarTool.limit + 2)
        XCTAssertEqual(lines.last, "and 7 more, not shown")
    }
}
