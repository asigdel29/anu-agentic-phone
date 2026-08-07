// ServerSentEventTests.swift — reading the provider's streamed body.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Lines taken from what an OpenAI-compatible provider actually sends, in the
// order it sends them: an opening chunk carrying the role and no text, the text
// itself, a closing chunk carrying a finish reason and no text, then `[DONE]`.
// Only the middle of that is content, and a reader that does not know the
// difference either yields empty chunks or throws on the ends.

import XCTest

@testable import WattRouter

final class ServerSentEventTests: XCTestCase {
    /// A chunk as the provider frames it, around whatever `delta` is given.
    private func line(delta: String) -> String {
        #"data: {"id":"c-1","model":"kimi-k3","choices":[{"index":0,"delta":\#(delta)}]}"#
    }

    func testTextIsRead() throws {
        XCTAssertEqual(
            try ServerSentEvent.decoding(line(delta: #"{"content":"hello"}"#)), .text("hello"))
    }

    func testAWholeCompletionYieldsOnlyItsText() throws {
        // The full sequence, ends included. Anything but `["Ada", " Lovelace"]`
        // means the reader is either inventing chunks or losing them.
        let body = [
            #"data: {"choices":[{"index":0,"delta":{"role":"assistant"}}]}"#,
            line(delta: #"{"content":"Ada"}"#),
            line(delta: #"{"content":" Lovelace"}"#),
            #"data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"#,
            "data: [DONE]",
        ]

        var text: [String] = []
        var finished = false
        for line in body {
            switch try ServerSentEvent.decoding(line) {
            case .text(let chunk): text.append(chunk)
            case .done: finished = true
            case .ignored: continue
            }
        }

        XCTAssertEqual(text, ["Ada", " Lovelace"])
        XCTAssertTrue(finished, "the stream never said it was done")
    }

    func testAnEmptyDeltaIsNotAChunk() throws {
        // Yielding "" here would commit a chain walk to a model that has not said
        // anything yet, and the whole point of the walk is that it can still move.
        for delta in [#"{"role":"assistant"}"#, "{}", #"{"content":""}"#] {
            XCTAssertEqual(try ServerSentEvent.decoding(line(delta: delta)), .ignored, delta)
        }
        XCTAssertEqual(
            try ServerSentEvent.decoding(#"data: {"choices":[]}"#), .ignored,
            "a usage-only chunk has no choices at all")
    }

    func testTheTerminatorIsRecognisedWithOrWithoutItsSpace() throws {
        // Both spellings are seen; the space after the colon is optional in the
        // format. Reading one and not the other means a stream that never ends.
        XCTAssertEqual(try ServerSentEvent.decoding("data: [DONE]"), .done)
        XCTAssertEqual(try ServerSentEvent.decoding("data:[DONE]"), .done)
    }

    func testFramingIsIgnored() throws {
        for line in [
            "",  // the blank line between events
            ": keep-alive",  // a comment
            "event: message",  // a field this client does not read
            "id: 42",
            "  data: {\"choices\":[]}",  // leading space: not a field at all
        ] {
            XCTAssertEqual(try ServerSentEvent.decoding(line), .ignored, line.debugDescription)
        }
    }

    func testMoreThanOneChoiceKeepsBothTexts() throws {
        // `choices` is an array. Taking `.first` would drop the second silently,
        // which is the failure this whole type is arranged to avoid.
        let both = #"data: {"choices":[{"delta":{"content":"left"}},"#
            + #"{"delta":{"content":"right"}}]}"#
        XCTAssertEqual(try ServerSentEvent.decoding(both), .text("leftright"))
    }

    func testAPayloadThatWillNotDecodeIsAnError() throws {
        // The deliberate strictness. A dropped `data:` line is lost text reported
        // as a complete answer, and nothing downstream could tell.
        for malformed in [
            "data: {not json",
            #"data: {"choices":{"delta":"an object where an array belongs"}}"#,
            "data:",
        ] {
            XCTAssertThrowsError(
                try ServerSentEvent.decoding(malformed), malformed.debugDescription)
        }
    }
}
