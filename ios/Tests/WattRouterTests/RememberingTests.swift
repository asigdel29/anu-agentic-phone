// RememberingTests.swift — the store's answers, read as Swift values.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The literals here are the shape router/src/ffi_memory.rs is asserted to emit,
// on the same terms as RepositoryTests: neither side can prove the other right
// on its own, so both assert the same literal and a change to one that does not
// change the other fails here.

import Foundation
import XCTest

@testable import WattRouter

final class RememberingTests: XCTestCase {
    private func recollection(_ json: String) throws(MemoryError) -> Recollection {
        try CoreAnswer<Recollection>.value(from: Data(json.utf8), failing: MemoryError.self)
    }

    private func evidence(role: String) -> String {
        """
        {"ok":{"route":"Relational","evidence":[
          {"turn_id":4,"session_id":"s","session_turn":2,"speaker":"user",
           "text":"the spare key is with Dave","ts":1786000000,
           "score":0.83,"role":"\(role)"}]}}
        """
    }

    func testARecollectionArrivesWithItsRouteAndItsEvidence() throws {
        let read = try recollection(evidence(role: "Main"))

        XCTAssertEqual(read.route, "Relational")
        XCTAssertEqual(read.evidence.count, 1)
        XCTAssertEqual(read.evidence.first?.text, "the spare key is with Dave")
        XCTAssertEqual(read.evidence.first?.speaker, "user")
        XCTAssertEqual(read.evidence.first?.ts, 1_786_000_000)
        XCTAssertEqual(read.evidence.first?.score ?? 0, 0.83, accuracy: 0.0001)
    }

    func testEachRoleArrivesAsItsOwnCase() throws {
        // The distinction a tool must not flatten: main matched, the other two
        // were dragged in beside something that did.
        XCTAssertEqual(try recollection(evidence(role: "Main")).evidence.first?.role, .main)
        XCTAssertEqual(
            try recollection(evidence(role: "GraphBridge")).evidence.first?.role, .graphBridge)
        XCTAssertEqual(
            try recollection(evidence(role: "LocalNeighbor")).evidence.first?.role, .localNeighbor)
    }

    func testARoleThisBuildDoesNotKnowFailsRatherThanDefaulting() {
        // A fourth role read as one of the three is a bridge turn presented as
        // direct evidence, which is a fact nobody stated.
        XCTAssertThrowsError(try recollection(evidence(role: "Hypothetical"))) { error in
            guard let memory = error as? MemoryError, case .unreadable = memory else {
                return XCTFail("read it as something: \(error)")
            }
        }
    }

    func testARouteThisBuildDoesNotKnowIsKeptRatherThanRefused() throws {
        // Open where the role is closed: a new route changes how an answer was
        // found, not what it means, so it is worth showing rather than failing.
        let read = try recollection(
            #"{"ok":{"route":"Something","evidence":[]}}"#)

        XCTAssertEqual(read.route, "Something")
    }

    func testAStoreWithNothingInItIsEmptyRatherThanAnError() throws {
        // A fresh install has never been told anything, which is ordinary.
        let read = try recollection(#"{"ok":{"route":"Local","evidence":[]}}"#)

        XCTAssertTrue(read.isEmpty)
        XCTAssertEqual(read.route, "Local")
    }

    func testARefusalArrivesAsTheCoresOwnWords() {
        let json = #"{"error":"a turn with no text cannot be remembered"}"#
        XCTAssertThrowsError(try recollection(json)) { error in
            XCTAssertEqual(
                error as? MemoryError, .refused("a turn with no text cannot be remembered"))
        }
    }

    func testATurnIdArrivesAsANumber() throws {
        // What `remember` answers with. The one entry point whose ok is a scalar.
        let id = try CoreAnswer<Int64>.value(from: Data(#"{"ok":7}"#.utf8), failing: MemoryError.self)

        XCTAssertEqual(id, 7)
    }

    func testEveryMemoryFailureSaysSomethingActionable() {
        // These reach the model through ToolBox as localizedDescription, and the
        // default is "the operation couldn't be completed".
        for failure: MemoryError in [
            .refused("no"), .unopened, .unanswered, .unreadable("x"),
        ] {
            let said = failure.errorDescription ?? ""
            XCTAssertFalse(said.isEmpty, "\(failure)")
            XCTAssertFalse(said.contains("couldn't be completed"), said)
        }
    }
}
