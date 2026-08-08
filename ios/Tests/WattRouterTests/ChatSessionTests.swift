// ChatSessionTests.swift — what a chat does between turns.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// `TurnDriverTests` covers the two ways one turn ends and `InterruptionTests` one
// turn cut short. Between them they cover a turn, and a person spends nearly all
// of their time in a chat not having one.
//
// So these are about the gaps: a second tap on send, a chat picked up after a
// while, saying something else instead of resuming, and the two guards that stop
// an interrupt or a resume happening when there is nothing to interrupt or
// resume. The last case is the mobile one — a turn cancelled while a tool is
// running, which is the half of #186 a phone actually produces, because a tool is
// where a turn spends its seconds.

import Foundation
import XCTest

@testable import WattRouter

@MainActor
final class ChatSessionTests: XCTestCase {
    /// Slow enough that a turn is reliably still running when something happens
    /// to it.
    private func slow() -> ScriptedInference {
        ScriptedInference(chunks: ["one ", "two ", "three"], perChunk: .milliseconds(300))
    }

    private func driver(_ inference: any Inference, tools: [any Tool] = []) throws -> TurnDriver {
        TurnDriver(
            agent: Agent(router: try makeRouter(), inference: inference, tools: ToolBox(tools)))
    }

    /// How many things the person is shown as having said.
    private func saidRows(_ driver: TurnDriver) -> [String] {
        driver.transcript.rows.compactMap {
            if case .said(_, let text) = $0 { return text }
            return nil
        }
    }

    func testTappingSendTwiceDoesNotStartTwoTurns() async throws {
        // The most ordinary thing a person does. Two turns over one conversation
        // interleave their rounds, and the model is then sent a transcript that
        // never happened.
        let driver = try driver(slow())

        let first = Task { await driver.send("hello") }
        try await Task.sleep(for: .milliseconds(100))
        await driver.send("hello again")
        await first.value

        XCTAssertEqual(saidRows(driver), ["hello"], "the second tap became a second turn")
        let messages = await driver.agent.conversation.messages
        XCTAssertEqual(messages.map(\.role), [.user, .assistant])
    }

    func testAChatPicksUpWhereItLeftOffAfterAnIdleGap() async throws {
        // Nothing about the driver is torn down between turns, so what this
        // catches is state left behind by the first one.
        let driver = try driver(ScriptedInference(chunks: ["the first answer"]))
        await driver.send("the first question")
        try await Task.sleep(for: .milliseconds(50))
        await driver.send("the second question")

        XCTAssertEqual(saidRows(driver), ["the first question", "the second question"])
        let messages = await driver.agent.conversation.messages
        XCTAssertEqual(messages.map(\.role), [.user, .assistant, .user, .assistant])
        XCTAssertEqual(messages.first?.content, "the first question")
    }

    func testSayingSomethingElseInsteadOfResumingLeavesTheNoticeInPlace() async throws {
        // After an interruption a person may well not want the old answer. `send`
        // is not `resume`, so a transcript that dropped the notice would claim a
        // turn was picked up that was not.
        let driver = try driver(slow())

        let running = Task { await driver.send("the interrupted one") }
        try await Task.sleep(for: .milliseconds(400))
        driver.interrupt()
        await running.value

        await driver.send("never mind, something else")

        XCTAssertEqual(saidRows(driver), ["the interrupted one", "never mind, something else"])
        let notice = driver.transcript.rows.contains {
            if case .interrupted = $0 { return true }
            return false
        }
        XCTAssertTrue(notice, "the interruption was quietly forgotten: \(driver.transcript.rows)")
        XCTAssertFalse(driver.isInterrupted, "the new turn still looks interrupted")
    }

    func testInterruptingWithNothingRunningChangesNothing() async throws {
        // Otherwise a person who backgrounds an idle chat comes back to a notice
        // about a turn that never happened.
        let driver = try driver(ScriptedInference(chunks: ["an answer"]))
        await driver.send("hello")
        let before = driver.transcript.rows

        driver.interrupt()

        XCTAssertEqual(driver.transcript.rows, before)
        XCTAssertFalse(driver.isInterrupted)
    }

    func testResumingWhileATurnIsRunningIsIgnored() async throws {
        // Two turns again, by another door.
        let driver = try driver(slow())

        let running = Task { await driver.send("hello") }
        try await Task.sleep(for: .milliseconds(100))
        await driver.resume()
        await running.value

        XCTAssertEqual(saidRows(driver), ["hello"])
        let messages = await driver.agent.conversation.messages
        XCTAssertEqual(messages.map(\.role), [.user, .assistant])
    }

    func testATurnStoppedWhileAToolIsRunningCommitsNothing() async throws {
        // #186 established that a round stopped partway commits nothing, against
        // a cancelled stream. This is the other half and the one a phone
        // produces: a tool is where a turn spends its seconds, so a turn
        // backgrounded mid-round is almost always backgrounded in one.
        let call = ToolCall(id: "c1", name: "slow", arguments: "{}")
        let driver = try driver(
            ScriptedInference(events: [.toolCall(call)]), tools: [SlowTool()])

        let running = Task { await driver.send("do the slow thing") }
        try await Task.sleep(for: .milliseconds(200))
        driver.interrupt()
        await running.value

        // Ending at the person's own message is what makes the turn re-askable:
        // a conversation holding a tool call with no answer is one the provider
        // refuses on the next turn.
        let messages = await driver.agent.conversation.messages
        XCTAssertEqual(
            messages.map(\.role), [.user],
            "a round that never finished was committed: \(messages)")
        XCTAssertTrue(driver.isInterrupted)
    }
}

/// A tool that takes longer than the turn is given.
private struct SlowTool: Tool {
    let name = "slow"
    let purpose = "Take a while."
    let schema = #"{"type":"object","properties":{}}"#

    func run(arguments: Data) async throws -> String {
        try await Task.sleep(for: .seconds(5))
        return "finished, which it should not have"
    }
}
