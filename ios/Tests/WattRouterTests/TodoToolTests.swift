// TodoToolTests.swift — the todo list seen from the model's side.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// TodoListTests covers the rules. These cover the wire: what a call has to look
// like, what comes back, and that the state is still there on the next call —
// which is the only thing an actor buys and the only thing worth checking about
// one.

import Foundation
import XCTest

@testable import WattRouter

final class TodoToolTests: XCTestCase {
    /// Run a call and parse the answer.
    private func run(_ tool: TodoTool, _ arguments: String) async throws -> [String: Any] {
        let text = try await tool.run(arguments: Data(arguments.utf8))
        return try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
    }

    func testWritingThenReadingSeesTheSamePlan() async throws {
        // The whole point of the actor. A tool that forgot between calls would
        // pass every other test in this file.
        let tool = TodoTool()
        _ = try await run(tool, #"{"todos":[{"id":"1","content":"write the parser"}]}"#)

        let answer = try await run(tool, "{}")
        let todos = try XCTUnwrap(answer["todos"] as? [[String: Any]])
        XCTAssertEqual(todos.first?["content"] as? String, "write the parser")
        XCTAssertEqual(todos.first?["status"] as? String, "pending")
    }

    func testAReadWithNoArgumentsAtAllIsStillARead() async throws {
        // Some providers render "no arguments" as empty bytes rather than `{}`.
        // Decoding those fails, which would cost a correction turn for a call
        // that was not wrong.
        let tool = TodoTool()
        let text = try await tool.run(arguments: Data())
        XCTAssertTrue(text.contains("\"todos\":[]"), text)
    }

    func testMergeReachesTheListRatherThanReplacingIt() async throws {
        let tool = TodoTool()
        _ = try await run(
            tool, #"{"todos":[{"id":"1","content":"first"},{"id":"2","content":"second"}]}"#)
        let answer = try await run(
            tool, #"{"todos":[{"id":"1","status":"completed"}],"merge":true}"#)

        let todos = try XCTUnwrap(answer["todos"] as? [[String: Any]])
        XCTAssertEqual(todos.count, 2, "merge replaced the list")
        XCTAssertEqual(todos.first?["status"] as? String, "completed")
        XCTAssertEqual(todos.first?["content"] as? String, "first", "the text was blanked")
    }

    func testTheSummaryIsAnObjectAndNamesEveryState() async throws {
        // Two things at once. `JSONEncoder` writes a dictionary with a custom key
        // type as an array of alternating keys and values — valid JSON, not an
        // object — so the model would be handed `["pending",1,"completed",1]`.
        // And every state appears even at zero, because an absent key has to be
        // inferred and a zero does not.
        let tool = TodoTool()
        let answer = try await run(
            tool,
            #"{"todos":[{"id":"1","content":"a","status":"completed"},{"id":"2","content":"b"}]}"#)

        let summary = try XCTUnwrap(answer["summary"] as? [String: Int])
        XCTAssertEqual(summary["completed"], 1)
        XCTAssertEqual(summary["pending"], 1)
        XCTAssertEqual(summary["in_progress"], 0)
        XCTAssertEqual(summary["cancelled"], 0)
        XCTAssertEqual(answer["total"] as? Int, 2)
    }

    func testABadStatusComesBackThroughDispatchWithTheSchema() async throws {
        // The divergence from the Python, end to end: a rejected status has to
        // reach the model as a message it can act on rather than as a coercion it
        // never hears about, and the schema is what tells it the four values.
        let box = ToolBox([TodoTool()])
        let result = try await box.run(
            ToolCall(
                id: "c1", name: "todo",
                arguments: #"{"todos":[{"id":"1","content":"x","status":"done"}]}"#))

        XCTAssertTrue(result.isError)
        XCTAssertTrue(result.content.contains("in_progress"), result.content)
    }

    func testTheSchemaSurvivesBeingPutOnTheWire() throws {
        // `definitions()` is the only thing that checks a schema is real JSON and
        // an object, and a hand-written one is exactly what it exists to catch.
        let definitions = try ToolBox([TodoTool()]).definitions()
        let entries = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(definitions.utf8)) as? [[String: Any]])
        let function = try XCTUnwrap(entries.first?["function"] as? [String: Any])

        XCTAssertEqual(function["name"] as? String, "todo")
        let parameters = try XCTUnwrap(function["parameters"] as? [String: Any])
        let properties = try XCTUnwrap(parameters["properties"] as? [String: Any])
        XCTAssertNotNil(properties["todos"])
        XCTAssertNotNil(properties["merge"])
    }

    func testTwoToolsDoNotShareAPlan() async throws {
        // Each instance owns its list. A static store would leak one
        // conversation's plan into the next.
        let first = TodoTool()
        let second = TodoTool()
        _ = try await run(first, #"{"todos":[{"id":"1","content":"only mine"}]}"#)

        let answer = try await run(second, "{}")
        XCTAssertEqual((answer["todos"] as? [[String: Any]])?.count, 0)
    }
}
