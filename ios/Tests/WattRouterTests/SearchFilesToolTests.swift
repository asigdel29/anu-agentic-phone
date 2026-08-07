// SearchFilesToolTests.swift — what a search finds, and what it admits it missed.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The cases that matter are the ones where a wrong answer looks like a right one:
// smart case returning a different set than was asked for, and a cap that says
// nothing about having cut.

import Foundation
import XCTest

@testable import WattRouter

final class SearchFilesToolTests: XCTestCase {
    private var root: URL!
    private var tool: SearchFilesTool!

    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("search-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { [root] in try? FileManager.default.removeItem(at: root!) }
        tool = SearchFilesTool(workspace: try Workspace(root: root))
    }

    private func make(_ relative: String, _ contents: String) throws {
        let url = root.appendingPathComponent(relative)
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(contents.utf8).write(to: url)
    }

    private func search(_ arguments: String) async throws -> String {
        try await tool.run(arguments: Data(arguments.utf8))
    }

    func testAMatchCarriesItsFileAndLine() async throws {
        try make("src/a.swift", "import Foundation\n\nfunc decode() {}\n")
        let found = try await search(#"{"pattern":"func decode"}"#)
        XCTAssertTrue(found.contains("src/a.swift:3: func decode() {}"), found)
        XCTAssertTrue(found.contains("1 match(es) in 1 file(s)"), found)
    }

    func testSmartCaseIsInsensitiveUntilYouAskOtherwise() async throws {
        // Wrong either way this returns a different set than was asked for, and
        // nothing says so.
        try make("a.txt", "TODO: one\ntodo: two\nTodoList three\n")

        let loose = try await search(#"{"pattern":"todo"}"#)
        XCTAssertTrue(loose.contains("3 match(es)"), loose)

        let exact = try await search(#"{"pattern":"TodoList"}"#)
        XCTAssertTrue(exact.contains("1 match(es)"), exact)
    }

    func testTheIncludeGlobNarrowsWhichFilesAreRead() async throws {
        try make("a.swift", "needle\n")
        try make("b.txt", "needle\n")
        let found = try await search(#"{"pattern":"needle","include":"*.swift"}"#)

        XCTAssertTrue(found.contains("a.swift") && !found.contains("b.txt"), found)
    }

    func testFilesModeTakesAGlobRatherThanARegex() async throws {
        try make("src/parser.swift", "")
        try make("README.md", "")
        let found = try await search(#"{"pattern":"*.swift","target":"files"}"#)

        XCTAssertTrue(found.contains("src/parser.swift") && !found.contains("README.md"), found)
    }

    func testACapSaysThatItCutAndSilenceMeansItDidNot() async throws {
        // A search that hits its limit in silence has told the model it saw every
        // occurrence there is — and one that cries truncation when it did not is
        // just as misleading the other way.
        try make("many.txt", (1...20).map { "needle \($0)" }.joined(separator: "\n"))
        let cut = try await search(#"{"pattern":"needle","limit":5}"#)
        XCTAssertTrue(cut.contains("cut at 5") && cut.contains("there are more"), cut)

        try make("few.txt", "solitary\n")
        let whole = try await search(#"{"pattern":"solitary","limit":5}"#)
        XCTAssertFalse(whole.contains("cut at"), whole)
    }

    func testNoMatchSaysSoRatherThanReturningNothing() async throws {
        try make("a.txt", "nothing here\n")
        let found = try await search(#"{"pattern":"needle"}"#)
        XCTAssertEqual(found, "no match for needle")
    }

    func testWhatIsNotSearchedIsNotSearched() async throws {
        // A binary — the `read_file` rule minus the explanation, since a search
        // skips silently — and a build directory, which the walk never enters.
        try Data([0x00, 0x6E, 0x00]).write(to: root.appendingPathComponent("thing.bin"))
        try make(".build/generated.swift", "needle\n")
        try make("keep.swift", "needle\n")

        let found = try await search(#"{"pattern":"needle"}"#)
        XCTAssertTrue(found.contains("1 match(es) in 1 file(s)"), found)
        XCTAssertFalse(found.contains("thing.bin"), found)
        XCTAssertFalse(found.contains(".build"), found)
    }

    func testALongLineIsCutToALocator() async throws {
        // A minified bundle would otherwise be the whole result.
        try make("min.js", String(repeating: "n", count: SearchFilesTool.maxLineLength + 400))
        let found = try await search(#"{"pattern":"nnn"}"#)

        XCTAssertLessThan(found.count, SearchFilesTool.maxLineLength + 100)
        XCTAssertTrue(found.hasSuffix("…"), String(found.suffix(20)))
    }

    func testABadRegexComesBackNamingTheProblem() async throws {
        // The model wrote it and can fix it, but only if told what is wrong.
        let box = ToolBox([tool!])
        let result = try await box.run(
            ToolCall(id: "c1", name: "search_files", arguments: #"{"pattern":"func ([a-z"}"#))

        XCTAssertTrue(result.isError)
        XCTAssertTrue(result.content.contains("not a valid regular expression"), result.content)
    }
}
