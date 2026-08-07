// MessageToolCallTests.swift — the two shapes a tool exchange needs.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Almost all of these are about the encoded form rather than the Swift value,
// because the failure they guard against is a provider rejecting a request over
// a key it did not expect — and that failure names nothing in particular, so it
// is expensive to diagnose and cheap to prevent.

import Foundation
import XCTest

@testable import WattRouter

final class MessageToolCallTests: XCTestCase {
    /// One message as the provider will see it.
    private func encoded(_ message: Message) throws -> [String: Any] {
        let data = try JSONEncoder().encode(message)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testACallIsEncodedInTheNestedShapeTheWireUses() throws {
        // Flat in Swift, nested on the wire. `ServerSentEvent` unpacks the same
        // nesting inbound, and the two halves being in one type is what keeps
        // them from drifting.
        let message = Message.assistant(
            "", toolCalls: [ToolCall(id: "call_1", name: "read_file", arguments: #"{"path":"a"}"#)])

        let calls = try XCTUnwrap(try encoded(message)["tool_calls"] as? [[String: Any]])
        XCTAssertEqual(calls.first?["id"] as? String, "call_1")
        XCTAssertEqual(calls.first?["type"] as? String, "function")

        let function = try XCTUnwrap(calls.first?["function"] as? [String: Any])
        XCTAssertEqual(function["name"] as? String, "read_file")
        // A string, not an object: the provider sends arguments as text and
        // expects them back as text, whatever they happen to contain.
        XCTAssertEqual(function["arguments"] as? String, #"{"path":"a"}"#)
    }

    func testAToolMessageNamesTheCallItAnswers() throws {
        let message = Message.tool("three lines", answering: "call_1")
        let json = try encoded(message)

        XCTAssertEqual(json["role"] as? String, "tool")
        XCTAssertEqual(json["tool_call_id"] as? String, "call_1")
        XCTAssertEqual(json["content"] as? String, "three lines")
    }

    func testAResultBecomesAToolMessageWithoutTheCallerRepeatingTheId() throws {
        // The result already knows which call it answers; making a caller restate
        // it is an invitation to state it wrong.
        let result = ToolResult(id: "call_9", content: "done")
        XCTAssertEqual(Message.tool(result), Message.tool("done", answering: "call_9"))
    }

    func testAnOrdinaryMessageCarriesNeitherKey() throws {
        // Absent, not empty. `"tool_calls": []` and `"tool_call_id": null` are
        // both things a provider may refuse, and it refuses the whole request.
        for message in [Message.user("hello"), .system("be brief"), .assistant("hi")] {
            let json = try encoded(message)
            XCTAssertNil(json["tool_calls"], "\(message.role)")
            XCTAssertNil(json["tool_call_id"], "\(message.role)")
        }
    }

    func testAnAssistantTurnThatOnlyAskedForToolsHasEmptyContent() throws {
        // Ordinary rather than a fault: a model that decided to read a file
        // before saying anything produces exactly this.
        let message = Message.assistant(
            "", toolCalls: [ToolCall(id: "c", name: "read_file", arguments: "{}")])
        let json = try encoded(message)

        XCTAssertEqual(json["content"] as? String, "")
        XCTAssertNotNil(json["tool_calls"])
    }

    func testTheWholeExchangeSurvivesBeingStoredAndRead() throws {
        // The agent persists a conversation between launches, and a tool exchange
        // that lost its ids on the way back would be a conversation the provider
        // will not accept.
        var conversation = Conversation(system: "be brief")
        conversation.append(.user("what is in a.swift?"))
        conversation.append(
            .assistant(
                "", toolCalls: [ToolCall(id: "call_1", name: "read_file", arguments: #"{"p":1}"#)]))
        conversation.append(.tool("three lines", answering: "call_1"))

        let data = try JSONEncoder().encode(conversation)
        let read = try JSONDecoder().decode(Conversation.self, from: data)

        XCTAssertEqual(read, conversation)
        // By role rather than by index: `Conversation(system:)` already put a
        // message at zero, and counting positions past it is a test that breaks
        // when somebody adds a message rather than when the code is wrong.
        XCTAssertEqual(read.messages.first { $0.role == .tool }?.toolCallId, "call_1")
        XCTAssertEqual(
            read.messages.first { $0.role == .assistant }?.toolCalls.first?.name, "read_file")
    }

    func testAMessageFromAProviderWithoutContentDecodes() throws {
        // `content: null` is what an assistant turn carrying only tool calls
        // looks like coming back, and a non-optional `String` would throw on it.
        let text = #"{"role":"assistant","content":null,"tool_calls":[{"id":"c","type":"function","#
            + #""function":{"name":"x","arguments":"{}"}}]}"#
        let message = try JSONDecoder().decode(Message.self, from: Data(text.utf8))

        XCTAssertEqual(message.content, "")
        XCTAssertEqual(message.toolCalls.first?.name, "x")
    }
}
