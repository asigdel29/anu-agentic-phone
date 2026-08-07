// ToolCallAssemblyTests.swift — a call, put back together.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The fragment sequences here are what a provider actually sends: an opening
// fragment with the id and name and no arguments, then arguments a few
// characters at a time, then nothing to say the call is over. Every failure mode
// is one of losing a piece — a name blanked by a later fragment, two parallel
// calls collapsing into one, arguments arriving in a different order than they
// were sent.

import Foundation
import XCTest

@testable import WattRouter

final class ToolCallAssemblyTests: XCTestCase {
    private func fragment(
        _ index: Int, id: String? = nil, name: String? = nil, arguments: String = ""
    ) -> ServerSentEvent.ToolCallFragment {
        .init(index: index, id: id, name: name, arguments: arguments)
    }

    func testACallArrivesInPiecesAndComesOutWhole() {
        var assembly = ToolCallAssembly()
        assembly.add(fragment(0, id: "call_1", name: "read_file"))
        assembly.add(fragment(0, arguments: #"{"pa"#))
        assembly.add(fragment(0, arguments: #"th":"a."#))
        assembly.add(fragment(0, arguments: #"swift"}"#))

        XCTAssertEqual(
            assembly.take(),
            [ToolCall(id: "call_1", name: "read_file", arguments: #"{"path":"a.swift"}"#)])
    }

    func testALaterFragmentDoesNotBlankTheNameOrId() {
        // Every fragment after the first carries neither, and writing them
        // unconditionally is how a call loses the name it was to be dispatched
        // by — while still looking like a call.
        var assembly = ToolCallAssembly()
        assembly.add(fragment(0, id: "call_1", name: "patch"))
        assembly.add(fragment(0, arguments: "{}"))

        let call = assembly.take().first
        XCTAssertEqual(call?.id, "call_1")
        XCTAssertEqual(call?.name, "patch")
    }

    func testParallelCallsStayApartAndComeBackInIndexOrder() {
        // Interleaved, which is how they arrive. Keyed on anything but the index
        // they would collapse into one call with both sets of arguments.
        var assembly = ToolCallAssembly()
        assembly.add(fragment(1, id: "b", name: "write_file"))
        assembly.add(fragment(0, id: "a", name: "read_file"))
        assembly.add(fragment(1, arguments: #"{"path":"b"}"#))
        assembly.add(fragment(0, arguments: #"{"path":"a"}"#))

        XCTAssertEqual(
            assembly.take(),
            [
                ToolCall(id: "a", name: "read_file", arguments: #"{"path":"a"}"#),
                ToolCall(id: "b", name: "write_file", arguments: #"{"path":"b"}"#),
            ])
    }

    func testTakingTwiceDoesNotRepeatTheSameCall() {
        // The client flushes on more than one signal — a finish reason, `[DONE]`,
        // the body ending — and a second flush that repeated everything would
        // dispatch every tool twice.
        var assembly = ToolCallAssembly()
        assembly.add(fragment(0, id: "a", name: "x", arguments: "{}"))

        XCTAssertEqual(assembly.take().count, 1)
        XCTAssertTrue(assembly.take().isEmpty)
        XCTAssertTrue(assembly.isEmpty)
    }

    func testTruncatedArgumentsAreHandedOverAsTheyAre() throws {
        // A call cut off at the cap. `ToolBox` answers unparseable arguments with
        // the decoding fault and the schema, which is what the model needs to try
        // again — so repairing or dropping them here would be a second, worse
        // copy of a policy that already exists. This checks the whole path.
        var assembly = ToolCallAssembly()
        assembly.add(fragment(0, id: "a", name: "todo", arguments: #"{"todos":[{"id"#))

        let call = try XCTUnwrap(assembly.take().first)
        XCTAssertEqual(call.arguments, #"{"todos":[{"id"#)
    }

    func testACallWithNoNameStillReachesDispatch() async throws {
        // Malformed, and dropping it would leave the model waiting on a result
        // nothing was going to produce. `ToolBox` says the tool does not exist and
        // lists the ones that do, which the model can act on.
        var assembly = ToolCallAssembly()
        assembly.add(fragment(0, arguments: "{}"))

        let call = try XCTUnwrap(assembly.take().first)
        let result = try await ToolBox([TodoTool()]).run(call)

        XCTAssertTrue(result.isError)
        XCTAssertTrue(result.content.contains("todo"), result.content)
    }

    func testNothingCollectedIsNothingTaken() {
        var assembly = ToolCallAssembly()
        XCTAssertTrue(assembly.isEmpty)
        XCTAssertTrue(assembly.take().isEmpty)
    }
}
