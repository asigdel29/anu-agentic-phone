// GitWriteToolsTests.swift — what staging and committing tell the model.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The refusals get most of the attention. Both tools exist above a layer that
// already refuses well, and the failure worth catching here is this one
// flattening a sentence the model could have acted on.

import Foundation
import XCTest

@testable import WattRouter

final class GitWriteToolsTests: XCTestCase {
    private var root = URL(filePath: "/")

    override func setUpWithError() throws {
        root = URL(filePath: NSTemporaryDirectory())
            .appending(path: "git-write-tools-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: root)
    }

    private func adds(_ repository: StubRepository) throws -> GitAddTool {
        GitAddTool(repository: repository, workspace: try Workspace(root: root))
    }

    private func commits(_ repository: StubRepository) throws -> GitCommitTool {
        GitCommitTool(repository: repository, workspace: try Workspace(root: root))
    }

    func testStagingAnswersWithTheStatusRatherThanWithSuccess() async throws {
        let staged = GitStatus(
            head: .branch("main"), staged: [GitChange(path: "a.txt", kind: .added)],
            unstaged: [], untracked: [], conflicted: [])

        let said = try await adds(StubRepository(staged))
            .run(arguments: Data(#"{"paths":["a.txt"]}"#.utf8))

        // What it staged, not that it staged. "Done" leaves the model to ask.
        XCTAssertTrue(said.contains("a.txt"), said)
        XCTAssertTrue(said.contains("added"), said)
    }

    func testThePathsReachTheRepositoryAsWritten() async throws {
        let repository = StubRepository()
        _ = try await adds(repository)
            .run(arguments: Data(#"{"paths":["src/main.rs","docs"]}"#.utf8))

        XCTAssertEqual(repository.staged, [["src/main.rs", "docs"]])
        XCTAssertEqual(repository.asked.first, try Workspace(root: root).root)
    }

    func testStagingNothingIsSaidRatherThanDone() async throws {
        // An empty list succeeds at libgit2's level and tells the model it acted.
        let repository = StubRepository()
        let said = try await adds(repository).run(arguments: Data(#"{"paths":[]}"#.utf8))

        XCTAssertTrue(said.contains("nothing was staged"), said)
        XCTAssertTrue(repository.staged.isEmpty, "reached the repository anyway")
    }

    func testAMissingPathIsNamedRatherThanFlattened() async throws {
        // The layers below took trouble to name which of four paths was wrong.
        // This is the last place that could be lost.
        let repository = StubRepository(.empty, refusal: .refused("nothing at gone.txt to stage"))
        let box = ToolBox([try adds(repository)])

        let result = try await box.run(
            ToolCall(id: "c1", name: "git_add", arguments: #"{"paths":["gone.txt"]}"#))

        XCTAssertEqual(result.isError, true)
        XCTAssertTrue(result.content.contains("gone.txt"), result.content)
    }

    func testCommittingAnswersWithTheShortId() async throws {
        let repository = StubRepository()
        let said = try await commits(repository)
            .run(arguments: Data(#"{"message":"Add a thing"}"#.utf8))

        XCTAssertTrue(said.contains("a1b2c3d"), said)
        XCTAssertEqual(repository.messages, ["Add a thing"])
    }

    func testAnEmptyMessageIsRefusedBeforeTheRepositoryIsTouched() async throws {
        // libgit2 accepts one and writes a commit nobody can read afterwards.
        let repository = StubRepository()
        let said = try await commits(repository).run(arguments: Data(#"{"message":"  \n "}"#.utf8))

        XCTAssertTrue(said.contains("needs a message"), said)
        XCTAssertTrue(repository.messages.isEmpty, "wrote it anyway")
    }

    func testNothingStagedArrivesAsTheSentenceThatStopsALoop() async throws {
        // A model committing identical trees believes it is making progress, and
        // this sentence is what interrupts that. It is git.rs's, not this file's.
        let refusal = "nothing is staged, so there is nothing to commit. Stage what should go in first"
        let repository = StubRepository(.empty, refusal: .refused(refusal))
        let box = ToolBox([try commits(repository)])

        let result = try await box.run(
            ToolCall(id: "c1", name: "git_commit", arguments: #"{"message":"again"}"#))

        XCTAssertEqual(result.isError, true)
        XCTAssertTrue(result.content.hasSuffix(refusal), result.content)
    }

    func testAMissingArgumentIsADecodingFailureTheModelCanAnswer() async throws {
        // Through ToolBox, which turns it into a result rather than ending a turn.
        let box = ToolBox([try adds(StubRepository()), try commits(StubRepository())])

        for call in [
            ToolCall(id: "c1", name: "git_add", arguments: "{}"),
            ToolCall(id: "c2", name: "git_commit", arguments: "{}"),
        ] {
            let result = try await box.run(call)
            XCTAssertEqual(result.isError, true, result.content)
            XCTAssertTrue(result.content.contains("arguments"), result.content)
        }
    }
}
