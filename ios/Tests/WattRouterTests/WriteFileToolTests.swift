// WriteFileToolTests.swift — putting a file back, and saying which.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The pair with ReadFileToolTests, and the round trip between them is the test
// that matters most: what one tool writes, the other has to read back unchanged
// and count the same way.

import Foundation
import XCTest

@testable import WattRouter

final class WriteFileToolTests: XCTestCase {
    private var root: URL!
    private var tool: WriteFileTool!
    private var reader: ReadFileTool!

    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("write-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { [root] in try? FileManager.default.removeItem(at: root!) }

        let workspace = try Workspace(root: root)
        tool = WriteFileTool(workspace: workspace)
        reader = ReadFileTool(workspace: workspace)
    }

    private func write(_ arguments: String) async throws -> String {
        try await tool.run(arguments: Data(arguments.utf8))
    }

    private func contents(_ name: String) throws -> String {
        try String(contentsOf: root.appendingPathComponent(name), encoding: .utf8)
    }

    func testANewFileIsCreatedAndSaysSo() async throws {
        let answer = try await write(#"{"path":"a.txt","content":"hello\n"}"#)

        XCTAssertTrue(answer.hasPrefix("created a.txt"), answer)
        XCTAssertEqual(try contents("a.txt"), "hello\n")
    }

    func testReplacingIsCalledReplacing() async throws {
        // A model that cannot tell a new file from an overwrite cannot tell that
        // it has just destroyed something.
        _ = try await write(#"{"path":"a.txt","content":"first"}"#)
        let answer = try await write(#"{"path":"a.txt","content":"second"}"#)

        XCTAssertTrue(answer.hasPrefix("replaced a.txt"), answer)
        XCTAssertEqual(try contents("a.txt"), "second")
    }

    func testMissingDirectoriesAreMade() async throws {
        // Otherwise a model writing into a new tree needs a second tool it has
        // not got.
        let answer = try await write(#"{"path":"src/parser/lexer.swift","content":"enum L {}\n"}"#)

        XCTAssertTrue(answer.contains("src/parser/lexer.swift"), answer)
        XCTAssertEqual(try contents("src/parser/lexer.swift"), "enum L {}\n")
    }

    func testWhatIsWrittenIsWhatIsReadBack() async throws {
        // The round trip, and the reason the two tools count lines the same way.
        // Content chosen to break a naive one: no trailing newline, a blank line
        // in the middle, and characters that are not ASCII.
        let content = "first\n\nthird — with an em dash and 🙂"
        _ = try await write(#"{"path":"round.txt","content":"first\n\nthird — with an em dash and 🙂"}"#)

        XCTAssertEqual(try contents("round.txt"), content)
        let shown = try await reader.run(arguments: Data(#"{"path":"round.txt"}"#.utf8))
        XCTAssertTrue(shown.contains("lines 1-3 of 3"), shown)
        XCTAssertTrue(shown.contains("🙂"), shown)
    }

    func testTheLineCountAgreesWithTheReader() async throws {
        // Two answers about one file that disagreed would be worse than neither.
        for content in ["one\n", "one", "one\ntwo\n", "one\n\nthree"] {
            let escaped = content.replacingOccurrences(of: "\n", with: #"\n"#)
            let written = try await write(#"{"path":"c.txt","content":"\#(escaped)"}"#)
            let read = try await reader.run(arguments: Data(#"{"path":"c.txt"}"#.utf8))

            let lines = content.hasSuffix("\n") ? String(content.dropLast()) : content
            let expected = lines.split(separator: "\n", omittingEmptySubsequences: false).count
            XCTAssertTrue(written.contains("\(expected) line"), written)
            XCTAssertTrue(read.contains("of \(expected)"), read)
        }
    }

    func testAnEmptyFileIsWrittenAndSaidToBeEmpty() async throws {
        // Legitimate — clearing a file — and "0 lines, 0 bytes" reads like a
        // failure rather than an outcome.
        let answer = try await write(#"{"path":"blank.txt","content":""}"#)

        XCTAssertEqual(answer, "created blank.txt, now empty")
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("blank.txt").path))
    }

    func testWritingOverADirectoryIsRefusedBeforeAnythingHappens() async throws {
        try FileManager.default.createDirectory(
            at: root.appendingPathComponent("dir"), withIntermediateDirectories: true)

        do {
            _ = try await write(#"{"path":"dir","content":"x"}"#)
            XCTFail("a directory is not a file")
        } catch let error as WriteFileError {
            XCTAssertEqual(error, .isDirectory("dir"))
        }
    }

    func testAPathOutsideTheWorkspaceWritesNothing() async throws {
        let escape = root.deletingLastPathComponent().appendingPathComponent("escaped.txt")

        let box = ToolBox([tool!])
        let result = try await box.run(
            ToolCall(id: "c1", name: "write_file", arguments: #"{"path":"../escaped.txt","content":"x"}"#))

        XCTAssertTrue(result.isError)
        XCTAssertTrue(result.content.contains("outside the workspace"), result.content)
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: escape.path), "it was written anyway")
    }
}
