// TodoListTests.swift — the rules a plan is kept under.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Mostly about merge and the caps, because those are where a port goes wrong
// quietly: a merge that reorders a plan reads as the model changing its mind, and
// a cap that cuts in the wrong place produces a list that is fine until the day
// it is long.

import Foundation
import XCTest

@testable import WattRouter

final class TodoListTests: XCTestCase {
    private func edit(_ id: String, _ content: String? = nil, _ status: TodoStatus? = nil)
        -> TodoEdit
    {
        TodoEdit(id: id, content: content, status: status)
    }

    func testReplacingKeepsOnlyWhatWasSent() {
        var list = TodoList()
        list.write([edit("1", "first"), edit("2", "second")], merge: false)
        list.write([edit("3", "third")], merge: false)

        XCTAssertEqual(list.items.map(\.id), ["3"])
        XCTAssertEqual(list.items.first?.content, "third")
    }

    func testMergeChangesOnlyTheFieldsThatWereSent() {
        // The reason `TodoEdit` exists. Sending a status must not blank the text
        // the model wrote three turns ago and did not repeat.
        var list = TodoList()
        list.write([edit("1", "write the parser"), edit("2", "write the tests")], merge: false)
        list.write([edit("1", nil, .completed)], merge: true)

        XCTAssertEqual(list.items.count, 2)
        XCTAssertEqual(list.items.first?.content, "write the parser")
        XCTAssertEqual(list.items.first?.status, .completed)
        XCTAssertEqual(list.items.last?.status, .pending, "the untouched item is untouched")
    }

    func testMergeAppendsWhatItHasNotSeen() {
        var list = TodoList()
        list.write([edit("1", "first")], merge: false)
        list.write([edit("2", "second", .inProgress)], merge: true)

        XCTAssertEqual(list.items.map(\.id), ["1", "2"])
        XCTAssertEqual(list.items.last?.status, .inProgress)
    }

    func testMergeDoesNotReorderThePlan() {
        // Order is priority. An update to the first item that moved it to the end
        // would read as the model deprioritising its own work.
        var list = TodoList()
        list.write([edit("a"), edit("b"), edit("c")], merge: false)
        list.write([edit("a", nil, .completed)], merge: true)

        XCTAssertEqual(list.items.map(\.id), ["a", "b", "c"])
    }

    func testARepeatedIdKeepsTheLastInTheFirstsPosition() {
        // A model listing an id twice has revised it, and revising should not
        // move it down the list.
        var list = TodoList()
        list.write([edit("a", "first try"), edit("b", "other"), edit("a", "revised")], merge: false)

        XCTAssertEqual(list.items.map(\.id), ["a", "b"])
        XCTAssertEqual(list.items.first?.content, "revised")
    }

    func testAnItemWithNoIdIsNotSilentlyUnique() {
        // Without an id there is nothing to tell two items apart by; giving each
        // a fresh identity would make the next merge unpredictable.
        var list = TodoList()
        list.write([edit("", "one"), edit("   ", "two")], merge: false)

        XCTAssertEqual(list.items.count, 1)
        XCTAssertEqual(list.items.first?.id, "?")
        XCTAssertEqual(list.items.first?.content, "two")
    }

    func testMissingFieldsTakeDefaultsWhenReplacing() {
        var list = TodoList()
        list.write([edit("1"), edit("2", "   ")], merge: false)

        XCTAssertEqual(list.items.map(\.status), [.pending, .pending])
        XCTAssertEqual(list.items.map(\.content), ["(no description)", "(no description)"])
    }

    func testAnOversizedItemIsCutToTheCap() {
        var list = TodoList()
        list.write([edit("1", String(repeating: "x", count: TodoList.maxContent + 500))], merge: false)

        let content = list.items.first?.content ?? ""
        // At the cap including the marker, not the cap plus it — an item that
        // announced its own truncation by going over would be its own bug.
        XCTAssertEqual(content.count, TodoList.maxContent)
        XCTAssertTrue(content.hasSuffix("… [truncated]"), String(content.suffix(20)))
    }

    func testTruncationCountsCharactersRatherThanBytes() {
        // A cut measured in bytes lands inside a multi-byte character, and the
        // list then carries a replacement glyph the model never wrote.
        var list = TodoList()
        list.write([edit("1", String(repeating: "🙂", count: TodoList.maxContent))], merge: false)

        let content = list.items.first?.content ?? ""
        XCTAssertEqual(content.count, TodoList.maxContent)
        XCTAssertFalse(content.contains("\u{FFFD}"), "a character was cut in half")
    }

    func testTooManyItemsKeepsTheHead() {
        // Order is priority, so the overflow to drop is the tail.
        var list = TodoList()
        list.write((0..<(TodoList.maxItems + 10)).map { edit("\($0)", "item \($0)") }, merge: false)

        XCTAssertEqual(list.items.count, TodoList.maxItems)
        XCTAssertEqual(list.items.first?.id, "0")
        XCTAssertEqual(list.items.last?.id, "\(TodoList.maxItems - 1)")
    }

    func testAnUnknownStatusIsRejectedRatherThanCoerced() throws {
        // The divergence from the Python, which turns this into `pending`. A model
        // told nothing believes the item is done and plans from that; the decoding
        // failure costs one turn and names the four values that work.
        let json = Data(#"{"id":"1","content":"x","status":"done"}"#.utf8)
        XCTAssertThrowsError(try JSONDecoder().decode(TodoEdit.self, from: json))
    }

    func testTheSummaryCountsEachState() {
        var list = TodoList()
        list.write(
            [
                edit("1", "a", .completed), edit("2", "b", .completed),
                edit("3", "c", .inProgress), edit("4", "d"),
            ], merge: false)

        XCTAssertEqual(list.summary[.completed], 2)
        XCTAssertEqual(list.summary[.inProgress], 1)
        XCTAssertEqual(list.summary[.pending], 1)
        XCTAssertNil(list.summary[.cancelled], "a state nothing is in is absent, not zero")
    }
}
