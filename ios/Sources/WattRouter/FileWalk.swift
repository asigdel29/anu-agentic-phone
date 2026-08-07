// FileWalk.swift — visiting the files a tool is allowed to look at.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   FileWalk  Every readable file under a path, in a stable order.
//
// Hermes shells to ripgrep, which brings a traversal, an ignore policy and binary
// detection along with it. iOS has none of those, and they are a policy about a
// tree rather than anything to do with matching, so they live apart from the
// searching that rides on them.
//
// One deliberate divergence from ripgrep: it skips every hidden entry by default,
// which is right for a person at a terminal and wrong here. A model has every
// reason to read `.github/workflows/ci.yml`, and a tool that silently cannot see
// a file teaches it the file does not exist. So the rule is a short list of
// directories that are build output by universal convention. `.git` is on it;
// `.github` is not.
//
// Order is sorted rather than whatever the enumerator gives, because a model that
// runs one search twice and gets two orderings cannot tell that nothing changed.

import Foundation

/// Every readable file under a path.
public struct FileWalk: Sendable {
    /// Never descended into. Kept short and boring on purpose: each name is a
    /// directory that is build output or version-control bookkeeping in every
    /// project that has one, so skipping it cannot hide something authored.
    public static let ignoredDirectories: Set<String> = [
        ".git", ".hg", ".svn",
        ".build", "DerivedData", ".swiftpm", "Pods",
        "node_modules", ".next",
        "target",
        ".venv", "venv", "__pycache__", ".mypy_cache", ".pytest_cache",
    ]

    /// Files larger than this are skipped rather than read. A source file is not
    /// two megabytes; a checked-in fixture or a bundle is.
    public static let maxFileBytes = 2 * 1024 * 1024

    private let workspace: Workspace

    public init(workspace: Workspace) {
        self.workspace = workspace
    }

    /// The files under `path`, sorted by their workspace-relative path.
    ///
    /// - Parameters:
    ///   - path: where to start, relative to the workspace root.
    ///   - glob: a filename pattern. Without a `/` it is matched against the file
    ///     name, with one against the relative path — the distinction `ripgrep -g`
    ///     makes, and what stops `*.swift` failing on a nested file.
    ///   - limit: most paths returned.
    /// - Returns: the paths, and whether `limit` cut the list short. A caller that
    ///   cannot tell a complete answer from a truncated one will report the wrong
    ///   thing to the model.
    /// - Throws: [`WorkspaceError`] IF `path` is outside the workspace.
    public func files(
        under path: String = ".", matching glob: String? = nil, limit: Int = .max
    ) throws -> (paths: [String], truncated: Bool) {
        let start = try workspace.resolve(path)
        var found: [String] = []

        let keys: [URLResourceKey] = [.isDirectoryKey, .isRegularFileKey, .fileSizeKey]
        // `.skipsHiddenFiles` is deliberately absent — see the note above. The
        // enumerator does not descend into symlinked directories, which is what
        // keeps a link pointing at an ancestor from being an infinite walk.
        guard
            let enumerator = FileManager.default.enumerator(
                at: start, includingPropertiesForKeys: keys, options: [])
        else { return ([], false) }

        for case let url as URL in enumerator {
            let values = try? url.resourceValues(forKeys: Set(keys))

            if values?.isDirectory == true {
                if Self.ignoredDirectories.contains(url.lastPathComponent) {
                    enumerator.skipDescendants()
                }
                continue
            }

            guard values?.isRegularFile == true,
                (values?.fileSize ?? 0) <= Self.maxFileBytes
            else { continue }

            let relative = workspace.display(url)
            guard Self.matches(glob, name: url.lastPathComponent, path: relative) else { continue }
            found.append(relative)
        }

        found.sort()
        guard found.count > limit else { return (found, false) }
        return (Array(found.prefix(limit)), true)
    }

    /// Whether a glob selects this file.
    ///
    /// `fnmatch` is in libc and already does this correctly, including character
    /// classes and escapes. Writing a glob engine in order to avoid one C call
    /// would be trading a solved problem for an unsolved one.
    static func matches(_ glob: String?, name: String, path: String) -> Bool {
        guard let glob, !glob.isEmpty else { return true }
        // A pattern naming a directory is matched against the whole path, and
        // `FNM_PATHNAME` then stops its `*` from crossing a separator, so
        // `src/*.swift` does not match `src/deep/a.swift`.
        let subject = glob.contains("/") ? path : name
        let flags = glob.contains("/") ? FNM_PATHNAME : 0
        return fnmatch(glob, subject, flags) == 0
    }
}
