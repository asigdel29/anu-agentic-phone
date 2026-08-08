// MemoryToolsTests.swift — what a turn is told it remembers.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Against a stub rather than a store, which is what the seam is for: these are
// about the rendering, and CoreMemoryTests already runs the real thing end to
// end. The one that matters is context not reading as evidence.

import Foundation
import XCTest

@testable import WattRouter

/// A store that answers with what it was given, and remembers being asked.
///
/// A class rather than an actor, because the seam is synchronous — an actor
/// would need every call to hop, and the seam says these are disk reads that do
/// not suspend.
final class StubRemembering: Remembering, @unchecked Sendable {
    private(set) var written: [String] = []
    private(set) var asked: [String] = []
    var answer: Recollection
    var refusal: MemoryError?

    init(
        _ answer: Recollection = Recollection(route: "Local", evidence: []),
        refusal: MemoryError? = nil
    ) {
        self.answer = answer
        self.refusal = refusal
    }

    func remember(
        _ text: String, speaker: String, session: String, at: Date
    ) throws(MemoryError) -> Int64 {
        written.append(text)
        if let refusal { throw refusal }
        return Int64(written.count)
    }

    func recall(_ query: String, most: Int) throws(MemoryError) -> Recollection {
        asked.append(query)
        if let refusal { throw refusal }
        return answer
    }
}

final class MemoryToolsTests: XCTestCase {
    private func piece(_ text: String, role: Remembered.Role) -> Remembered {
        let json = """
            {"text":"\(text)","speaker":"user","ts":1786000000,"score":0.5,
             "role":"\(role.rawValue)"}
            """
        return try! JSONDecoder().decode(Remembered.self, from: Data(json.utf8))
    }

    func testContextIsMarkedSoItIsNotStatedAsFact() {
        // The one that matters. A turn dragged in across the entity graph, shown
        // like one that matched, becomes a fact the model asserts.
        let said = RecallTool.describe(
            Recollection(
                route: "Relational",
                evidence: [
                    piece("the spare key is with Dave", role: .main),
                    piece("Dave moved away in June", role: .graphBridge),
                ]))

        let lines = said.split(separator: "\n").map(String.init)
        XCTAssertFalse(lines[0].contains("context"), lines[0])
        XCTAssertTrue(lines[1].contains("(context)"), lines[1])
    }

    func testANeighbourIsContextToo() {
        let said = RecallTool.describe(
            Recollection(route: "Local", evidence: [piece("and then we left", role: .localNeighbor)]))

        XCTAssertTrue(said.contains("(context)"), said)
    }

    func testAnEmptyStoreSaysSoRatherThanAnsweringWithNothing() {
        // Distinguishable from a failure, and from a store never written to,
        // which a model would otherwise keep asking.
        let said = RecallTool.describe(Recollection(route: "Local", evidence: []))

        XCTAssertTrue(said.contains("nothing remembered"), said)
    }

    func testWhenSomethingWasSaidIsShown() {
        // A fact from a year ago and one from this morning are different facts,
        // and nothing else in the line says which this is.
        let said = RecallTool.describe(
            Recollection(route: "Local", evidence: [piece("the bins go out Tuesday", role: .main)]))

        XCTAssertTrue(said.contains("2026-08-"), said)
        XCTAssertTrue(said.contains("bins go out Tuesday"), said)
    }

    func testTheListIsCapped() {
        let many = (1...RecallTool.limit + 4).map { piece("fact \($0)", role: .main) }
        let said = RecallTool.describe(Recollection(route: "Local", evidence: many))

        XCTAssertEqual(said.split(separator: "\n").count, RecallTool.limit)
    }

    func testRememberingSaysBackWhatLandedAndActuallyStoresIt() async throws {
        // "Done" leaves a model unable to see what was stored, so it stores it
        // again next turn.
        let store = StubRemembering()
        let said = try await RememberTool(memory: store, session: "s")
            .run(arguments: Data(#"{"text":"Dave lives next door"}"#.utf8))

        XCTAssertTrue(said.contains("Dave lives next door"), said)
        XCTAssertEqual(store.written, ["Dave lives next door"])
    }

    func testRememberingNothingIsSaidRatherThanStored() async throws {
        let store = StubRemembering()
        let said = try await RememberTool(memory: store, session: "s")
            .run(arguments: Data(#"{"text":"   "}"#.utf8))

        XCTAssertTrue(said.contains("nothing to remember"), said)
        XCTAssertTrue(store.written.isEmpty, "stored it anyway")
    }

    func testAnEmptyQueryIsSaidRatherThanSearched() async throws {
        let store = StubRemembering()
        let said = try await RecallTool(memory: store)
            .run(arguments: Data(#"{"query":"  "}"#.utf8))

        XCTAssertTrue(said.contains("no question was given"), said)
        XCTAssertTrue(store.asked.isEmpty, "searched anyway")
    }
}
