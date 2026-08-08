// CancelledRoundTests.swift — a round that stops partway commits nothing.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// A round is committed atomically, and #149 established that against a tool
// throwing mid-round. Cancellation is the other way a round stops and it did not
// reach the same place: a cancelled walk ends without throwing, so text that had
// stopped partway arrived at the commit looking exactly like an answer that had
// finished.
//
// The consequence is not a lost answer, which would be obvious. It is a truncated
// assistant turn left in the conversation with no tool calls on it — so the loop
// ends as though the model were done, and the next turn is sent a reply the model
// never gave.

import Foundation
import XCTest

@testable import WattRouter

final class CancelledRoundTests: XCTestCase {
    func testACancelledRoundLeavesTheConversationAsItWas() async throws {
        let agent = Agent(
            router: try makeRouter(),
            inference: ScriptedInference(
                chunks: ["one ", "two ", "three"], perChunk: .milliseconds(300)),
            tools: ToolBox([]))

        // Consume until the first fragment has arrived, then stop. Terminating
        // the stream cancels the turn behind it.
        let consumer = Task {
            for try await event in await agent.send("hello") {
                if case .text = event { break }
            }
        }
        _ = try? await consumer.value
        consumer.cancel()
        try await Task.sleep(for: .milliseconds(400))

        let messages = await agent.conversation.messages
        XCTAssertEqual(
            messages.last?.role, .user,
            "a partial answer was committed as though the model had finished: \(messages)")
        XCTAssertEqual(messages.last?.content, "hello")
    }

    func testAnUncancelledRoundStillCommits() async throws {
        // The check must not swallow ordinary turns, which is the way a fix like
        // this goes wrong.
        let agent = Agent(
            router: try makeRouter(),
            inference: ScriptedInference(chunks: ["a complete answer"]),
            tools: ToolBox([]))

        for try await _ in await agent.send("hello") {}

        let messages = await agent.conversation.messages
        XCTAssertEqual(messages.last?.role, .assistant)
        XCTAssertEqual(messages.last?.content, "a complete answer")
    }
}
