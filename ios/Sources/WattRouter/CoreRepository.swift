// CoreRepository.swift — the routing core's git half, as a Repository.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   CoreRepository  The four operations, over the C ABI.
//
// The only place in the app that touches the git entry points, and what it owns
// is the string lifetime. Every one of them returns an allocation the caller
// gives back with wattrouter_string_free, and every path out of a call — a
// value, a refusal, an envelope that would not decode — has to reach that. One
// `defer` after one null check does it, which is why this is a type rather than
// the same four lines copied into four functions.
//
// Nothing here interprets. A refusal is the core's own message, passed up
// unchanged; the layer that knows which path was missing is the one that wrote
// it. See Repository.swift for why the decoding is not tested through this type.

import Foundation
import WattRouterFFI

/// The routing core's git half.
///
/// Stateless: the core's git entry points take a path and hold nothing between
/// calls, so there is nothing to own and one instance serves the whole app.
public struct CoreRepository: Repository {
    public init() {}

    public func head(of workspace: URL) throws(GitError) -> GitHead {
        try answered(workspace) { path in wattrouter_git_head(path) }
    }

    public func status(of workspace: URL) throws(GitError) -> GitStatus {
        try answered(workspace) { path in wattrouter_git_status(path) }
    }

    public func add(_ paths: [String], in workspace: URL) throws(GitError) -> GitStatus {
        // A JSON array is what the entry point takes, because that is the shape
        // these arrive in and the shape they are parsed back into. Encoding an
        // array of strings has no failing case; the throw is here rather than a
        // force-unwrap because a crash is not what an unreachable arm deserves.
        guard let encoded = try? JSONEncoder().encode(paths),
            let json = String(data: encoded, encoding: .utf8)
        else { throw .unreadable("the paths could not be written as JSON") }

        return try answered(workspace, json) { path, paths in
            wattrouter_git_add(path, paths)
        }
    }

    public func commit(_ message: String, in workspace: URL) throws(GitError) -> String {
        try answered(workspace, message) { path, message in
            wattrouter_git_commit(path, message)
        }
    }

    /// Call an entry point taking a path, and read its envelope.
    ///
    /// - Throws: `GitError.unanswered` if the core returned null, which needs a
    ///   path that is not UTF-8 — unreachable from a Swift `String` — or a core
    ///   built without the git feature. Otherwise what the envelope carried.
    private func answered<Value: Decodable>(
        _ workspace: URL,
        _ call: (UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>?
    ) throws(GitError) -> Value {
        try read(workspace.path(percentEncoded: false).withCString(call))
    }

    /// The same, for an entry point taking a second string.
    private func answered<Value: Decodable>(
        _ workspace: URL,
        _ second: String,
        _ call: (UnsafePointer<CChar>, UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>?
    ) throws(GitError) -> Value {
        try read(
            workspace.path(percentEncoded: false).withCString { path in
                second.withCString { second in call(path, second) }
            })
    }

    /// Take ownership of what an entry point returned, and read it.
    private func read<Value: Decodable>(
        _ returned: UnsafeMutablePointer<CChar>?
    ) throws(GitError) -> Value {
        guard let returned else { throw .unanswered }
        defer { wattrouter_string_free(returned) }
        return try CoreAnswer<Value>.value(
            from: Data(String(cString: returned).utf8), failing: GitError.self)
    }
}
