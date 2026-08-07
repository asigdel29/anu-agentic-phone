// ToolBoxTests.swift — dispatch, and every way a call can go wrong.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Almost all of these are failures, which is the right proportion: the success
// path is a dictionary lookup, and everything interesting is what the model is
// told when it asks for something that cannot be done. Those messages are its
// whole account of what happened.

import Foundation
import XCTest

@testable import WattRouter

final class ToolBoxTests: XCTestCase {
    /// A tool that echoes a required string, or does whatever it is told to.
    private struct Echo: Tool {
        struct Arguments: Decodable { let text: String }

        let name: String
        var purpose = "Repeat something back."
        var schema = #"{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"#
        /// Thrown instead of answering, for the tests that need a failure.
        var failure: (any Error)?

        func run(arguments: Data) async throws -> String {
            if let failure { throw failure }
            return try JSONDecoder().decode(Arguments.self, from: arguments).text
        }
    }

    private func call(_ name: String, _ arguments: String) -> ToolCall {
        ToolCall(id: "call-1", name: name, arguments: arguments)
    }

    func testACallReachesItsToolAndKeepsItsIdentity() async throws {
        let box = ToolBox([Echo(name: "echo")])
        let result = try await box.run(call("echo", #"{"text":"hello"}"#))

        XCTAssertEqual(result.content, "hello")
        XCTAssertFalse(result.isError)
        // Untouched: a turn may have several calls in flight.
        XCTAssertEqual(result.id, "call-1")
    }

    func testAnUnknownToolNamesTheOnesThatExist() async throws {
        let box = ToolBox([Echo(name: "echo"), Echo(name: "read_file")])
        let result = try await box.run(call("read_files", #"{}"#))

        XCTAssertTrue(result.isError)
        // A model that guessed is one list away from the right name.
        XCTAssertTrue(result.content.contains("read_file"), result.content)
        XCTAssertTrue(result.content.contains("echo"), result.content)
    }

    func testADecodingFailureNamesTheKeyAndTheProblem() async throws {
        // `localizedDescription` on a DecodingError says only that the data could
        // not be read. Which key, and what was wrong with it, is the useful part —
        // and the model is the one that has to act on it.
        let box = ToolBox([Echo(name: "echo")])
        for (arguments, expected) in [
            ("not json at all", "not valid JSON"),
            (#"{"txt":"typo"}"#, "text is missing"),
            (#"{"text":42}"#, "text should be"),
        ] {
            let result = try await box.run(call("echo", arguments))
            XCTAssertTrue(result.isError, arguments)
            XCTAssertTrue(result.content.contains(expected), result.content)
            // And the schema comes back with all three: it is the only thing
            // saying what would have worked.
            XCTAssertTrue(result.content.contains("required"), result.content)
        }
    }

    func testAToolThatFailsIsReportedRatherThanThrown() async throws {
        // The decision this type turns on: a file that is not there is something
        // the model can act on, and a turn that ended has discarded that move.
        struct NotThere: LocalizedError {
            var errorDescription: String? { "no such file: /nope" }
        }
        let box = ToolBox([Echo(name: "echo", failure: NotThere())])
        let result = try await box.run(call("echo", #"{"text":"x"}"#))

        XCTAssertTrue(result.isError)
        XCTAssertTrue(result.content.contains("no such file"), result.content)
    }

    func testCancellationIsTheOneThingThatPropagates() async {
        // "The tool was cancelled" is something a model answers by trying again.
        let box = ToolBox([Echo(name: "echo", failure: CancellationError())])
        do {
            _ = try await box.run(call("echo", #"{"text":"x"}"#))
            XCTFail("cancellation must not become a result")
        } catch is CancellationError {
        } catch {
            XCTFail("expected CancellationError, got \(error)")
        }
    }

    func testARepeatedNameLeavesDispatchAndAdvertisingAgreeing() async throws {
        // Otherwise the model is offered a tool dispatch cannot reach.
        let box = ToolBox([
            Echo(name: "echo", purpose: "the first"),
            Echo(name: "echo", purpose: "the second"),
        ])

        XCTAssertEqual(box.tools.count, 1)
        XCTAssertEqual(box.tools.first?.purpose, "the first")
        XCTAssertEqual(box["echo"]?.purpose, "the first")
    }
}
