// TurnDriverTests.swift — the two ways a turn ends.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// A turn finishes or it throws, and the difference has to reach the transcript
// rather than a log nobody reads. That is the whole reason the driver is a type
// rather than a closure inside a view, so it is what these check.

import Foundation
import XCTest

@testable import WattRouter

@MainActor
final class TurnDriverTests: XCTestCase {
    private func makeDriver(_ inference: any Inference) throws -> TurnDriver {
        let agent = Agent(
            router: try makeRouter(),
            inference: inference,
            tools: ToolBox([]))
        return TurnDriver(agent: agent)
    }

    func testAnAnswerReachesTheTranscript() async throws {
        let driver = try makeDriver(ScriptedInference(chunks: ["one ", "two"]))
        await driver.send("hello")

        XCTAssertEqual(driver.transcript.rows.first, .said(id: 0, text: "hello"))
        guard case .answered(_, _, let text) = driver.transcript.rows.last else {
            return XCTFail("no answer: \(driver.transcript.rows)")
        }
        XCTAssertEqual(text, "one two")
        XCTAssertFalse(driver.isRunning)
    }

    func testAFailedTurnSaysWhyRatherThanVanishing() async throws {
        // A model that produces nothing and then fails. What the person sees has
        // to be a reason, not a turn that silently stopped.
        let driver = try makeDriver(
            ScriptedInference(chunks: [], failure: .rejected(model: "m", status: 400, detail: "no")))
        await driver.send("hello")

        XCTAssertEqual(driver.transcript.rows.first, .said(id: 0, text: "hello"))
        guard case .failed(_, let reason) = driver.transcript.rows.last else {
            return XCTFail("no failure row: \(driver.transcript.rows)")
        }
        XCTAssertFalse(reason.isEmpty, "the reason was empty, which explains nothing")
        XCTAssertFalse(driver.isRunning, "a failed turn left the driver looking busy")
    }

    func testEmptyTextIsNotATurn() async throws {
        let driver = try makeDriver(ScriptedInference(chunks: ["ignored"]))
        await driver.send("   \n ")

        XCTAssertTrue(driver.transcript.rows.isEmpty, "whitespace started a turn")
    }

    func testTheDriverIsNotBusyAfterwards() async throws {
        // isRunning gates the composer. Left true, the field never comes back.
        let driver = try makeDriver(ScriptedInference(chunks: ["a"]))
        XCTAssertFalse(driver.isRunning)
        await driver.send("hello")
        XCTAssertFalse(driver.isRunning)
    }
}
