// AgentTests.swift — a whole turn, and the ways one ends badly.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The two that matter are atomicity and order. A conversation left holding a tool
// call with no answer is one the provider refuses on the *next* turn, so the
// failure shows up a turn late and somewhere else; and tools that overlap are a
// race that only appears on a fast day.

import Foundation
import XCTest

@testable import WattRouter

final class AgentTests: XCTestCase {
    /// A model that reads from a script of rounds, one per call. Unsynchronised
    /// because these tests are serial and a lock would claim otherwise.
    private final class Rounds: Inference, @unchecked Sendable {
        private let rounds: [[StreamEvent]]
        private var asked = 0

        init(_ rounds: [[StreamEvent]]) { self.rounds = rounds }

        func complete(_ conversation: Conversation, model: String, maxTokens: Int?)
            -> AsyncThrowingStream<StreamEvent, any Error>
        {
            defer { asked += 1 }
            return ScriptedInference(events: asked < rounds.count ? rounds[asked] : [])
                .complete(conversation, model: model, maxTokens: maxTokens)
        }
    }

    /// A tool that records the order it was called in.
    private struct Recorder: Tool {
        let name: String
        var purpose = "Record."
        var schema = #"{"type":"object","properties":{}}"#
        let log: Log

        func run(arguments: Data) async throws -> String {
            await log.add(name)
            return "\(name) done"
        }
    }

    private actor Log {
        private(set) var names: [String] = []
        func add(_ name: String) { names.append(name) }
    }

    private func agent(_ rounds: [[StreamEvent]], tools: [any Tool] = [], maxRounds: Int = 8)
        throws -> Agent
    {
        Agent(
            router: try makeRouter(), inference: Rounds(rounds),
            tools: ToolBox(tools), maxRounds: maxRounds)
    }

    private func drain(_ agent: Agent, _ text: String) async throws -> [TurnEvent] {
        var events: [TurnEvent] = []
        for try await event in await agent.send(text) { events.append(event) }
        return events
    }

    func testATurnWithNoToolsIsOneRound() async throws {
        let agent = try agent([[.text("hello "), .text("there")]])
        let events = try await drain(agent, "hi")

        // Two messages is one round: a second would have added two more.
        let conversation = await agent.conversation
        XCTAssertEqual(conversation.messages.map(\.role), [.user, .assistant])
        XCTAssertEqual(conversation.messages.last?.content, "hello there")
        XCTAssertTrue(events.contains(.text("hello ")))
    }

    func testAToolCallIsRunAndItsResultGoesBack() async throws {
        let call = ToolCall(id: "c1", name: "first", arguments: "{}")
        let agent = try agent(
            [[.toolCall(call)], [.text("done")]], tools: [Recorder(name: "first", log: Log())])

        let events = try await drain(agent, "go")

        // Four messages is two rounds: the model was asked again after the tool.
        let conversation = await agent.conversation
        XCTAssertEqual(conversation.messages.map(\.role), [.user, .assistant, .tool, .assistant])
        XCTAssertEqual(conversation.messages[2].toolCallId, "c1")
        XCTAssertEqual(conversation.messages[2].content, "first done")
        // And whoever is watching saw it happen, not just its effect.
        XCTAssertTrue(
            events.contains(.toolResult(ToolResult(id: "c1", content: "first done"))), "\(events)")
    }

    func testToolsRunInTheOrderTheyWereAskedFor() async throws {
        // A patch then a read of the same path is a correct sequence and a race
        // if they overlap. The model that asked in that order meant it.
        let log = Log()
        let agent = try agent(
            [
                [
                    .toolCall(ToolCall(id: "1", name: "first", arguments: "{}")),
                    .toolCall(ToolCall(id: "2", name: "second", arguments: "{}")),
                    .toolCall(ToolCall(id: "3", name: "third", arguments: "{}")),
                ], [.text("done")],
            ],
            tools: [
                Recorder(name: "first", log: log), Recorder(name: "second", log: log),
                Recorder(name: "third", log: log),
            ])

        _ = try await drain(agent, "go")
        let names = await log.names
        XCTAssertEqual(names, ["first", "second", "third"])
    }

    func testAFailedRoundLeavesTheConversationSendable() async throws {
        // The failure that shows up a turn late: a conversation ending in a call
        // with no answer is one the provider refuses next time, and by then the
        // cause is somewhere else entirely.
        let agent = Agent(
            router: try makeRouter(),
            inference: ScriptedInference(
                events: [.toolCall(ToolCall(id: "c1", name: "x", arguments: "{}"))],
                failure: .unavailable(model: "m", detail: "dropped")),
            tools: ToolBox([]))

        do {
            _ = try await drain(agent, "go")
            XCTFail("the round failed")
        } catch {}

        let conversation = await agent.conversation
        XCTAssertEqual(conversation.messages.map(\.role), [.user], "a half round was committed")
    }

    func testAModelThatKeepsAskingHitsTheCapAndSaysSo() async throws {
        // Returning the last round instead would look exactly like an answer.
        let asking = [StreamEvent.toolCall(ToolCall(id: "c", name: "first", arguments: "{}"))]
        let agent = try agent(
            Array(repeating: asking, count: 20),
            tools: [Recorder(name: "first", log: Log())], maxRounds: 3)

        do {
            _ = try await drain(agent, "go")
            XCTFail("the cap should have been reported")
        } catch let error as AgentError {
            XCTAssertEqual(error, .tooManyRounds(3))
        }
    }

}
