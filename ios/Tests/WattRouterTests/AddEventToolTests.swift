// AddEventToolTests.swift — the order, and what is said afterwards.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// What a written date means is #203's, and tested there. What is left is the
// tool's own: that nothing is written after a refusal, that nothing is written
// before the arguments are read, and that the answer names where the event
// actually went.

import Foundation
import XCTest

@testable import WattRouter

final class AddEventToolTests: XCTestCase {
    private let zone = TimeZone(identifier: "UTC")!

    private func tool(
        _ calendars: StubCalendars, allowed: PermissionState = .granted
    ) -> AddEventTool {
        AddEventTool(
            calendars: calendars, permission: Permission(FixedAuthorizer(says: allowed)),
            zone: zone)
    }

    private func call(_ json: String) -> Data { Data(json.utf8) }

    func testTheAnswerNamesWhereItLandedRatherThanWhereItWasAimed() async throws {
        // The two differ. A model told its own argument back has learned nothing
        // about what actually happened.
        let calendars = StubCalendars(lands: "Shared")
        let said = try await tool(calendars).run(
            arguments: call(
                #"{"title": "Review", "starts": "2026-08-09T14:00", "calendar": "Work"}"#))

        XCTAssertTrue(said.contains("on Shared"), said)
        XCTAssertTrue(said.contains(#""Review""#), said)
        XCTAssertTrue(said.contains("2026-08-09 14:00 to 2026-08-09 15:00"), said)
    }

    func testAnAllDayEventIsSaidBackAsDaysRatherThanTimes() async throws {
        let said = try await tool(StubCalendars()).run(
            arguments: call(#"{"title": "Leave", "starts": "2026-08-09", "all_day": true}"#))

        XCTAssertTrue(said.contains("all day 2026-08-09 to 2026-08-10"), said)
        XCTAssertFalse(said.contains("00:00"), said)
    }

    func testTheUnderscoredKeyIsTheOneTheSchemaAdvertises() async throws {
        // `all_day` in the schema and `allDay` in Swift. A decoder-wide key
        // strategy would fix it for every tool at once and break the ones that
        // do not want it, so the mapping is on this type.
        let calendars = StubCalendars()
        _ = try await tool(calendars).run(
            arguments: call(#"{"title": "Leave", "starts": "2026-08-09", "all_day": true}"#))

        let written = await calendars.added
        XCTAssertEqual(written.first?.isAllDay, true, "all_day did not reach the event")
    }

    func testNothingIsWrittenAfterARefusal() async throws {
        let calendars = StubCalendars()

        do {
            _ = try await tool(calendars, allowed: .refused).run(
                arguments: call(#"{"title": "Standup", "starts": "2026-08-09T09:00"}"#))
            XCTFail("wrote to a calendar it was refused")
        } catch {
            XCTAssertEqual(error as? PermissionError, .refused(.calendar))
        }

        let written = await calendars.added
        XCTAssertTrue(written.isEmpty)
    }

    func testTheArgumentsAreReadBeforeThePromptIsSpent() async throws {
        // One prompt per capability for the life of the app. A call the model
        // can rewrite must not be what spends it.
        let calendars = StubCalendars()

        do {
            _ = try await tool(calendars, allowed: .unasked).run(
                arguments: call(#"{"title": "Sprint", "starts": "sometime next week"}"#))
            XCTFail("read a date nothing can read")
        } catch {
            XCTAssertEqual(error as? TimeRangeError, .unreadable("sometime next week"))
        }

        let written = await calendars.added
        XCTAssertTrue(written.isEmpty)
    }

    func testARefusedShapeNeverReachesTheCalendarEither() async throws {
        // A bare day for a meeting is refused by #203, and the point here is
        // that the refusal happens before anything is written rather than after.
        let calendars = StubCalendars()

        do {
            _ = try await tool(calendars).run(
                arguments: call(#"{"title": "Sprint", "starts": "2026-08-09"}"#))
            XCTFail("scheduled a meeting at midnight")
        } catch {
            XCTAssertEqual(error as? AddEventError, .dayWithoutATime("2026-08-09"))
        }

        let written = await calendars.added
        XCTAssertTrue(written.isEmpty)
    }
}
