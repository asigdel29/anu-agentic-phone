// RepositoryTests.swift — the core's git answers, read as Swift values.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The literals here are the shape `router/src/ffi_git.rs` is asserted to emit.
// Neither side can prove the other right on its own — a Swift test cannot make a
// repository to round-trip against — so both assert the same literal, and a
// change to one that does not change the other fails here.

import Foundation
import XCTest

@testable import WattRouter

final class RepositoryTests: XCTestCase {
    private func head(_ json: String) throws -> GitHead {
        try CoreAnswer<GitHead>.value(from: Data(json.utf8), failing: GitError.self)
    }

    private func status(_ json: String) throws -> GitStatus {
        try CoreAnswer<GitStatus>.value(from: Data(json.utf8), failing: GitError.self)
    }

    func testEachHeadKindArrivesAsItsOwnCase() throws {
        XCTAssertEqual(try head(#"{"ok":{"kind":"branch","name":"main"}}"#), .branch("main"))
        XCTAssertEqual(
            try head(#"{"ok":{"kind":"detached","commit":"a1b2c3d"}}"#),
            .detached(commit: "a1b2c3d"))
        XCTAssertEqual(
            try head(#"{"ok":{"kind":"unborn","name":"main"}}"#), .unborn(branch: "main"))
    }

    func testAHeadKindThisBuildDoesNotKnowFailsRatherThanDefaulting() {
        // A fourth kind read as one of the three is the app acting confidently on
        // a repository state it has never seen.
        XCTAssertThrowsError(try head(#"{"ok":{"kind":"bisecting","name":"main"}}"#)) { error in
            guard let git = error as? GitError, case .unreadable = git else {
                return XCTFail("read it as something: \(error)")
            }
        }
    }

    func testAStatusArrivesWithEveryListWhole() throws {
        let read = try status(
            """
            {"ok":{"head":{"kind":"branch","name":"main"},
             "staged":[{"path":"a.txt","kind":"added"}],
             "unstaged":[{"path":"b.txt","kind":"modified"}],
             "untracked":["c/"],"conflicted":["d.txt"]}}
            """)

        XCTAssertEqual(read.head, .branch("main"))
        XCTAssertEqual(read.staged, [GitChange(path: "a.txt", kind: .added)])
        XCTAssertEqual(read.unstaged, [GitChange(path: "b.txt", kind: .modified)])
        XCTAssertEqual(read.untracked, ["c/"])
        // Apart from the changes on purpose: a conflicted path read as modified is
        // a path a model commits.
        XCTAssertEqual(read.conflicted, ["d.txt"])
    }

    func testAnEmptyStatusIsEmptyListsRatherThanAbsentOnes() throws {
        let read = try status(
            """
            {"ok":{"head":{"kind":"unborn","name":"main"},
             "staged":[],"unstaged":[],"untracked":[],"conflicted":[]}}
            """)

        XCTAssertEqual(read.head, .unborn(branch: "main"))
        XCTAssertTrue(read.staged.isEmpty)
        XCTAssertTrue(read.conflicted.isEmpty)
    }

    func testARefusalArrivesAsTheCoresOwnWords() {
        // Not reworded here. The message was written for the model to act on, and
        // this layer knows less about the repository than the one that wrote it.
        XCTAssertThrowsError(try status(#"{"error":"nothing at gone.txt to stage"}"#)) { error in
            XCTAssertEqual(error as? GitError, .refused("nothing at gone.txt to stage"))
            XCTAssertEqual(
                (error as? GitError)?.errorDescription, "nothing at gone.txt to stage")
        }
    }

    func testACommitIdArrivesAsAStringRatherThanAnObject() throws {
        // The one entry point whose ok is a scalar, so the envelope is exercised
        // against something other than a keyed container.
        let id = try CoreAnswer<String>.value(from: Data(#"{"ok":"a1b2c3d"}"#.utf8), failing: GitError.self)
        XCTAssertEqual(id, "a1b2c3d")
    }

    func testAnEnvelopeWithNeitherKeyIsUnreadableRatherThanEmpty() {
        XCTAssertThrowsError(try status("{}")) { error in
            guard let git = error as? GitError, case .unreadable = git else {
                return XCTFail("read an answer out of nothing: \(error)")
            }
        }
    }

    func testAChangeKindThisBuildDoesNotKnowFails() {
        // The five kinds are the core's vocabulary. A sixth means the two halves
        // were built from different sources, which is worth failing over.
        XCTAssertThrowsError(
            try status(
                #"{"ok":{"head":null,"staged":[{"path":"a","kind":"exploded"}],"#
                    + #""unstaged":[],"untracked":[],"conflicted":[]}}"#))
    }
}
