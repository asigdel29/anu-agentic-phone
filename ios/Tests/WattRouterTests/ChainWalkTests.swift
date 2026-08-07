// ChainWalkTests.swift — falling back, and refusing to.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The two halves of `upstream.rs:146` ported, plus the condition that has no
// counterpart there: a model may fail after it has already said something, and
// then no second model can be asked, whatever the error says about itself.

import XCTest

@testable import WattRouter

final class ChainWalkTests: XCTestCase {
    /// An `Inference` that answers differently depending on which model is asked.
    /// Anything unscripted fails in the retryable way, so a test only has to
    /// write down the models it cares about.
    private struct PerModel: Inference {
        let scripts: [String: ScriptedInference]

        func complete(_ conversation: Conversation, model: String, maxTokens: Int?)
            -> AsyncThrowingStream<String, any Error>
        {
            let script =
                scripts[model]
                ?? ScriptedInference(
                    chunks: [], failure: .unavailable(model: model, detail: "no script"))
            return script.complete(conversation, model: model, maxTokens: maxTokens)
        }
    }

    private func asking() -> Conversation {
        var conversation = Conversation()
        conversation.append(.user("hello there"))
        return conversation
    }

    private func remote(_ names: String...) -> [Step] {
        names.map { Step(backend: .remote, model: $0) }
    }

    private func collect(
        _ stream: AsyncThrowingStream<TurnEvent, any Error>
    ) async throws -> [TurnEvent] {
        var events: [TurnEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    func testAServerErrorFallsThroughToTheNextModel() async throws {
        let walk = ChainWalk(
            asking: PerModel(scripts: [
                "kimi-k3": ScriptedInference(
                    chunks: [], failure: .unavailable(model: "kimi-k3", detail: "500")),
                "glm-5.2": ScriptedInference(answer: "served by the fallback"),
            ]))

        let events = try await collect(
            walk.complete(asking(), following: remote("kimi-k3", "glm-5.2")))

        // The answer names the model that produced it, and it comes first: a
        // caller rendering text as it arrives has the attribution before the
        // first word, not after the last.
        XCTAssertEqual(events.first, .answering(model: "glm-5.2", backend: .remote))
        XCTAssertEqual(text(of: events), "served by the fallback")
        XCTAssertEqual(
            events.filter({ if case .answering = $0 { true } else { false } }).count, 1,
            "the serving model is named exactly once")
    }

    func testAClientErrorDoesNotTryTheNextModel() async throws {
        // The second model would answer happily. Getting its text back would mean
        // the chain spent an attempt proving what the first refusal already said.
        let walk = ChainWalk(
            asking: PerModel(scripts: [
                "kimi-k3": ScriptedInference(
                    chunks: [],
                    failure: .rejected(model: "kimi-k3", status: 400, detail: "bad request")),
                "glm-5.2": ScriptedInference(answer: "should never be asked"),
            ]))

        do {
            _ = try await collect(walk.complete(asking(), following: remote("kimi-k3", "glm-5.2")))
            XCTFail("a refused request is a failed turn")
        } catch let error as InferenceError {
            XCTAssertEqual(error, .rejected(model: "kimi-k3", status: 400, detail: "bad request"))
        }
    }

    func testAModelThatDiesPartwayIsNotRetried() async throws {
        // The condition with no counterpart in `upstream.rs`. The error says it is
        // worth another model, and it would be, had this one not already spoken.
        let walk = ChainWalk(
            asking: PerModel(scripts: [
                "kimi-k3": ScriptedInference(
                    chunks: ["half an ans"],
                    failure: .unavailable(model: "kimi-k3", detail: "connection lost")),
                "glm-5.2": ScriptedInference(answer: "a whole different answer"),
            ]))

        var events: [TurnEvent] = []
        do {
            for try await event in walk.complete(
                asking(), following: remote("kimi-k3", "glm-5.2"))
            {
                events.append(event)
            }
            XCTFail("the turn failed")
        } catch let error as InferenceError {
            XCTAssertTrue(error.isWorthAnotherModel, "the error itself is retryable")
            // And it was not retried anyway. Splicing the second model's answer
            // onto the first's half-sentence would read as one reply.
            XCTAssertEqual(text(of: events), "half an ans")
            XCTAssertEqual(events.first, .answering(model: "kimi-k3", backend: .remote))
        }
    }

    func testAnExhaustedChainReportsWhatWasTried() async throws {
        let walk = ChainWalk(asking: PerModel(scripts: [:]))

        do {
            _ = try await collect(walk.complete(asking(), following: remote("a", "b", "c")))
            XCTFail("every model failed")
        } catch let error as InferenceError {
            guard case .exhausted(let tried, let last) = error else {
                return XCTFail("expected exhaustion, got \(error)")
            }
            XCTAssertEqual(tried, 3)
            XCTAssertTrue(last.contains("c"), "the last failure is the one reported: \(last)")
        }
    }

    func testALocalStepIsCountedAndSkipped() async throws {
        // Nothing in this app can run a model in process. A local step is an
        // environment misconfiguration, and the chain goes on to the next rather
        // than dialling something that does not exist.
        let walk = ChainWalk(asking: PerModel(scripts: ["remote-one": .init(answer: "answered")]))
        let steps = [
            Step(backend: .local, model: "bonsai-27b-mlx-1bit"),
            Step(backend: .remote, model: "remote-one"),
        ]

        let events = try await collect(walk.complete(asking(), following: steps))
        XCTAssertEqual(events.first, .answering(model: "remote-one", backend: .remote))
        XCTAssertEqual(text(of: events), "answered")
    }

    func testAChainOfOnlyLocalStepsSaysWhy() async throws {
        let walk = ChainWalk(asking: PerModel(scripts: [:]))
        let steps = [Step(backend: .local, model: "bonsai-27b-mlx-1bit")]

        do {
            _ = try await collect(walk.complete(asking(), following: steps))
            XCTFail("nothing could be asked")
        } catch let error as InferenceError {
            guard case .exhausted(let tried, let last) = error else {
                return XCTFail("expected exhaustion, got \(error)")
            }
            // Counted, not silently dropped: a chain that shortened itself would
            // report `tried: 0` against a tier that has three models.
            XCTAssertEqual(tried, 1)
            XCTAssertTrue(last.contains("locally"), last)
        }
    }

    func testAnEmptyChainFailsRatherThanSucceedingSilently() async throws {
        let walk = ChainWalk(asking: PerModel(scripts: [:]))
        do {
            _ = try await collect(walk.complete(asking(), following: []))
            XCTFail("there was nothing to ask")
        } catch let error as InferenceError {
            XCTAssertEqual(error, .exhausted(tried: 0, last: "the chain was empty"))
        }
    }

    private func text(of events: [TurnEvent]) -> String {
        events.compactMap { if case .text(let t) = $0 { t } else { nil } }.joined()
    }
}
