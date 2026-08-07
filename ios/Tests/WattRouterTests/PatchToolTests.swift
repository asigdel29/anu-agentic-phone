// PatchToolTests.swift — edits that land, and edits that are refused.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The refusals matter as much as the edits: a patch that guesses which of two
// similar blocks was meant leaves a file that compiles, is wrong, and was
// reported as a success.

import Foundation
import XCTest

@testable import WattRouter

final class PatchToolTests: XCTestCase {
    private var root: URL!
    private var tool: PatchTool!

    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("patch-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { [root] in try? FileManager.default.removeItem(at: root!) }
        tool = PatchTool(workspace: try Workspace(root: root))
    }

    private func make(_ name: String, _ contents: String) throws {
        try Data(contents.utf8).write(to: root.appendingPathComponent(name))
    }

    private func contents(_ name: String) throws -> String {
        try String(contentsOf: root.appendingPathComponent(name), encoding: .utf8)
    }

    private func patch(_ arguments: String) async throws -> String {
        try await tool.run(arguments: Data(arguments.utf8))
    }

    func testAUniqueBlockIsReplacedAndTheStrategyIsNamed() async throws {
        try make("a.swift", "func a() {\n    return 1\n}\n")
        let answer = try await patch(
            #"{"path":"a.swift","old_string":"return 1","new_string":"return 2"}"#)

        XCTAssertEqual(try contents("a.swift"), "func a() {\n    return 2\n}\n")
        // Exact is an edit the model can build on; loose is one to read back.
        XCTAssertTrue(answer.contains("matched exact"), answer)
        XCTAssertTrue(answer.contains("1 place"), answer)
    }

    func testTwoOccurrencesAreRefusedWithTheirLineNumbers() async throws {
        // Taking the first silently changes the wrong one of two similar methods.
        try make("a.swift", "let x = 1\nlet y = 2\nlet x = 1\n")

        do {
            _ = try await patch(
                #"{"path":"a.swift","old_string":"let x = 1","new_string":"let x = 9"}"#)
            XCTFail("two occurrences should be refused")
        } catch let error as PatchError {
            XCTAssertEqual(error, .ambiguous(path: "a.swift", count: 2, lines: [1, 3]))
        }
        XCTAssertEqual(try contents("a.swift"), "let x = 1\nlet y = 2\nlet x = 1\n", "it edited anyway")
    }

    func testReplaceAllChangesEveryOccurrence() async throws {
        try make("a.swift", "let x = 1\nlet y = 2\nlet x = 1\n")
        let answer = try await patch(
            #"{"path":"a.swift","old_string":"let x = 1","new_string":"let x = 9","replace_all":true}"#)

        XCTAssertEqual(try contents("a.swift"), "let x = 9\nlet y = 2\nlet x = 9\n")
        XCTAssertTrue(answer.contains("2 places"), answer)
    }

    func testABlockQuotedAtTheWrongIndentationIsReindented() async throws {
        // Unchanged, the replacement breaks the file it was meant to fix.
        try make("a.swift", "class C {\n        func a() {\n            return 1\n        }\n}\n")
        _ = try await patch(
            #"{"path":"a.swift","old_string":"func a() {\n    return 1\n}","new_string":"func a() {\n    return 2\n}"}"#)

        XCTAssertEqual(
            try contents("a.swift"),
            "class C {\n        func a() {\n            return 2\n        }\n}\n")
    }

    func testAnEmptyReplacementDeletesTheBlock() async throws {
        try make("a.txt", "keep\nremove\nkeep\n")
        _ = try await patch(#"{"path":"a.txt","old_string":"remove\n","new_string":""}"#)
        XCTAssertEqual(try contents("a.txt"), "keep\nkeep\n")
    }

    func testTextThatIsNotThereSaysWhatWasLookedForAndChangesNothing() async throws {
        // It says how it looked, too, so the model does not retry the same near
        // miss respelled. And a refusal that had already written half the file
        // would be worse than the guess it avoided.
        try make("a.swift", "func encode() {}\n")

        do {
            _ = try await patch(
                #"{"path":"a.swift","old_string":"func decode() {}","new_string":"x"}"#)
            XCTFail("that text is not there")
        } catch let error as PatchError {
            let said = error.localizedDescription
            XCTAssertTrue(said.contains("func decode() {}"), said)
            XCTAssertTrue(said.contains("not by similarity"), said)
        }
        XCTAssertEqual(try contents("a.swift"), "func encode() {}\n")
    }

    func testABlankLineInAReplacementStaysBlank() throws {
        // Trailing spaces on an empty line are what line-trimmed forgives, so
        // re-indentation must not add them.
        let out = PatchTool.reindenting("a\n\nb", from: "  a", to: "      a")
        XCTAssertEqual(out, "      a\n\n      b")
    }

    func testAPathOutsideTheWorkspaceIsRefused() async throws {
        let box = ToolBox([tool!])
        let result = try await box.run(
            ToolCall(
                id: "c1", name: "patch",
                arguments: #"{"path":"../x.txt","old_string":"a","new_string":"b"}"#))

        XCTAssertTrue(result.isError)
        XCTAssertTrue(result.content.contains("outside the workspace"), result.content)
    }
}
