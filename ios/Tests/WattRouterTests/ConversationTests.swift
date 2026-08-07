// ConversationTests.swift — conversation state, routed on an iOS runtime.
//
// History
//   2026-08-06  A. Sigdel  Created.
//
// These route a conversation built in Swift rather than a JSON string written by
// hand. That is the point of the type, so it is what the tests exercise: if the
// body it builds and the body the core parses ever disagree, the tier comes back
// wrong here and nowhere else.

import XCTest

@testable import WattRouter

final class ConversationTests: XCTestCase {
    func testAConversationRoutesTheSameAsAHandWrittenBody() throws {
        let router = try makeRouter()

        var conversation = Conversation()
        conversation.append(.user("hello there"))

        let built = try XCTUnwrap(router.decide(body: conversation.requestBody()))
        let handWritten = try XCTUnwrap(
            router.decide(body: #"{"messages":[{"role":"user","content":"hello there"}]}"#))

        XCTAssertEqual(built.tier, handWritten.tier)
        XCTAssertEqual(built.reason, handWritten.reason)
        XCTAssertEqual(built.tier, .mid)
    }

    func testBackgroundIsStatedRatherThanImplied() throws {
        let router = try makeRouter()
        var conversation = Conversation()
        conversation.append(.user("name this conversation"))

        // Marked explicitly, with no cap at all: the core's own fallback reads a
        // small max_tokens as housekeeping, and that guess is not needed here.
        let marked = try XCTUnwrap(
            router.decide(body: conversation.requestBody(background: true)))
        XCTAssertEqual(marked.tier, .aux)
        XCTAssertEqual(marked.reason, .background)

        // And the fallback still applies for callers that only cap the reply.
        let capped = try XCTUnwrap(router.decide(body: conversation.requestBody(maxTokens: 16)))
        XCTAssertEqual(capped.reason, .background)
    }

    func testAnUncappedConversationIsNotHousekeeping() throws {
        let router = try makeRouter()
        var conversation = Conversation()
        conversation.append(.user("write me a paragraph"))

        // A generous cap must not read as a title. The core's threshold is 32.
        let decision = try XCTUnwrap(router.decide(body: conversation.requestBody(maxTokens: 2048)))
        XCTAssertNotEqual(decision.reason, .background)
    }

    func testContentThatWouldBreakHandWrittenJSONIsOrdinaryText() throws {
        let router = try makeRouter()
        // Every one of these ends a string literal early, or is not ASCII at all.
        // Escaping belongs to the encoder; a call site should never see it.
        for hostile in [
            #"a quote " and a backslash \"#,
            "a newline\nand a tab\t",
            "emoji 🙂 and 日本語",
            #"{"messages": "already json"}"#,
            "",
        ] {
            var conversation = Conversation()
            conversation.append(.user(hostile))
            XCTAssertNotNil(
                router.decide(body: conversation.requestBody()),
                "the core rejected a body containing \(hostile.debugDescription)")
        }
    }

    func testSystemInstructionsLeadAndOrderIsKept() {
        var conversation = Conversation(system: "be brief")
        conversation.append(.user("first"))
        conversation.append(.assistant("second"))
        conversation.append(.user("third"))

        XCTAssertEqual(conversation.messages.map(\.role), [.system, .user, .assistant, .user])
        XCTAssertEqual(conversation.messages.map(\.content), ["be brief", "first", "second", "third"])
    }

    func testAConversationSurvivesBeingStoredAndRead() throws {
        // The agent loop has to persist this between launches, so the round trip
        // is part of the type's job rather than a caller's.
        var conversation = Conversation(system: "be brief")
        conversation.append(.user("hello"))

        let data = try JSONEncoder().encode(conversation)
        XCTAssertEqual(try JSONDecoder().decode(Conversation.self, from: data), conversation)
    }
}
