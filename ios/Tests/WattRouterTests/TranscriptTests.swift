// TranscriptTests.swift — the fold, over the sequences that are awkward.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The typical sequence is one line and would pass against almost any
// implementation. What is worth pinning is the rest: a fragment arriving before
// any model announced itself, a call whose result never comes, two rounds in one
// turn, and a failure partway that must leave what was already shown alone.

import Foundation
import XCTest

@testable import WattRouter

final class TranscriptTests: XCTestCase {
    private func call(_ name: String, id: String = "call-1") -> ToolCall {
        ToolCall(id: id, name: name, arguments: "{}")
    }

    func testTextArrivingInFragmentsIsOneAnswer() {
        var transcript = Transcript()
        transcript.said("hello")
        transcript.apply(.answering(model: "kimi", backend: .remote))
        transcript.apply(.text("one "))
        transcript.apply(.text("two "))
        transcript.apply(.text("three"))

        XCTAssertEqual(
            transcript.rows,
            [
                .said(id: 0, text: "hello"),
                .answered(id: 1, model: "kimi", text: "one two three"),
            ])
    }

    func testTextBeforeAModelAnnouncesItselfIsNotLost() {
        // ChainWalk sends `answering` first, but a fragment that arrives without
        // one is text somebody wrote, and dropping it is the worse failure.
        var transcript = Transcript()
        transcript.apply(.text("orphan"))
        transcript.apply(.answering(model: "qwen", backend: .remote))
        transcript.apply(.text(" adopted"))

        XCTAssertEqual(
            transcript.rows, [.answered(id: 0, model: "qwen", text: "orphan adopted")])
    }

    func testACallIsShownBeforeItsResultArrives() {
        // The gap between the two is where a person waits, so the call has to be
        // on screen during it rather than appearing complete afterwards.
        var transcript = Transcript()
        transcript.apply(.toolCall(call("read_file")))
        XCTAssertEqual(transcript.rows, [.used(id: 0, tool: "read_file", result: nil)])

        transcript.apply(.toolResult(ToolResult(id: "call-1", content: "12 lines")))
        XCTAssertEqual(transcript.rows, [.used(id: 0, tool: "read_file", result: "12 lines")])
    }

    func testResultsFillTheirOwnCallsInOrder() {
        var transcript = Transcript()
        transcript.apply(.toolCall(call("patch", id: "a")))
        transcript.apply(.toolCall(call("read_file", id: "b")))
        transcript.apply(.toolResult(ToolResult(id: "a", content: "patched")))

        XCTAssertEqual(
            transcript.rows,
            [
                .used(id: 0, tool: "patch", result: "patched"),
                .used(id: 1, tool: "read_file", result: nil),
            ])
    }

    func testTextAfterAToolIsANewAnswerRatherThanTheSameParagraph() {
        // Two rounds in one turn. Appending the second round's text to the first
        // would read as one thought and hide that a tool ran between them.
        var transcript = Transcript()
        transcript.apply(.answering(model: "kimi", backend: .remote))
        transcript.apply(.text("looking"))
        transcript.apply(.toolCall(call("search_files")))
        transcript.apply(.toolResult(ToolResult(id: "call-1", content: "3 hits")))
        transcript.apply(.text("found it"))

        XCTAssertEqual(
            transcript.rows,
            [
                .answered(id: 0, model: "kimi", text: "looking"),
                .used(id: 1, tool: "search_files", result: "3 hits"),
                .answered(id: 2, model: nil, text: "found it"),
            ])
    }

    func testAFailurePartwayKeepsWhatWasAlreadyShown() {
        var transcript = Transcript()
        transcript.said("do the thing")
        transcript.apply(.answering(model: "kimi", backend: .remote))
        transcript.apply(.text("half an ans"))
        transcript.failed("the connection went away")

        XCTAssertEqual(
            transcript.rows,
            [
                .said(id: 0, text: "do the thing"),
                .answered(id: 1, model: "kimi", text: "half an ans"),
                .failed(id: 2, reason: "the connection went away"),
            ])
    }

    func testSpeakingAgainClosesAnAnswerLeftOpen() {
        // Otherwise the next turn's first fragment lands in the last turn's
        // paragraph, and the two models read as one.
        var transcript = Transcript()
        transcript.apply(.text("unfinished"))
        transcript.said("never mind")
        transcript.apply(.text("fresh"))

        XCTAssertEqual(
            transcript.rows,
            [
                .answered(id: 0, model: nil, text: "unfinished"),
                .said(id: 1, text: "never mind"),
                .answered(id: 2, model: nil, text: "fresh"),
            ])
    }

    func testARowKeepsItsIdentityWhileItGrows() {
        // A list animates a row whose identity changed. Text arriving must not
        // look like a new row every fragment.
        var transcript = Transcript()
        transcript.apply(.text("a"))
        let first = transcript.rows[0].id
        transcript.apply(.text("b"))

        XCTAssertEqual(transcript.rows[0].id, first)
        XCTAssertEqual(transcript.rows.count, 1)
    }

    func testAResultWithNoCallWaitingChangesNothing() {
        var transcript = Transcript()
        transcript.apply(.text("hello"))
        let before = transcript.rows
        transcript.apply(.toolResult(ToolResult(id: "nobody", content: "orphan")))

        XCTAssertEqual(transcript.rows, before)
    }
}
