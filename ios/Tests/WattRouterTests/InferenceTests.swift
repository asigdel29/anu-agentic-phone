// InferenceTests.swift — the seam a turn asks a model through.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Two properties matter here and the rest is bookkeeping. Chunks must reach the
// caller as they are produced, because a seam that buffers passes every other
// test and loses time-to-first-token silently — the same failure
// `the_first_chunk_arrives_long_before_the_last` exists to catch on the server.
// And a failure has to say whether another model is worth trying, because the
// chain walk above this has no other way to know.

import XCTest

@testable import WattRouter

final class InferenceTests: XCTestCase {
    /// A conversation with something in it, since an empty one is not a request.
    private func asking(_ text: String = "hello there") -> Conversation {
        var conversation = Conversation()
        conversation.append(.user(text))
        return conversation
    }

    /// Drain a stream into the chunks it yielded.
    private func collect(
        _ stream: AsyncThrowingStream<String, any Error>
    ) async throws -> [String] {
        var chunks: [String] = []
        for try await chunk in stream { chunks.append(chunk) }
        return chunks
    }

    func testAnAnswerArrivesInOrderAndJoinsBackTogether() async throws {
        let inference = ScriptedInference(answer: "the tier is chosen  before the call")
        let chunks = try await collect(
            inference.complete(asking(), model: "kimi-k3", maxTokens: nil))

        XCTAssertGreaterThan(chunks.count, 1, "a single chunk is not a stream")
        // Including the doubled space: a caller joining what arrives must get the
        // answer back, not a tidied version of it.
        XCTAssertEqual(chunks.joined(), "the tier is chosen  before the call")
    }

    func testTheFirstChunkArrivesLongBeforeTheLast() async throws {
        // The point of the seam. Buffer the stream and this is the only test here
        // that fails.
        let inference = ScriptedInference(
            chunks: (0..<5).map { "chunk \($0)\n" }, perChunk: .milliseconds(120))

        let clock = ContinuousClock()
        let started = clock.now
        var firstAt: Duration?
        for try await _ in inference.complete(asking(), model: "m", maxTokens: nil) {
            if firstAt == nil { firstAt = clock.now - started }
        }
        let total = clock.now - started

        let first = try XCTUnwrap(firstAt, "no chunk arrived at all")
        XCTAssertLessThan(
            first, total / 2,
            "first chunk at \(first) of \(total) total — the answer is being buffered")
    }

    func testAFailureBeforeAnyChunkIsWorthAnotherModel() async throws {
        // The retryable case, and the shape a chain walk depends on: nothing was
        // delivered, so a second model can still answer this request.
        let inference = ScriptedInference(
            chunks: [], failure: .unavailable(model: "kimi-k3", detail: "502"))

        do {
            _ = try await collect(inference.complete(asking(), model: "kimi-k3", maxTokens: nil))
            XCTFail("the script ends in a failure")
        } catch let error as InferenceError {
            XCTAssertEqual(error, .unavailable(model: "kimi-k3", detail: "502"))
            XCTAssertTrue(error.isWorthAnotherModel)
        }
    }

    func testWhatArrivedBeforeAFailureIsStillDelivered() async throws {
        // A model that dies partway through. The chunks it managed are the
        // caller's, and they are why this failure cannot be retried however it is
        // classified — no second model can un-deliver them.
        let inference = ScriptedInference(
            chunks: ["par", "tial"], failure: .unavailable(model: "m", detail: "connection lost"))

        var received: [String] = []
        do {
            for try await chunk in inference.complete(asking(), model: "m", maxTokens: nil) {
                received.append(chunk)
            }
            XCTFail("the script ends in a failure")
        } catch is InferenceError {
            XCTAssertEqual(received, ["par", "tial"])
        }
    }

    func testTheRetryRuleMatchesTheServer() {
        // upstream.rs:146 — a server error is worth another model, a client error
        // is not, "since the next model would reject the same body identically".
        // Written as a table so a case added later has to be classified rather
        // than inheriting whichever answer a `default` arm happened to give.
        let cases: [(InferenceError, Bool)] = [
            (.unavailable(model: "m", detail: "500"), true),
            (.rejected(model: "m", status: 400, detail: "bad request"), false),
            (.rejected(model: "m", status: 429, detail: "slow down"), false),
            (.exhausted(tried: 3, last: "500"), false),
        ]
        for (error, retryable) in cases {
            XCTAssertEqual(error.isWorthAnotherModel, retryable, "\(error)")
        }
    }

    func testLeavingTheLoopEarlyDoesNotWaitForTheRestOfTheAnswer() async throws {
        // A caller that has seen enough — a cap reached, a view dismissed — must
        // not be held until the model finishes. The producer stopping too is not
        // observable from out here; it is `onTermination` cancelling the task.
        let inference = ScriptedInference(
            chunks: (0..<20).map(String.init), perChunk: .milliseconds(100))

        let clock = ContinuousClock()
        let started = clock.now
        var received: [String] = []
        for try await chunk in inference.complete(asking(), model: "m", maxTokens: nil) {
            received.append(chunk)
            break
        }

        XCTAssertEqual(received, ["0"])
        XCTAssertLessThan(clock.now - started, .milliseconds(500), "the caller was made to drain")
    }
}
