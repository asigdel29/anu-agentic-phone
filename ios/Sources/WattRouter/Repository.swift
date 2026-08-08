// Repository.swift — a repository's state, and the seam to whatever reads it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   GitHead     Where HEAD points.
//   GitChange   One path, and what happened to it.
//   GitStatus   The working tree, against the index and the head.
//   GitError    Why an operation could not be done.
//   GitAnswer   The envelope the core answers with.
//   Repository  The seam to whatever runs the operations.
//
// No FFI here. The conformance is its own change, as EventKit's was, and holding
// it back is what makes this testable — a Swift test cannot create a git
// repository. There is no Process on iOS, the ABI has no init, and a .git
// directory shipped in a test bundle is a fixture nobody maintains. So the
// decoding is exercised against literal JSON, which is the shape the Rust tests
// assert the core emits, and everything above this is written against the
// protocol and tested against a stub.
//
// Two decodings refuse rather than degrade. A head whose kind is not one of the
// three fails, because a fourth would arrive as neither a branch nor a detached
// commit and the app would act on whichever it defaulted to. A change whose kind
// is unknown fails for the same reason: "modified" and "deleted" are not
// interchangeable to anything that then commits.

import Foundation

/// Where `HEAD` points.
///
/// Three cases rather than an optional branch name, because the two that are not
/// a branch are not absences. A detached head has a commit and no branch; an
/// unborn one has a branch that does not exist yet, which is what `git init`
/// leaves behind and the state an agent most often finds.
public enum GitHead: Equatable, Sendable {
    /// On a branch, with at least one commit.
    case branch(String)
    /// On a commit rather than a branch, named by its short id.
    case detached(commit: String)
    /// On a branch the first commit will create.
    case unborn(branch: String)
}

extension GitHead: Decodable {
    private enum CodingKeys: String, CodingKey {
        case kind, name, commit
    }

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        switch try fields.decode(String.self, forKey: .kind) {
        case "branch": self = .branch(try fields.decode(String.self, forKey: .name))
        case "detached": self = .detached(commit: try fields.decode(String.self, forKey: .commit))
        case "unborn": self = .unborn(branch: try fields.decode(String.self, forKey: .name))
        case let other:
            throw DecodingError.dataCorruptedError(
                forKey: .kind, in: fields,
                debugDescription: "\(other) is not a head this build knows")
        }
    }
}

/// One path, and what happened to it.
public struct GitChange: Equatable, Sendable, Decodable {
    /// What happened to a path. Closed, because a kind this build does not know
    /// is a core it was not built against rather than a change to render.
    public enum Kind: String, Sendable, Decodable {
        case added, modified, deleted, renamed, typechange
    }

    /// Relative to the repository root, as git reports it.
    public let path: String
    public let kind: Kind
}

/// The working tree, against the index and the head.
public struct GitStatus: Equatable, Sendable, Decodable {
    /// Where `HEAD` points. Absent only where the core did not read it.
    public let head: GitHead?
    /// The index against the head: what a commit would write.
    public let staged: [GitChange]
    /// The working tree against the index: what a commit would leave behind.
    public let unstaged: [GitChange]
    /// Present and not in the index. A directory is named rather than walked.
    public let untracked: [String]
    /// Listed apart from the changes. A conflicted path is not something to
    /// commit, and a model told it is "modified" commits it.
    public let conflicted: [String]
}

/// Why an operation could not be done.
public enum GitError: LocalizedError, Equatable, Sendable {
    /// What the core refused, in the words it chose for the model to act on.
    case refused(String)
    /// The call produced no answer at all: a path that is not UTF-8, or a build
    /// of the core without the git feature.
    case unanswered
    /// An answer arrived and could not be read, which means the two halves were
    /// built from different sources.
    case unreadable(String)

    public var errorDescription: String? {
        switch self {
        case .refused(let why): why
        case .unanswered: "the routing core gave no answer to a git call"
        case .unreadable(let detail): "the routing core's git answer could not be read: \(detail)"
        }
    }
}

/// The envelope every entry point in the core's git half answers with.
///
/// Internal: a caller gets a value or a `GitError`, and the shape in between is
/// the boundary's business.
enum GitAnswer<Value: Decodable>: Decodable {
    case ok(Value)
    case refused(String)

    private enum CodingKeys: String, CodingKey {
        case ok, error
    }

    init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        if let why = try fields.decodeIfPresent(String.self, forKey: .error) {
            self = .refused(why)
            return
        }
        self = .ok(try fields.decode(Value.self, forKey: .ok))
    }

    /// Read one out of what the core returned.
    ///
    /// - Returns: the value the core sent.
    /// - Throws: `GitError.refused` carrying the core's own message, or
    ///   `GitError.unreadable` if the envelope did not decode.
    static func value(from json: Data) throws(GitError) -> Value {
        let envelope: Self
        do {
            envelope = try JSONDecoder().decode(Self.self, from: json)
        } catch {
            throw .unreadable(String(describing: error))
        }
        switch envelope {
        case .ok(let value): return value
        case .refused(let why): throw .refused(why)
        }
    }
}

/// The seam to whatever runs the operations.
///
/// Synchronous, as the file tools are: these are disk reads in the app's own
/// sandbox, and an `async` that never suspends says something untrue about where
/// the work happens.
public protocol Repository: Sendable {
    /// Where `HEAD` points.
    func head(of workspace: URL) throws(GitError) -> GitHead

    /// The working tree, against the index and the head.
    func status(of workspace: URL) throws(GitError) -> GitStatus

    /// Stage paths, relative to the repository root, where a directory stages
    /// what is under it.
    ///
    /// - Returns: the status after staging, so a caller need not ask again.
    /// - Throws: `GitError.refused` naming the missing path if one is missing,
    ///   in which case nothing was staged.
    func add(_ paths: [String], in workspace: URL) throws(GitError) -> GitStatus

    /// Commit what is staged.
    ///
    /// - Returns: the short id of the commit written.
    /// - Throws: `GitError.refused` if nothing is staged, which is refused rather
    ///   than written — a commit whose tree matches its parent is progress a model
    ///   believes in and does not make.
    func commit(_ message: String, in workspace: URL) throws(GitError) -> String
}
