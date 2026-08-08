// WhereAmIToolTests.swift — what a turn is told about where it is.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Every case here is about the answer being distrustable. A location tool that
// returns coordinates and nothing else is always confident and often wrong, and
// the failure it produces — somebody sent to the wrong place — does not look
// like a bug in anything.

import Foundation
import XCTest

@testable import WattRouter

/// A locator that answers with whatever it was given.
actor StubLocated: Located {
    private(set) var asked = 0
    private let answer: Place

    init(_ answer: Place) {
        self.answer = answer
    }

    func here() async throws -> Place {
        asked += 1
        return answer
    }
}

final class WhereAmIToolTests: XCTestCase {
    private static let now = Date(timeIntervalSince1970: 1_786_000_000)

    private func place(
        agedBy seconds: TimeInterval = 0, accuracy: Double = 10, name: String? = nil
    ) -> Place {
        Place(
            latitude: 51.50722, longitude: -0.12750,
            taken: Self.now.addingTimeInterval(-seconds), accuracy: accuracy, name: name)
    }

    private func tool(
        _ located: StubLocated, says: PermissionState = .granted
    ) -> WhereAmITool {
        WhereAmITool(
            located: located, permission: Permission(FixedAuthorizer(says: says)),
            now: { Self.now })
    }

    func testAFreshAccurateFixReadsAsOne() async throws {
        let said = try await tool(StubLocated(place())).run(arguments: Data("{}".utf8))

        XCTAssertTrue(said.contains("51.50722"), said)
        XCTAssertTrue(said.contains("accurate to about 10 m"), said)
    }

    func testAnOldFixIsCalledOldRatherThanTimestamped() {
        // A fix from an hour ago rendered as coordinates is indistinguishable
        // from one taken now, and a model has no reason to subtract dates.
        let said = WhereAmITool.describe(place(agedBy: 3600), asOf: Self.now)

        XCTAssertTrue(said.contains("60 minutes ago"), said)
        XCTAssertTrue(said.contains("somewhere else"), said)
    }

    func testAFixInsideTheWindowIsAgedWithoutTheWarning() {
        let said = WhereAmITool.describe(place(agedBy: 120), asOf: Self.now)

        XCTAssertTrue(said.contains("2 minutes ago"), said)
        XCTAssertFalse(said.contains("somewhere else"), said)
    }

    func testACoarseFixIsSaidInKilometres() {
        // Printed to five decimal places, three kilometres of error reads as a
        // doorstep. The unit is what stops that, not the number.
        let said = WhereAmITool.describe(place(accuracy: 3000), asOf: Self.now)

        XCTAssertTrue(said.contains("only to about 3 km"), said)
    }

    func testANegativeAccuracyIsAGuessRatherThanAPerfectFix() {
        // The system reports negative for a fix it will not stand behind, and an
        // unchecked comparison reads that as the most accurate value there is.
        let said = WhereAmITool.describe(place(accuracy: -1), asOf: Self.now)

        XCTAssertTrue(said.contains("guess"), said)
        XCTAssertFalse(said.contains("-1"), said)
    }

    func testAFixFromTheFutureIsFreshRatherThanNegativelyOld() {
        // A clock that moved. "Taken -3 minutes ago" is nonsense a model cannot
        // use, and treating it as stale would discard a fix that is fine.
        let said = WhereAmITool.describe(place(agedBy: -180), asOf: Self.now)

        XCTAssertTrue(said.contains("just now"), said)
        XCTAssertFalse(said.contains("minutes ago"), said)
    }

    func testAPlaceWithNoNameIsStillAnAnswer() {
        // Reverse geocoding needs the network and fails on a train. Coordinates
        // are the answer; the name was a second question.
        let said = WhereAmITool.describe(place(name: nil), asOf: Self.now)

        XCTAssertTrue(said.contains("51.50722"), said)
    }

    func testANameIsGivenWithThePositionRatherThanInsteadOfIt() {
        // Both: a name is what a person recognises and coordinates are what
        // anything else can act on.
        let said = WhereAmITool.describe(place(name: "Charing Cross"), asOf: Self.now)

        XCTAssertTrue(said.contains("Charing Cross"), said)
        XCTAssertTrue(said.contains("51.50722"), said)
    }

    func testARefusalNeverReachesTheHardware() async {
        // The prompt is the point at which somebody declines, and asking the
        // manager anyway would be this app taking a fix it was refused.
        let located = StubLocated(place())
        let box = ToolBox([tool(located, says: .refused)])

        let result = try? await box.run(ToolCall(id: "c1", name: "where_am_i", arguments: "{}"))

        XCTAssertEqual(result?.isError, true)
        let asked = await located.asked
        XCTAssertEqual(asked, 0)
    }
}
