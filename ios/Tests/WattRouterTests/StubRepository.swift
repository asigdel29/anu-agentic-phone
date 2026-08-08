// StubRepository.swift — a repository that is not one, for the git tools.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Shared by the git tools rather than declared per test file, as StubCalendars
// is. It records rather than pretends: the tools' interesting properties are
// about what they pass down and what they do with what comes back, so what it
// was asked is as much an assertion as what it answered.

import Foundation

@testable import WattRouter

/// A repository that answers with what it was given, and remembers being asked.
final class StubRepository: Repository, @unchecked Sendable {
    /// What `status` and `add` answer with.
    var state: GitStatus
    /// What every operation throws instead of answering, if anything.
    var refusal: GitError?
    /// Every workspace it was asked about, in order.
    private(set) var asked: [URL] = []

    init(_ state: GitStatus = .empty, refusal: GitError? = nil) {
        self.state = state
        self.refusal = refusal
    }

    func head(of workspace: URL) throws(GitError) -> GitHead {
        asked.append(workspace)
        if let refusal { throw refusal }
        return state.head ?? .unborn(branch: "main")
    }

    func status(of workspace: URL) throws(GitError) -> GitStatus {
        asked.append(workspace)
        if let refusal { throw refusal }
        return state
    }

    func add(_ paths: [String], in workspace: URL) throws(GitError) -> GitStatus {
        asked.append(workspace)
        if let refusal { throw refusal }
        return state
    }

    func commit(_ message: String, in workspace: URL) throws(GitError) -> String {
        asked.append(workspace)
        if let refusal { throw refusal }
        return "a1b2c3d"
    }
}

extension GitStatus {
    /// A clean tree on a branch, which is what a case varies from.
    static let empty = GitStatus(
        head: .branch("main"), staged: [], unstaged: [], untracked: [], conflicted: [])
}
