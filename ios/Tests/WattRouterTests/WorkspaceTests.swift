// WorkspaceTests.swift — every way out of the workspace, tried.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Against real directories rather than a fake filesystem, because three of the
// four failures this guards against are properties of the filesystem: symlinks,
// Darwin's normalisation of `/var` and `/private/var` into each other, and a path
// that does not exist yet resolving to nothing. A fake would have whichever of
// those its author remembered.

import Foundation
import XCTest

@testable import WattRouter

final class WorkspaceTests: XCTestCase {
    private var root: URL!
    private var workspace: Workspace!

    override func setUpWithError() throws {
        // Under the temporary directory on purpose: on Darwin it lives beneath
        // `/var`, which is a symlink to `/private/var`, so a root and a path that
        // were normalised differently would not match.
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("workspace-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { [root] in try? FileManager.default.removeItem(at: root!) }
        workspace = try Workspace(root: root)
    }

    func testARelativePathLandsInsideTheRoot() throws {
        let resolved = try workspace.resolve("notes/today.md")
        XCTAssertTrue(resolved.path.hasSuffix("notes/today.md"), resolved.path)
        XCTAssertEqual(workspace.display(resolved), "notes/today.md")
    }

    func testTwoSpellingsOfOnePathResolveTogether() throws {
        // The root and the candidates have to go through the same normalisation,
        // or the check fails for legitimate paths and gets loosened. A symlink
        // inside the root is the portable way to make one path have two spellings.
        try FileManager.default.createDirectory(
            at: root.appendingPathComponent("real"), withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(
            at: root.appendingPathComponent("link"),
            withDestinationURL: root.appendingPathComponent("real"))

        XCTAssertEqual(
            try workspace.resolve("link/file.txt").path,
            try workspace.resolve("real/file.txt").path)
        XCTAssertEqual(workspace.display(try workspace.resolve("link/file.txt")), "real/file.txt")
    }

    func testDotDotIsResolvedBeforeTheCheck() throws {
        // Inside the root as a string, outside it as a path.
        XCTAssertThrowsError(try workspace.resolve("notes/../../secrets")) { error in
            guard case .outside(_, let named)? = error as? WorkspaceError else {
                return XCTFail("expected .outside, got \(error)")
            }
            // The root is named, so the model is told where the boundary is
            // rather than left to guess the same thing again.
            XCTAssertEqual(named, workspace.root.path)
        }
    }

    func testDotDotThatStaysInsideIsFine() throws {
        // Not every `..` is an escape, and refusing them all would be a rule the
        // model cannot follow while editing a tree.
        let resolved = try workspace.resolve("notes/../src/main.swift")
        XCTAssertEqual(workspace.display(resolved), "src/main.swift")
    }

    func testASiblingWithTheRootAsAPrefixIsOutside() throws {
        // `/work` is a prefix of `/workshop`. Components, not strings.
        let sibling = root.deletingLastPathComponent()
            .appendingPathComponent(root.lastPathComponent + "-other")
        XCTAssertThrowsError(try workspace.resolve(sibling.path))
    }

    func testAnAbsolutePathInsideTheRootIsAccepted() throws {
        // A model told where it is working writes one of these.
        let resolved = try workspace.resolve(root.appendingPathComponent("x.txt").path)
        XCTAssertEqual(workspace.display(resolved), "x.txt")
    }

    func testAnAbsolutePathOutsideIsRefused() {
        XCTAssertThrowsError(try workspace.resolve("/etc/passwd"))
    }

    func testASymlinkOutOfTheRootIsFollowedAndRefused() throws {
        // Lexically inside, and it lands wherever it points.
        let outside = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("outside-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: outside) }
        try FileManager.default.createSymbolicLink(
            at: root.appendingPathComponent("escape"), withDestinationURL: outside)

        XCTAssertThrowsError(try workspace.resolve("escape")) { error in
            XCTAssertNotNil(error as? WorkspaceError)
        }
    }

    func testANewFileUnderAnEscapingSymlinkIsAlsoRefused() throws {
        // The case that makes `resolvingSymlinksInPath` insufficient on its own:
        // the file does not exist yet, so there is nothing for it to resolve, and
        // a write would land outside.
        let outside = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("outside-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: outside) }
        try FileManager.default.createSymbolicLink(
            at: root.appendingPathComponent("escape"), withDestinationURL: outside)

        XCTAssertThrowsError(try workspace.resolve("escape/new-file.txt"))
    }

    func testTheRootItselfResolvesAndDisplaysAsADot() throws {
        let resolved = try workspace.resolve(".")
        XCTAssertEqual(resolved.path, workspace.root.path)
        XCTAssertEqual(workspace.display(resolved), ".")
    }

    func testAPathWhoseEveryComponentIsMissingTerminates() throws {
        // Walking up to the deepest existing ancestor has to stop at the
        // filesystem root, which is its own parent.
        XCTAssertThrowsError(try workspace.resolve("/no/such/path/anywhere/at/all"))
    }

    func testARefusalReadsAsASentence() {
        // These reach the model through `ToolBox`, which reports
        // `localizedDescription`. The default drops the root, which is the one
        // thing the error was shaped to carry.
        let outside = WorkspaceError.outside(path: "../x", root: "/w")
        XCTAssertEqual(outside.localizedDescription, "../x is outside the workspace, which is /w")
        XCTAssertEqual(
            WorkspaceError.noSuchRoot("/nope").localizedDescription, "/nope is not a directory")
    }

    func testARootThatIsNotADirectoryIsRefusedAtStartup() throws {
        // A misconfigured app should fail here, not halfway through a turn.
        let file = root.appendingPathComponent("a-file")
        try Data("x".utf8).write(to: file)

        XCTAssertThrowsError(try Workspace(root: file))
        XCTAssertThrowsError(try Workspace(root: root.appendingPathComponent("absent")))
    }
}
