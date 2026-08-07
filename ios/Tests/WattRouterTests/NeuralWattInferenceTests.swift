// NeuralWattInferenceTests.swift — the call that leaves the device.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Against a stubbed transport, so these are free, fast, and can produce a 503 on
// demand. Two properties a working client can lose with no other test noticing:
// nothing is handed over before the status is known, and chunks arrive as made.

import Foundation
import XCTest

@testable import WattRouter

final class NeuralWattInferenceTests: XCTestCase {
    private func asking() -> Conversation {
        var conversation = Conversation()
        conversation.append(.user("hello there"))
        return conversation
    }

    /// A client whose transport is the stub, on the real configuration.
    private func client() -> NeuralWattInference {
        let configuration = URLSessionConfiguration.upstream()
        configuration.protocolClasses = [StubTransport.self]
        return NeuralWattInference(
            apiKey: "ios-test", session: URLSession(configuration: configuration))
    }

    /// One completion event, framed as the provider frames it.
    private func event(_ text: String) -> String {
        #"data: {"choices":[{"delta":{"content":"\#(text)"}}]}"# + "\n\n"
    }

    func testNothingIsYieldedBeforeTheStatusIsKnown() async throws {
        // A body that is a perfectly good completion, behind a status that is not.
        // A client reading ahead would deliver "leaked" and fail afterwards, and
        // the caller's rule for whether to try another model would then be wrong.
        StubTransport.script = .init(status: 500, chunks: [event("leaked"), "data: [DONE]\n"])

        var received: [String] = []
        do {
            for try await chunk in client().complete(asking(), model: "m", maxTokens: nil) {
                received.append(chunk)
            }
            XCTFail("a 500 is a failure")
        } catch let error as InferenceError {
            XCTAssertEqual(received, [], "text was delivered out of a failed response")
            XCTAssertTrue(error.isWorthAnotherModel, "a 5xx is worth another model")
        }
    }

    func testATransportErrorIsWorthAnotherModel() async throws {
        // The other half of the same rule, and the reason it needs saying twice in
        // Swift: a 503 is a perfectly successful `URLResponse` while a lost network
        // is a thrown `URLError`. Two unrelated paths, one answer.
        StubTransport.script = .init(transportError: URLError(.notConnectedToInternet))

        do {
            for try await _ in client().complete(asking(), model: "m", maxTokens: nil) {}
            XCTFail("an unreachable provider is a failure")
        } catch let error as InferenceError {
            XCTAssertTrue(error.isWorthAnotherModel, "\(error)")
        }
    }

    func testARefusalQuotesWhatTheProviderSaid() async throws {
        // Without this a 400 is a status and nothing else: every model in the
        // chain refused, and no way to tell whether the fault was the request, the
        // credential or the account.
        StubTransport.script = .init(
            status: 400, chunks: [#"{"error":{"message":"unknown parameter: temperture"}}"#])

        do {
            for try await _ in client().complete(asking(), model: "m", maxTokens: nil) {}
            XCTFail("a 400 is a failure")
        } catch let error as InferenceError {
            guard case .rejected(_, let status, let detail) = error else {
                return XCTFail("a 4xx is a refusal, not \(error)")
            }
            XCTAssertEqual(status, 400)
            XCTAssertTrue(detail.contains("unknown parameter"), detail)
        }
    }

    func testAFailureWithNothingToSayStillSaysSo() async throws {
        // An empty body is common enough — a proxy returning a bare 502 — and
        // "HTTP 502: " with nothing after it reads like the message was lost.
        StubTransport.script = .init(status: 502)

        do {
            for try await _ in client().complete(asking(), model: "m", maxTokens: nil) {}
            XCTFail("a 502 is a failure")
        } catch let error as InferenceError {
            XCTAssertTrue(String(describing: error).contains("no message"), "\(error)")
        }
    }

    func testTheRequestNamesTheModelAndAsksForAStream() async throws {
        StubTransport.script = .init(chunks: ["data: [DONE]\n"])
        for try await _ in client().complete(asking(), model: "kimi-k3", maxTokens: 256) {}

        let sent = try XCTUnwrap(StubTransport.sent)
        XCTAssertEqual(sent.headers["Authorization"], "Bearer ios-test")

        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: sent.body) as? [String: Any])
        XCTAssertEqual(body["model"] as? String, "kimi-k3")
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertEqual(body["max_tokens"] as? Int, 256)
        // The classification key is the router's, not the provider's: the decision
        // it feeds was taken before this call was made.
        XCTAssertNil(body["x_wattrouter_background"])
    }

    func testTheFirstChunkArrivesLongBeforeTheLast() async throws {
        // The property `upstream.rs` exists to protect, on this side of the wire.
        // Swap `bytes(for:)` for `data(for:)` and this is the only test that
        // notices.
        StubTransport.script = .init(
            chunks: (0..<5).map { event("chunk \($0)") } + ["data: [DONE]\n"], perChunk: 0.12)

        let clock = ContinuousClock()
        let started = clock.now
        var firstAt: Duration?
        var received: [String] = []
        for try await chunk in client().complete(asking(), model: "m", maxTokens: nil) {
            if firstAt == nil { firstAt = clock.now - started }
            received.append(chunk)
        }
        let total = clock.now - started

        XCTAssertEqual(received, (0..<5).map { "chunk \($0)" })
        let first = try XCTUnwrap(firstAt, "no chunk arrived at all")
        XCTAssertLessThan(
            first, total / 2,
            "first chunk at \(first) of \(total) total — the answer is being buffered")
    }
}
