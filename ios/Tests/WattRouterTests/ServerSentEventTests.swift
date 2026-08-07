// ServerSentEventTests.swift — reading the provider's streamed body.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Lines taken from what an OpenAI-compatible provider actually sends, in the
// order it sends them: an opening chunk carrying the role and no text, the text
// itself, a closing chunk carrying a finish reason, then `[DONE]`. Only the
// middle of that is content, and a reader that does not know the difference
// either yields empty chunks or throws on the ends.
//
// One line can mean several things, so every assertion is against a list. The
// cases where it means more than one — text beside a tool call, a finish reason
// beside the fragment that triggered it — are the ones a single-event reader
// silently loses.

import XCTest

@testable import WattRouter

final class ServerSentEventTests: XCTestCase {
    /// A chunk as the provider frames it, around whatever `delta` is given.
    private func line(delta: String) -> String {
        #"data: {"id":"c-1","model":"kimi-k3","choices":[{"index":0,"delta":\#(delta)}]}"#
    }

    func testTextIsRead() throws {
        XCTAssertEqual(
            try ServerSentEvent.decoding(line(delta: #"{"content":"hello"}"#)), [.text("hello")])
    }

    func testAToolCallArrivesInFragmentsThatAreNotAssembledHere() throws {
        // The id and name come once; the arguments come a few characters at a
        // time. Reassembly needs state across lines and this is a function of
        // one, so what comes back is the fragments as they were sent.
        let opening = #"{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]}"#
        XCTAssertEqual(
            try ServerSentEvent.decoding(line(delta: opening)),
            [
                .toolCall(
                    .init(index: 0, id: "call_1", name: "read_file", arguments: ""))
            ])

        let more = #"{"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]}"#
        XCTAssertEqual(
            try ServerSentEvent.decoding(line(delta: more)),
            [.toolCall(.init(index: 0, id: nil, name: nil, arguments: #"{"pa"#))])
    }

    func testParallelToolCallsAreAllRead() throws {
        // The field is an array and a provider can fill it. Taking the first is
        // how one of two calls disappears.
        let both = #"{"tool_calls":[{"index":0,"id":"a","function":{"name":"x","arguments":""}},"#
            + #"{"index":1,"id":"b","function":{"name":"y","arguments":""}}]}"#
        let events = try ServerSentEvent.decoding(line(delta: both))

        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(
            events,
            [
                .toolCall(.init(index: 0, id: "a", name: "x", arguments: "")),
                .toolCall(.init(index: 1, id: "b", name: "y", arguments: "")),
            ])
    }

    func testTextAndAToolCallOnOneLineBothSurvive() throws {
        // The case that made this return a list. A reader picking one would drop
        // the other, and which one it dropped would depend on the order it
        // happened to check them in.
        let mixed = #"data: {"choices":[{"delta":{"content":"one moment","tool_calls":"#
            + #"[{"index":0,"id":"c","function":{"name":"z","arguments":""}}]}}]}"#
        let events = try ServerSentEvent.decoding(mixed)

        XCTAssertEqual(events.first, .text("one moment"))
        XCTAssertEqual(events.count, 2)
    }

    func testTheFinishReasonIsReadAndAnUnknownOneSurvives() throws {
        // A closed enumeration would throw on a value a provider added later,
        // failing an otherwise good stream over a word almost nothing reads.
        XCTAssertEqual(
            try ServerSentEvent.decoding(
                #"data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"#),
            [.finished(.toolCalls)])

        XCTAssertEqual(
            try ServerSentEvent.decoding(
                #"data: {"choices":[{"delta":{},"finish_reason":"something_new"}]}"#),
            [.finished(.init(rawValue: "something_new"))])
    }

    func testLengthIsToldApartFromStop() throws {
        // Not a failure, and not a complete answer either. Nothing else on the
        // wire distinguishes the two.
        XCTAssertNotEqual(ServerSentEvent.FinishReason.length, .stop)
        XCTAssertEqual(
            try ServerSentEvent.decoding(
                #"data: {"choices":[{"delta":{},"finish_reason":"length"}]}"#),
            [.finished(.length)])
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
        var reason: ServerSentEvent.FinishReason?
        var finished = false
        for event in try body.flatMap(ServerSentEvent.decoding) {
            switch event {
            case .text(let chunk): text.append(chunk)
            case .finished(let why): reason = why
            case .done: finished = true
            case .toolCall: XCTFail("no tool was asked for")
            }
        }

        XCTAssertEqual(text, ["Ada", " Lovelace"])
        XCTAssertEqual(reason, .stop)
        XCTAssertTrue(finished, "the stream never said it was done")
    }

    func testAnEmptyDeltaIsNotAChunk() throws {
        // Yielding "" here would commit a chain walk to a model that has not said
        // anything yet, and the whole point of the walk is that it can still move.
        for delta in [#"{"role":"assistant"}"#, "{}", #"{"content":""}"#] {
            XCTAssertEqual(try ServerSentEvent.decoding(line(delta: delta)), [], delta)
        }
        XCTAssertEqual(
            try ServerSentEvent.decoding(#"data: {"choices":[]}"#), [],
            "a usage-only chunk has no choices at all")
    }

    func testTheTerminatorIsRecognisedWithOrWithoutItsSpace() throws {
        // Both spellings are seen; the space after the colon is optional in the
        // format. Reading one and not the other means a stream that never ends.
        XCTAssertEqual(try ServerSentEvent.decoding("data: [DONE]"), [.done])
        XCTAssertEqual(try ServerSentEvent.decoding("data:[DONE]"), [.done])
    }

    func testFramingIsIgnored() throws {
        for line in [
            "",  // the blank line between events
            ": keep-alive",  // a comment
            "event: message",  // a field this client does not read
            "id: 42",
            "  data: {\"choices\":[]}",  // leading space: not a field at all
        ] {
            XCTAssertEqual(try ServerSentEvent.decoding(line), [], line.debugDescription)
        }
    }

    func testMoreThanOneChoiceKeepsBothTexts() throws {
        // `choices` is an array. Taking `.first` would drop the second silently,
        // which is the failure this whole type is arranged to avoid.
        let both = #"data: {"choices":[{"delta":{"content":"left"}},"#
            + #"{"delta":{"content":"right"}}]}"#
        XCTAssertEqual(try ServerSentEvent.decoding(both), [.text("leftright")])
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
