// GitStatusTool.swift — showing the model what git sees.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   GitStatusTool  The working tree, as a tool.
//
// The rendering is the part with the mistakes in it. A status is five lists and a
// head; handed over as JSON it is legible and wasteful, and handed over badly it
// is worse than nothing. Three things that read as details and are not.
//
// An unborn head says so. "On branch main" is true of a repository with no
// commits and misleading about it — a model reads a branch it can diff against
// and asks for a history that is not there.
//
// A conflicted path is listed apart from the changes. git.rs separates them for
// exactly this reason, and one list produces a path the model stages and commits.
//
// A clean tree says it is clean. An empty answer reads as a failed call, and the
// next thing a model does with one is make it again.
//
// No arguments: the repository is the workspace, and a path argument would be one
// the model chooses, which is the thing `Workspace` exists to stop.

import Foundation

/// The working tree, against the index and the head.
public struct GitStatusTool: Tool {
    public let name = "git_status"

    public let purpose = """
        What git sees in the workspace: which branch you are on, what is staged, \
        what is changed but not staged, and what is untracked. Takes no \
        arguments. Read this before staging or committing — it is the only way \
        to know what a commit would actually write.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {},
          "additionalProperties": false
        }
        """

    private let repository: any Repository
    private let workspace: Workspace

    public init(repository: any Repository, workspace: Workspace) {
        self.repository = repository
        self.workspace = workspace
    }

    /// - Returns: the status, rendered for reading.
    /// - Throws: nothing the model can act on. A refusal is its message, which
    ///   `ToolBox` reports as a result.
    ///
    /// # Rely
    /// The workspace root exists. Reading one is disk work that does not suspend.
    public func run(arguments: Data) async throws -> String {
        Self.describe(try repository.status(of: workspace.root))
    }

    /// One status, as lines.
    ///
    /// Static so that the rendering can be exercised without a repository, which
    /// is the half of this that has decisions in it.
    static func describe(_ status: GitStatus) -> String {
        var lines = [heading(status.head)]

        lines += section("Staged", status.staged)
        lines += section("Not staged", status.unstaged)
        if !status.untracked.isEmpty {
            lines.append("")
            lines.append("Untracked:")
            lines += status.untracked.map { "  \($0)" }
        }
        // Its own section, named for what it is: a conflicted path rendered among
        // the changes is a path that gets committed.
        if !status.conflicted.isEmpty {
            lines.append("")
            lines.append("Conflicted, and not committable until resolved:")
            lines += status.conflicted.map { "  \($0)" }
        }

        if lines.count == 1 {
            lines.append("Nothing staged, nothing changed, nothing untracked.")
        }
        return lines.joined(separator: "\n")
    }

    /// The first line: where `HEAD` is, in words that do not overstate it.
    private static func heading(_ head: GitHead?) -> String {
        switch head {
        case .branch(let name):
            "On branch \(name)."
        case .detached(let commit):
            "Not on a branch: at commit \(commit). A commit here belongs to no branch."
        case .unborn(let name):
            "On branch \(name), which has no commits yet. The next commit creates it."
        case nil:
            "The repository's head was not read."
        }
    }

    /// A titled list of changes, or nothing at all when there are none.
    private static func section(_ title: String, _ changes: [GitChange]) -> [String] {
        guard !changes.isEmpty else { return [] }
        // Padded so the paths line up: the model reads down the column of paths,
        // and a ragged left edge makes it read the kinds instead.
        let width = changes.map(\.kind.rawValue.count).max() ?? 0
        return ["", "\(title):"]
            + changes.map { change in
                let kind = change.kind.rawValue.padding(
                    toLength: width, withPad: " ", startingAt: 0)
                return "  \(kind)  \(change.path)"
            }
    }
}
