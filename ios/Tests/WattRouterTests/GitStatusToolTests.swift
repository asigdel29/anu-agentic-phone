// GitStatusToolTests.swift — what the model is told about the working tree.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Mostly the rendering, because that is where this tool's mistakes are: a status
// that decodes correctly and reads wrongly throws nothing, logs nothing, and is
// acted on.

import Foundation
import XCTest

@testable import WattRouter

final class GitStatusToolTests: XCTestCase {
    private var root = URL(filePath: "/")

    override func setUpWithError() throws {
        root = URL(filePath: NSTemporaryDirectory())
            .appending(path: "git-status-tool-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: root)
    }

    private func tool(_ repository: StubRepository) throws -> GitStatusTool {
        GitStatusTool(repository: repository, workspace: try Workspace(root: root))
    }

    func testACleanTreeSaysSoRatherThanAnsweringWithNothing() async throws {
        // An empty answer reads as a failed call, and gets made again.
        let said = try await tool(StubRepository()).run(arguments: Data("{}".utf8))

        XCTAssertTrue(said.contains("On branch main"), said)
        XCTAssertTrue(said.contains("Nothing staged"), said)
    }

    func testAnUnbornHeadSaysThereAreNoCommitsYet() async throws {
        // "On branch main" is true here and misleading: a model reads a branch it
        // can diff against and asks for a history that does not exist.
        let state = GitStatus(
            head: .unborn(branch: "main"), staged: [], unstaged: [], untracked: ["a.txt"],
            conflicted: [])

        let said = try await tool(StubRepository(state)).run(arguments: Data("{}".utf8))
        XCTAssertTrue(said.contains("no commits yet"), said)
    }

    func testADetachedHeadDoesNotNameABranch() async throws {
        let state = GitStatus(
            head: .detached(commit: "a1b2c3d"), staged: [], unstaged: [], untracked: [],
            conflicted: [])

        let said = try await tool(StubRepository(state)).run(arguments: Data("{}".utf8))
        XCTAssertTrue(said.contains("a1b2c3d"), said)
        XCTAssertFalse(said.contains("On branch"), "invented a branch: \(said)")
    }

    func testAConflictedPathIsNotListedAmongTheChanges() async throws {
        // The one that matters most: rendered among the changes it gets committed.
        let state = GitStatus(
            head: .branch("main"),
            staged: [], unstaged: [GitChange(path: "b.txt", kind: .modified)],
            untracked: [], conflicted: ["a.txt"])

        let said = try await tool(StubRepository(state)).run(arguments: Data("{}".utf8))
        let notStaged = try XCTUnwrap(said.range(of: "Not staged:"))
        let conflicted = try XCTUnwrap(said.range(of: "Conflicted"))

        XCTAssertTrue(said.contains("not committable"), said)
        // In its own section, below the changes rather than inside them.
        XCTAssertTrue(notStaged.lowerBound < conflicted.lowerBound, said)
        let changes = said[notStaged.upperBound..<conflicted.lowerBound]
        XCTAssertFalse(changes.contains("a.txt"), "listed as a change: \(said)")
    }

    func testEveryListIsRenderedWithItsPathsAndKinds() async throws {
        let state = GitStatus(
            head: .branch("work"),
            staged: [GitChange(path: "a.txt", kind: .added)],
            unstaged: [GitChange(path: "b.txt", kind: .typechange)],
            untracked: ["c/"], conflicted: [])

        let said = try await tool(StubRepository(state)).run(arguments: Data("{}".utf8))
        for expected in ["a.txt", "added", "b.txt", "typechange", "c/", "Untracked:"] {
            XCTAssertTrue(said.contains(expected), "\(expected) missing from: \(said)")
        }
    }

    func testItAsksAboutTheWorkspaceAndNotSomewhereTheModelNamed() async throws {
        // Why it takes no arguments: a path the model chose is what Workspace refuses.
        let repository = StubRepository()
        _ = try await tool(repository).run(arguments: Data(#"{"path":"/etc"}"#.utf8))

        XCTAssertEqual(repository.asked.count, 1)
        XCTAssertEqual(
            repository.asked.first?.path(percentEncoded: false),
            try Workspace(root: root).root.path(percentEncoded: false))
    }

    func testARefusalReachesTheModelAsItsOwnMessage() async {
        let repository = StubRepository(.empty, refusal: .refused("no git repository at /tmp/x"))
        let box = ToolBox([try! tool(repository)])

        let result = try? await box.run(
            ToolCall(id: "c1", name: "git_status", arguments: "{}"))

        // Through ToolBox rather than directly: a thrown turn is over, and this is
        // something the model can answer. ToolBox names the tool; what matters is
        // that the core's sentence reaches the end rather than becoming "the
        // operation could not be completed".
        XCTAssertEqual(result?.isError, true)
        XCTAssertEqual(result?.content, "git_status failed: no git repository at /tmp/x")
    }
}
