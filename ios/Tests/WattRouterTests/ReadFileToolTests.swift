// ReadFileToolTests.swift — what the model actually sees.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Against real files: the interesting cases — a zero byte, no trailing newline, a
// line longer than the context window — are properties of real content.

import Foundation
import XCTest

@testable import WattRouter

final class ReadFileToolTests: XCTestCase {
    private var root: URL!
    private var tool: ReadFileTool!

    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("read-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { [root] in try? FileManager.default.removeItem(at: root!) }
        tool = ReadFileTool(workspace: try Workspace(root: root))
    }

    @discardableResult
    private func write(_ name: String, _ contents: String) throws -> URL {
        let url = root.appendingPathComponent(name)
        try Data(contents.utf8).write(to: url)
        return url
    }

    private func read(_ arguments: String) async throws -> String {
        try await tool.run(arguments: Data(arguments.utf8))
    }

    func testLinesComeBackNumberedAndInAColumn() async throws {
        try write("a.txt", "alpha\nbeta\ngamma\n")
        let shown = try await read(#"{"path":"a.txt"}"#)

        XCTAssertTrue(shown.contains("a.txt, lines 1-3 of 3"), shown)
        XCTAssertTrue(shown.contains("1  alpha"), shown)
        XCTAssertTrue(shown.contains("3  gamma"), shown)
    }

    func testALineIsCountedExactlyWhenItIsOne() async throws {
        // `split` on "a\n" yields ["a", ""], so a trailing newline invents a line;
        // and dropping blank ones renumbers everything after them. Both break the
        // numbers, which are the whole reason the model can describe an edit.
        try write("with.txt", "only\n")
        try write("without.txt", "only")
        try write("gaps.txt", "one\n\nthree\n")

        for name in ["with.txt", "without.txt"] {
            let shown = try await read(#"{"path":"\#(name)"}"#)
            XCTAssertTrue(shown.contains("lines 1-1 of 1"), shown)
        }
        let gaps = try await read(#"{"path":"gaps.txt"}"#)
        XCTAssertTrue(gaps.contains("lines 1-3 of 3"), gaps)
        XCTAssertTrue(gaps.contains("3  three"), gaps)
    }

    func testAPageCanBeContinuedAndTheLastOneKnowsItIsLast() async throws {
        try write("long.txt", (1...20).map { "line \($0)" }.joined(separator: "\n"))

        let first = try await read(#"{"path":"long.txt","offset":1,"limit":5}"#)
        XCTAssertTrue(first.contains("lines 1-5 of 20"), first)
        XCTAssertTrue(first.contains("offset 6"), first)
        XCTAssertFalse(first.contains("line 6"), "the page ran past its limit")

        let last = try await read(#"{"path":"long.txt","offset":16,"limit":5}"#)
        XCTAssertTrue(last.contains("lines 16-20 of 20"), last)
        XCTAssertFalse(last.contains("ask again"), last)

        // A limit past the cap is capped, not obeyed.
        let all = try await read(#"{"path":"long.txt","limit":999999}"#)
        XCTAssertTrue(all.contains("lines 1-20 of 20"), all)

        // And an offset past the end says how long the file is, rather than
        // leaving the model to guess whether the read is broken.
        let past = try await read(#"{"path":"long.txt","offset":99}"#)
        XCTAssertTrue(past.contains("has 20 lines"), past)
    }

    func testAVeryLongLineIsCutRatherThanSentWhole() async throws {
        // A minified bundle is one line. Sent whole it is the context window.
        try write("min.js", String(repeating: "x", count: ReadFileTool.maxLineLength + 500))
        let shown = try await read(#"{"path":"min.js"}"#)
        XCTAssertTrue(shown.contains("[line truncated]"), String(shown.suffix(40)))
        XCTAssertLessThan(shown.count, ReadFileTool.maxLineLength + 200)
    }

    func testAnEmptyFileSaysSo() async throws {
        try write("empty.txt", "")
        let shown = try await read(#"{"path":"empty.txt"}"#)
        XCTAssertEqual(shown, "empty.txt is empty")
    }

    func testABinaryFileIsRefusedRatherThanMangled() async throws {
        // Checked over the whole file, not a header: an archive can begin with
        // readable text.
        let url = root.appendingPathComponent("thing.bin")
        try (Data("#!/bin/sh\n".utf8) + Data([0x00, 0xFF, 0x00])).write(to: url)

        do {
            _ = try await read(#"{"path":"thing.bin"}"#)
            XCTFail("a binary file should be refused")
        } catch let error as ReadFileError {
            XCTAssertTrue(error.localizedDescription.contains("zero byte"), "\(error)")
        }
    }

    func testAMissingFileAndADirectoryAreToldApart() async throws {
        // Both are "you cannot read this"; the model does different things.
        do {
            _ = try await read(#"{"path":"nope.txt"}"#)
            XCTFail("expected a failure")
        } catch let error as ReadFileError {
            XCTAssertEqual(error, .notFound("nope.txt"))
        }

        try FileManager.default.createDirectory(
            at: root.appendingPathComponent("dir"), withIntermediateDirectories: true)
        do {
            _ = try await read(#"{"path":"dir"}"#)
            XCTFail("expected a failure")
        } catch let error as ReadFileError {
            XCTAssertEqual(error, .isDirectory("dir"))
        }
    }

    func testAPathOutsideTheWorkspaceSaysWhereTheBoundaryIs() async throws {
        // Through `ToolBox`, which reports `localizedDescription` — the reason
        // `WorkspaceError` gained one.
        let box = ToolBox([tool!])
        let result = try await box.run(
            ToolCall(id: "c1", name: "read_file", arguments: #"{"path":"../escape.txt"}"#))

        XCTAssertTrue(result.isError)
        XCTAssertTrue(result.content.contains("outside the workspace"), result.content)
        XCTAssertTrue(result.content.contains(root.lastPathComponent), result.content)
    }
}
