// TurnLoadTests.swift — a whole turn, at the size and the rate one happens.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   TurnLoadTests  A happy path, sustained load, turns at once, and first-token
//                  latency.
//
// `RoutingPerformanceTests` measures the decision. The decision is one function
// call; a turn is a chain walk, a stream, a fold into rows, a tool dispatch and
// a commit. So the numbers in this repository described the cheapest part of a
// turn and nothing else.
//
// The happy path is here rather than in `AgentTests` because it asserts a
// different thing. Every case there pins one property in isolation — atomicity,
// ordering, the cap. None of them runs a turn through a `ToolBox` of real tools
// over a real workspace and then checks that the conversation and the transcript
// both came out the way a person would see them.
//
// The bounds are ceilings rather than numbers, for the reason
// `RoutingPerformanceTests` gives: a measurement that fails whenever the machine
// is busy teaches people to re-run it until it passes. They catch an order of
// magnitude, not a percentage. The printed figures are what a reader should look
// at.

import Foundation
import XCTest

@testable import WattRouter

final class TurnLoadTests: XCTestCase {
    /// A model reading a script of rounds, one per call.
    ///
    /// Its own type rather than `AgentTests`'s, which is private to that file.
    /// Locked rather than unsynchronised, because the concurrency case below
    /// drives several turns at once and an unlocked counter there is a data race
    /// that shows up as a flake.
    private final class Rounds: Inference, @unchecked Sendable {
        private let rounds: [[StreamEvent]]
        private let asked = NSLock()
        private var count = 0
        private let perChunk: Duration

        init(_ rounds: [[StreamEvent]], perChunk: Duration = .zero) {
            self.rounds = rounds
            self.perChunk = perChunk
        }

        func complete(_ conversation: Conversation, model: String, maxTokens: Int?)
            -> AsyncThrowingStream<StreamEvent, any Error>
        {
            asked.lock()
            let round = count % max(rounds.count, 1)
            count += 1
            asked.unlock()

            return ScriptedInference(events: rounds[round], perChunk: perChunk)
                .complete(conversation, model: model, maxTokens: maxTokens)
        }
    }

    /// A workspace holding one file, in a directory that removes itself.
    private func workspace(named name: String) throws -> (Workspace, URL) {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("wattrouter-turn-\(name)")
        try? FileManager.default.removeItem(at: root)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try "the second line is the answer\nforty two\n"
            .write(to: root.appendingPathComponent("notes.txt"), atomically: true, encoding: .utf8)
        return (try Workspace(root: root), root)
    }

    func testAWholeTurnReadsAFileAndAnswersAboutIt() async throws {
        // The shape a person sees, end to end: what they said, the tool that ran,
        // its result, and the answer — in that order, once each.
        let (workspace, root) = try workspace(named: "happy")
        defer { try? FileManager.default.removeItem(at: root) }

        let call = ToolCall(
            id: "c1", name: "read_file", arguments: #"{"path": "notes.txt"}"#)
        let agent = Agent(
            router: try makeRouter(),
            inference: Rounds([[.toolCall(call)], [.text("it says "), .text("forty two")]]),
            tools: ToolBox([ReadFileTool(workspace: workspace)]))

        var transcript = Transcript()
        transcript.said("what does notes.txt say")
        for try await event in await agent.send("what does notes.txt say") {
            transcript.apply(event)
        }

        // The conversation, as the provider will be sent it next turn.
        let messages = await agent.conversation.messages
        XCTAssertEqual(messages.map(\.role), [.user, .assistant, .tool, .assistant])
        XCTAssertEqual(messages.last?.content, "it says forty two")

        // The transcript, as the person reads it.
        XCTAssertEqual(
            transcript.rows.map(\.id).count, transcript.rows.count, "a row lost its identity")
        guard case .said(_, let asked) = transcript.rows.first else {
            return XCTFail("the first row was not what the person said: \(transcript.rows)")
        }
        XCTAssertEqual(asked, "what does notes.txt say")

        guard case .used(_, let tool, let result) = transcript.rows[1] else {
            return XCTFail("the tool did not appear: \(transcript.rows)")
        }
        XCTAssertEqual(tool, "read_file")
        XCTAssertEqual(result?.contains("forty two"), true, "the file was not read")

        guard case .answered(_, _, let answer) = transcript.rows.last else {
            return XCTFail("the turn did not end in an answer: \(transcript.rows)")
        }
        XCTAssertEqual(answer, "it says forty two")
    }

    func testAHundredTurnsDownOneConversationStayWhole() async throws {
        // The conversation grows every round and `requestBody` re-encodes all of
        // it, so this is where cost per turn is least likely to be constant.
        let agent = Agent(
            router: try makeRouter(),
            inference: Rounds([[.text("an answer")]]),
            tools: ToolBox([]))

        let turns = 100
        let started = Date()
        for index in 0..<turns {
            for try await _ in await agent.send("ask \(index)") {}
        }
        let elapsed = Date().timeIntervalSince(started)

        // Two messages a turn, and none lost: a dropped round is invisible in a
        // duration and obvious in a count.
        let messages = await agent.conversation.messages
        XCTAssertEqual(messages.count, turns * 2, "a round went missing under load")
        XCTAssertEqual(messages.last?.content, "an answer")

        XCTAssertLessThan(
            elapsed, 30.0,
            "\(turns) turns took \(elapsed)s, which is far beyond anything a phone should need")
        print(
            "turn load: \(turns) turns in \(elapsed)s, "
                + "\(elapsed / Double(turns) * 1000)ms each")
    }

    func testTurnsAtOnceDoNotReachIntoEachOther() async throws {
        // One actor per agent and one ToolBox shared between them. What this
        // catches is a tool that is accidentally stateful across agents, which
        // shows up as an answer landing in the wrong conversation.
        let shared = ToolBox([TodoTool()])
        let agents = try (0..<8).map { _ in
            Agent(
                router: try makeRouter(),
                inference: Rounds([[.text("an answer")]]),
                tools: shared)
        }

        try await withThrowingTaskGroup(of: Void.self) { group in
            for (index, agent) in agents.enumerated() {
                group.addTask {
                    for _ in 0..<10 {
                        for try await _ in await agent.send("from \(index)") {}
                    }
                }
            }
            try await group.waitForAll()
        }

        for (index, agent) in agents.enumerated() {
            let messages = await agent.conversation.messages
            XCTAssertEqual(messages.count, 20, "agent \(index) lost a round")
            XCTAssertEqual(
                messages.first?.content, "from \(index)", "agent \(index) has somebody else's turn")
        }
    }

    func testTheFirstFragmentArrivesLongBeforeTheTurnEnds() async throws {
        // The number a person feels, which is not the turn's duration and is not
        // derivable from it. Twenty chunks at 20ms is a turn of at least 400ms;
        // the first of them should be there almost at once.
        let agent = Agent(
            router: try makeRouter(),
            inference: Rounds(
                [Array(repeating: StreamEvent.text("word "), count: 20)],
                perChunk: .milliseconds(20)),
            tools: ToolBox([]))

        let started = Date()
        var first: TimeInterval?
        for try await event in await agent.send("say something") {
            if case .text = event, first == nil {
                first = Date().timeIntervalSince(started)
            }
        }
        let whole = Date().timeIntervalSince(started)

        let latency = try XCTUnwrap(first, "no text ever arrived")
        XCTAssertLessThan(latency, whole / 2, "the stream was buffered rather than streamed")
        print("first fragment: \(latency * 1000)ms, whole turn: \(whole * 1000)ms")
    }
}
