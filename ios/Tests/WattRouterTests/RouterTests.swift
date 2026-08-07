// RouterTests.swift — the routing rules, asserted from Swift on an iOS runtime.
//
// History
//   2026-08-06  A. Sigdel  Created.
//
// These duplicate assertions the Rust tests already make, on purpose. Those run
// on the host and prove the policy; these run on an iOS runtime and prove the
// port did not change it — the failure this spike exists to catch, and one that
// would show up nowhere else.

import XCTest

@testable import WattRouter

final class RouterTests: XCTestCase {
    private static let chat = #"{"messages":[{"role":"user","content":"hello there"}]}"#
    private static let background = #"{"messages":[{"role":"user","content":"t"}],"max_tokens":16}"#

    func testADecisionCrossesTheBoundary() throws {
        let router = try makeRouter()
        let decision = try XCTUnwrap(router.decide(body: Self.chat))
        XCTAssertEqual(decision.tier, .mid, "unscored lands in the middle")
        XCTAssertEqual(decision.reason, .unscored)
        XCTAssertNil(decision.score, "no head is loaded, so nothing was scored")
    }

    func testThePolicyRulesSurviveTheBoundary() throws {
        let router = try makeRouter()

        let pinned = try XCTUnwrap(router.decide(body: Self.chat, pin: .cheap))
        XCTAssertEqual(pinned.tier, .cheap)
        XCTAssertEqual(pinned.reason, .pinned)

        let background = try XCTUnwrap(router.decide(body: Self.background))
        XCTAssertEqual(background.tier, .aux)
        XCTAssertEqual(background.reason, .background)
    }

    func testASessionIsNeverDroppedToALowerTier() throws {
        let router = try makeRouter()
        let session = "swift-1"

        let first = try XCTUnwrap(router.decide(body: Self.chat, session: session))
        XCTAssertEqual(first.tier, .mid)

        // Background work is aux on its own. Inside a session already at mid it
        // must not drop, or a conversation gets worse partway through.
        let later = try XCTUnwrap(router.decide(body: Self.background, session: session))
        XCTAssertEqual(later.tier, .mid)
        XCTAssertEqual(later.reason, .sticky)
    }

    func testHostileInputIsAbsenceRatherThanACrash() throws {
        let router = try makeRouter()
        // A panic crossing the boundary would be undefined behaviour, not a
        // failed test. Each of these has to come back as nil.
        for bad in ["not json", "", "<<<", #"{"messages":}"#] {
            XCTAssertNil(router.decide(body: bad), "for \(bad)")
        }
    }

    func testNamesAreReadFromTheCoreRatherThanRestated() {
        XCTAssertEqual(
            Tier.allCases.map(\.name), ["aux", "cheap", "mid", "code", "long", "heavy"])
        XCTAssertEqual(
            Reason.allCases.map(\.name),
            ["pinned", "background", "context-too-large", "scored", "code-shaped", "unscored",
             "sticky"])
        // The backend decides whether a step loads a file into this process or
        // spends the upstream credential over the network, so a raw value that
        // had drifted from the core's code would pick the other one silently.
        // Reading each name back is what pins the two together.
        XCTAssertEqual(Backend.allCases.map(\.name), ["local", "remote"])
    }

    func testARouterIsUsableFromSeveralTasksAtOnce() async throws {
        // The header says one router may be shared. If that were wrong this is
        // where it would surface, since the score cache is the shared part.
        let router = try makeRouter()
        await withTaskGroup(of: Tier?.self) { group in
            for i in 0..<32 {
                group.addTask { router.decide(body: Self.chat, session: "s\(i % 4)")?.tier }
            }
            for await tier in group {
                XCTAssertEqual(tier, .mid)
            }
        }
    }
}
