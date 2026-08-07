// Workspace.swift — which files the tools may touch.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   WorkspaceError  Why a path was refused.
//   Workspace       A root, and paths resolved inside it.
//
// Written rather than ported. `tools/file_operations.py:836` routes every read
// and write through `self.env.execute(...)` — `cat`, `sed`, shell heredocs — so
// on that side the boundary is wherever the shell's working directory happened to
// be. iOS has no shell, so the boundary is invented here, which is the moment to
// be explicit about it.
//
// The model writes these paths, and there are four ways a containment check is
// wrong. `..` left unresolved, so `notes/../../secrets` is inside as a string and
// outside as a path. The root normalised differently from the paths compared
// against it — on Darwin `/var` is a symlink to `/private/var`, and
// `resolvingSymlinksInPath` then strips a leading `/private` again when the
// result exists, so the two spellings round-trip into each other and only putting
// both sides through the same function makes them comparable. A string prefix,
// where `/work` matches `/workshop`. And a symlink inside the root, which is
// lexically fine and lands wherever it points.
//
// The last one is why resolution happens against the deepest ancestor that
// exists: a write names a file that is not there yet, and `resolvingSymlinksInPath`
// on a path that does not exist resolves nothing.

import Foundation

/// Why a path was refused.
///
/// `LocalizedError`, not plain `Error`. These reach the model through `ToolBox`,
/// which reports `localizedDescription` — and the default is "The operation
/// couldn't be completed", which throws away the root this type carries
/// specifically so the boundary can be named.
public enum WorkspaceError: LocalizedError, Equatable, Sendable {
    /// The root is not a directory, or is not there.
    case noSuchRoot(String)
    /// The path resolved to somewhere outside the root. Carries the root,
    /// because a refusal that does not say where the boundary is leaves the model
    /// to guess, and it guesses the same thing again.
    case outside(path: String, root: String)

    public var errorDescription: String? {
        switch self {
        case .noSuchRoot(let path):
            "\(path) is not a directory"
        case .outside(let path, let root):
            "\(path) is outside the workspace, which is \(root)"
        }
    }
}

/// The directory the tools may touch, and nothing above it.
public struct Workspace: Sendable {
    /// Normalised by exactly the function `resolve` puts candidates through.
    /// Which spelling that lands on matters less than the two agreeing.
    public let root: URL

    /// - Parameter root: the directory tools are confined to. On a phone this is
    ///   somewhere in the app container.
    /// - Throws: [`WorkspaceError.noSuchRoot`] IF it is not an existing directory.
    ///   Checked now rather than on first use, so a misconfigured app fails at
    ///   startup instead of halfway through a turn.
    public init(root: URL) throws {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: root.path, isDirectory: &isDirectory),
            isDirectory.boolValue
        else { throw WorkspaceError.noSuchRoot(root.path) }

        self.root = root.resolvingSymlinksInPath().standardizedFileURL
    }

    /// Resolve a path the model wrote.
    ///
    /// - Parameter path: relative to the root, or absolute. An absolute path is
    ///   allowed when it lands inside the root, since a model told where it is
    ///   working will write one.
    /// - Returns: an absolute URL inside the root. The file need not exist.
    /// - Throws: [`WorkspaceError.outside`].
    public func resolve(_ path: String) throws -> URL {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidate =
            trimmed.hasPrefix("/")
            ? URL(fileURLWithPath: trimmed)
            : root.appendingPathComponent(trimmed)

        let resolved = Self.resolvingExistingPrefix(of: candidate.standardizedFileURL)
        guard contains(resolved) else {
            throw WorkspaceError.outside(path: path, root: root.path)
        }
        return resolved
    }

    /// The path as the model should see it: relative to the root, so a transcript
    /// does not carry a container identifier that means nothing and changes on
    /// every install.
    public func display(_ url: URL) -> String {
        let resolved = Self.resolvingExistingPrefix(of: url.standardizedFileURL)
        let inside = resolved.pathComponents.dropFirst(root.pathComponents.count)
        return inside.isEmpty ? "." : inside.joined(separator: "/")
    }

    /// Whether `url` is the root or beneath it, compared component by component.
    /// A string prefix would put `/workshop` inside `/work`.
    private func contains(_ url: URL) -> Bool {
        let base = root.pathComponents
        let candidate = url.pathComponents
        return candidate.count >= base.count && Array(candidate.prefix(base.count)) == base
    }

    /// Follow symlinks as far as the filesystem goes, then keep the rest.
    ///
    /// `resolvingSymlinksInPath` on a path that does not exist resolves nothing,
    /// and a write always names a file that does not exist yet. Walking up to the
    /// deepest existing ancestor and resolving that catches the case this is here
    /// for: a symlink inside the root pointing out of it, with a new file named
    /// beneath it.
    private static func resolvingExistingPrefix(of url: URL) -> URL {
        var missing: [String] = []
        var current = url

        while !FileManager.default.fileExists(atPath: current.path) {
            let parent = current.deletingLastPathComponent().standardizedFileURL
            // The root of the filesystem is its own parent; without this a path
            // whose every component is absent spins.
            guard parent.path != current.path else { return url }
            missing.append(current.lastPathComponent)
            current = parent
        }

        var resolved = current.resolvingSymlinksInPath()
        for component in missing.reversed() {
            resolved.appendPathComponent(component)
        }
        return resolved.standardizedFileURL
    }
}
