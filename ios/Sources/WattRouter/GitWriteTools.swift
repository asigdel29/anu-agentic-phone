// GitWriteTools.swift — staging and committing, as tools.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   GitAddTool     Staging paths.
//   GitCommitTool  Writing what is staged.
//
// One file because the pair is the decision. `git_commit` could take the paths
// and stage them itself, and folding the two together would remove the step
// where the model looks at what it is about to write. It would also blunt the
// refusal: "nothing is staged" has an obvious next move, and a combined tool
// would have to explain both that a path did not exist and that nothing landed.
//
// Both answer with what happened rather than that it happened. Staging answers
// with the status afterwards, so the model sees what it staged; committing
// answers with the short id, so the commit can be named later.
//
// No permission is obtained. This is the app's own sandbox, which the file tools
// already write to, and there is nothing to ask anybody for.

import Foundation

/// Stage paths for the next commit.
public struct GitAddTool: Tool {
    public let name = "git_add"

    public let purpose = """
        Stage paths in the workspace for the next commit. A directory stages \
        everything under it. Answers with the status afterwards, so you can see \
        what is about to be committed without asking again.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "paths": {
              "type": "array",
              "items": {"type": "string"},
              "description": "Paths relative to the workspace root."
            }
          },
          "required": ["paths"]
        }
        """

    private let repository: any Repository
    private let workspace: Workspace

    public init(repository: any Repository, workspace: Workspace) {
        self.repository = repository
        self.workspace = workspace
    }

    /// - Returns: the status after staging.
    /// - Throws: the refusal, which names the path that is missing when one is —
    ///   the last place that could be flattened into "could not stage".
    ///
    /// # Rely
    /// The workspace root exists. Staging is disk work that does not suspend.
    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        // Before the repository is touched: staging nothing succeeds at libgit2's
        // level and tells the model it did something.
        guard !request.paths.isEmpty else {
            return "no paths were given, so nothing was staged"
        }

        let status = try repository.add(request.paths, in: workspace.root)
        return GitStatusTool.describe(status)
    }

    private struct Request: Decodable {
        let paths: [String]
    }
}

/// Commit what is staged.
public struct GitCommitTool: Tool {
    public let name = "git_commit"

    public let purpose = """
        Commit what is staged, with a message. Stage first with git_add — this \
        commits the index and nothing else, and refuses rather than writing an \
        empty commit. Answers with the short id of what it wrote.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "message": {
              "type": "string",
              "description": "The commit message. One imperative line, then a blank line, then why."
            }
          },
          "required": ["message"]
        }
        """

    private let repository: any Repository
    private let workspace: Workspace

    public init(repository: any Repository, workspace: Workspace) {
        self.repository = repository
        self.workspace = workspace
    }

    /// - Returns: the short id of the commit written.
    /// - Throws: the refusal. "Nothing is staged" is the sentence that stops a
    ///   model committing identical trees in a loop, so it arrives as written.
    ///
    /// # Rely
    /// The workspace root exists. Committing is disk work that does not suspend.
    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        // libgit2 accepts an empty message and writes a commit nobody can read
        // later. Refused here rather than there, because there it is not refused.
        let message = request.message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !message.isEmpty else {
            return "a commit needs a message, and this one was empty"
        }

        return "committed \(try repository.commit(message, in: workspace.root))"
    }

    private struct Request: Decodable {
        let message: String
    }
}
