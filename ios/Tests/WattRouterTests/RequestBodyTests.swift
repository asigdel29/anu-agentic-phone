// RequestBodyTests.swift — what actually leaves the client.
//
// History
//   2026-08-09  A. Sigdel  Created with the fix for #319.
//
// ToolBoxWireTests asserts that `definitions()` builds the right JSON, and it
// did, and it was sent nowhere: `Request` had no `tools` field, so every tool
// registered in PhoneTools was invisible to the model. Every layer below the
// wire was tested and correct, which is exactly why nothing caught it — the
// suite reached in from below the seam that was missing.
//
// So these assert on the bytes of a URLRequest rather than on a helper's
// return value. Through JSONSerialization rather than by matching text, for the
// reason ToolBoxWireTests gives: what matters is the structure the provider
// parses, not how it was spelled.

import Foundation
import XCTest

@testable import WattRouter

final class RequestBodyTests: XCTestCase {
    private struct Stub: Tool {
        let name: String
        var purpose = "Do a thing."
        var schema = #"{"type":"object","properties":{"path":{"type":"string"}}}"#
        func run(arguments: Data) async throws -> String { "" }
    }

    private func client() -> NeuralWattInference {
        NeuralWattInference(apiKey: "nw-test")
    }

    private func said(_ text: String) -> Conversation {
        var conversation = Conversation()
        conversation.append(.user(text))
        return conversation
    }

    private func body(of request: URLRequest) throws -> [String: Any] {
        let data = try XCTUnwrap(request.httpBody, "the request carried no body")
        return try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any],
            "the body was not a JSON object")
    }

    func testTheRequestCarriesTheToolsTheModelMayCall() throws {
        let tools = try ToolBox([Stub(name: "read_file"), Stub(name: "write_file")])
            .definitions()

        let request = try client().request(
            said("what is in Notes"), model: "qwen3.6-35b-fast", maxTokens: nil, tools: tools)

        let carried = try XCTUnwrap(
            try body(of: request)["tools"] as? [[String: Any]],
            "no tools reached the wire")

        XCTAssertEqual(carried.count, 2)
        let named = carried.compactMap { ($0["function"] as? [String: Any])?["name"] as? String }
        XCTAssertEqual(named, ["read_file", "write_file"])
    }

    func testAToolKeepsItsSchemaAcrossTheSplice() throws {
        // The splice reopens the encoded envelope and puts a parsed array in.
        // Re-encoding is where a schema would arrive as a string rather than as
        // the object a provider needs.
        let tools = try ToolBox([Stub(name: "read_file")]).definitions()

        let request = try client().request(
            said("read it"), model: "m", maxTokens: 64, tools: tools)

        let carried = try XCTUnwrap(try body(of: request)["tools"] as? [[String: Any]])
        let function = try XCTUnwrap(carried.first?["function"] as? [String: Any])
        let parameters = try XCTUnwrap(
            function["parameters"] as? [String: Any],
            "parameters arrived as something other than a schema object")

        XCTAssertEqual(parameters["type"] as? String, "object")
        XCTAssertNotNil(parameters["properties"])
    }

    func testTheRestOfTheRequestIsUnchangedByTheSplice() throws {
        let tools = try ToolBox([Stub(name: "read_file")]).definitions()

        let request = try client().request(
            said("hello"), model: "kimi-k3", maxTokens: 128, tools: tools)
        let body = try body(of: request)

        XCTAssertEqual(body["model"] as? String, "kimi-k3")
        XCTAssertEqual(body["max_tokens"] as? Int, 128)
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertNotNil(body["messages"] as? [[String: Any]])
    }

    func testNoToolsSendsNoKeyRatherThanAnEmptyArray() throws {
        // An empty array says the model may call nothing, which is what leaving
        // the key out says. One of the two need not be sent.
        let empty = try ToolBox([]).definitions()
        XCTAssertEqual(empty, "[]")

        let request = try client().request(
            said("hello"), model: "m", maxTokens: nil, tools: empty)

        XCTAssertNil(try body(of: request)["tools"])
    }

    func testTheSameToolsAndConversationProduceTheSameBytes() throws {
        // Byte-stable, so a prompt cache can hit. definitions() sorts its keys
        // for this and the splice would undo it if it re-encoded unsorted.
        let tools = try ToolBox([Stub(name: "b"), Stub(name: "a")]).definitions()

        let first = try client().request(
            said("hello"), model: "m", maxTokens: nil, tools: tools)
        let second = try client().request(
            said("hello"), model: "m", maxTokens: nil, tools: tools)

        XCTAssertEqual(first.httpBody, second.httpBody)
    }

    func testSomethingThatIsNotAnArrayIsRefusedRatherThanSent() throws {
        XCTAssertThrowsError(
            try client().request(
                said("hello"), model: "m", maxTokens: nil, tools: #"{"not":"an array"}"#)
        ) { error in
            XCTAssertEqual(
                (error as? ToolBox.SchemaError)?.detail, "tools is not a JSON array")
        }
    }
}
