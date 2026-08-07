// FileWalkTests.swift — what the walk sees and what it refuses to.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Against a real tree, because every property here is one of the filesystem's:
// enumerator ordering, whether a symlinked directory is descended into, and what
// a file's reported size is.

import Foundation
import XCTest

@testable import WattRouter

final class FileWalkTests: XCTestCase {
    private var root: URL!
    private var walk: FileWalk!

    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("walk-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { [root] in try? FileManager.default.removeItem(at: root!) }
        walk = FileWalk(workspace: try Workspace(root: root))
    }

    private func make(_ relative: String, _ contents: String = "x") throws {
        let url = root.appendingPathComponent(relative)
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(contents.utf8).write(to: url)
    }

    func testEveryFileIsFoundInAStableOrder() throws {
        // Sorted rather than however the enumerator felt: a model running one
        // search twice and seeing two orderings cannot tell that nothing changed.
        try make("b.txt")
        try make("a.txt")
        try make("src/z.swift")
        try make("src/a.swift")

        let found = try walk.files().paths
        XCTAssertEqual(found, ["a.txt", "b.txt", "src/a.swift", "src/z.swift"])
    }

    func testBuildOutputIsNotDescendedInto() throws {
        try make("keep.swift")
        try make(".build/generated.swift")
        try make("node_modules/left-pad/index.js")
        try make("target/debug/thing.rs")

        XCTAssertEqual(try walk.files().paths, ["keep.swift"])
    }

    func testHiddenFilesAreNotSkippedTheWayRipgrepSkipsThem() throws {
        // The divergence. A model has every reason to read a workflow, and a tool
        // that cannot see one teaches it the file does not exist.
        try make(".github/workflows/ci.yml")
        try make(".gitignore")
        try make(".git/config")

        let found = try walk.files().paths
        XCTAssertTrue(found.contains(".github/workflows/ci.yml"), "\(found)")
        XCTAssertTrue(found.contains(".gitignore"), "\(found)")
        XCTAssertFalse(found.contains(".git/config"), "the repository's own bookkeeping")
    }

    func testAGlobWithoutASlashMatchesTheNameAtAnyDepth() throws {
        try make("a.swift")
        try make("src/deep/b.swift")
        try make("src/c.txt")

        XCTAssertEqual(try walk.files(matching: "*.swift").paths, ["a.swift", "src/deep/b.swift"])
    }

    func testAGlobWithASlashMatchesThePathAndDoesNotCrossOne() throws {
        // `FNM_PATHNAME`: `src/*.swift` is one directory deep, not any.
        try make("src/b.swift")
        try make("src/deep/c.swift")

        XCTAssertEqual(try walk.files(matching: "src/*.swift").paths, ["src/b.swift"])
    }

    func testASymlinkedDirectoryIsNotDescendedInto() throws {
        // A link pointing at an ancestor is an infinite walk, and this is the
        // property of `FileManager.enumerator` that stops it — worth a test,
        // because it is inherited rather than written.
        try make("real/a.txt")
        try FileManager.default.createSymbolicLink(
            at: root.appendingPathComponent("loop"), withDestinationURL: root)

        let found = try walk.files().paths
        XCTAssertEqual(found, ["real/a.txt"])
    }

    func testAHugeFileIsSkippedWithoutBeingRead() throws {
        try make("small.txt")
        let big = root.appendingPathComponent("big.bin")
        try Data(count: FileWalk.maxFileBytes + 1).write(to: big)

        XCTAssertEqual(try walk.files().paths, ["small.txt"])
    }

    func testTruncationIsReportedRatherThanHidden() throws {
        // A caller that cannot tell a complete answer from a cut one reports the
        // wrong thing to the model, which then believes it has seen everything.
        for index in 0..<5 { try make("f\(index).txt") }

        let all = try walk.files()
        XCTAssertFalse(all.truncated)

        let cut = try walk.files(limit: 3)
        XCTAssertEqual(cut.paths, ["f0.txt", "f1.txt", "f2.txt"])
        XCTAssertTrue(cut.truncated)
    }

    func testASubdirectoryCanBeWalkedOnItsOwn() throws {
        try make("outside.txt")
        try make("src/inside.swift")

        XCTAssertEqual(try walk.files(under: "src").paths, ["src/inside.swift"])
    }

    func testWalkingOutsideTheWorkspaceIsRefused() {
        XCTAssertThrowsError(try walk.files(under: "../elsewhere"))
    }
}
