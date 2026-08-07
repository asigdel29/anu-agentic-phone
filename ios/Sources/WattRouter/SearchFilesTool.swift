// SearchFilesTool.swift — finding where something is written.
//
// History
//   2026-08-07  A. Sigdel  Created, from tools/file_operations.py.
//
// Contents
//   SearchFilesError  Why a search could not be run.
//   SearchFilesTool   A regex over the workspace, or a glob over its names.
//
// Two modes, because they are two questions: where is the file called this, and
// where is this written. Folding them into one parameter is what the Python does,
// and it keeps one tool in the model's list rather than two nearly identical ones.
//
// Case-insensitive unless the pattern has an uppercase letter — ripgrep's
// smart-case. A model searching for `todo` means any spelling; one searching for
// `TodoList` means that one. Wrong either way it quietly returns a different set,
// and "no matches" and "no matches the way you asked" look identical.
//
// A limit is reported when it bites: a search that hits its cap in silence has
// told the model it saw every occurrence there is.

import Foundation

/// Why a search could not be run at all.
public enum SearchFilesError: LocalizedError, Equatable, Sendable {
    case badPattern(pattern: String, detail: String)

    public var errorDescription: String? {
        switch self {
        case .badPattern(let pattern, let detail):
            "\(pattern) is not a valid regular expression: \(detail)"
        }
    }
}

/// Search the workspace.
public struct SearchFilesTool: Tool {
    /// Matches returned when none is asked for. The Python's `DEFAULT_SEARCH_LIMIT`.
    public static let defaultLimit = 50
    /// Most that can be asked for. Past this a search is a read.
    public static let maxLimit = 500
    /// A matching line is a locator, not content; a minified bundle would
    /// otherwise be the whole result.
    public static let maxLineLength = 300

    public let name = "search_files"

    public let purpose = """
        Search the workspace. With `target` of "content" (the default) the pattern \
        is a regular expression and the answer is matching lines with their file \
        and line number; with "files" it is a filename glob like `*.swift` and the \
        answer is paths. Case-insensitive unless the pattern has a capital letter. \
        Build directories and `.git` are never searched.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "A regular expression for content, or a glob for files."
            },
            "target": {"type": "string", "enum": ["content", "files"]},
            "path": {"type": "string", "description": "Directory to search under."},
            "include": {
              "type": "string",
              "description": "Only search files matching this glob, such as *.swift."
            },
            "limit": {
              "type": "integer",
              "description": "Most results. Defaults to \(defaultLimit), capped at \(maxLimit)."
            }
          },
          "required": ["pattern"]
        }
        """

    private let workspace: Workspace
    private let walk: FileWalk

    public init(workspace: Workspace) {
        self.workspace = workspace
        self.walk = FileWalk(workspace: workspace)
    }

    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)
        let limit = min(max(1, request.limit ?? Self.defaultLimit), Self.maxLimit)
        let where_ = request.path ?? "."

        if request.target == "files" {
            let found = try walk.files(under: where_, matching: request.pattern, limit: limit)
            guard !found.paths.isEmpty else { return "no file matches \(request.pattern)" }
            let cut = found.truncated ? " (cut at \(limit); narrow the pattern)" : ""
            return "\(found.paths.count) file(s)\(cut)\n" + found.paths.joined(separator: "\n")
        }

        let regex = try Self.compiling(request.pattern)
        let files = try walk.files(under: where_, matching: request.include)
        return search(regex, in: files.paths, limit: limit, pattern: request.pattern)
    }

    /// Smart case, then compile.
    private static func compiling(_ pattern: String) throws -> NSRegularExpression {
        do {
            let hasUppercase = pattern.contains { $0.isUppercase }
            return try NSRegularExpression(
                pattern: pattern, options: hasUppercase ? [] : [.caseInsensitive])
        } catch {
            throw SearchFilesError.badPattern(
                pattern: pattern, detail: error.localizedDescription)
        }
    }

    /// Every matching line, until `limit`.
    private func search(
        _ regex: NSRegularExpression, in paths: [String], limit: Int, pattern: String
    ) -> String {
        var lines: [String] = []
        var filesWithMatches = 0
        var truncated = false

        for path in paths {
            guard let url = try? workspace.resolve(path),
                let contents = FileWalk.text(of: url)
            else { continue }
            var matchedHere = false

            for (index, line) in contents.split(
                separator: "\n", omittingEmptySubsequences: false
            ).enumerated() {
                let line = String(line)
                let range = NSRange(line.startIndex..<line.endIndex, in: line)
                guard regex.firstMatch(in: line, range: range) != nil else { continue }

                matchedHere = true
                guard lines.count < limit else {
                    truncated = true
                    break
                }
                let shown =
                    line.count > Self.maxLineLength
                    ? String(line.prefix(Self.maxLineLength)) + "…" : line
                lines.append("\(path):\(index + 1): \(shown)")
            }

            if matchedHere { filesWithMatches += 1 }
            if truncated { break }
        }

        guard !lines.isEmpty else { return "no match for \(pattern)" }
        let cut = truncated ? " (cut at \(limit); there are more)" : ""
        return """
            \(lines.count) match(es) in \(filesWithMatches) file(s)\(cut)
            \(lines.joined(separator: "\n"))
            """
    }

    private struct Request: Decodable {
        let pattern: String
        let target: String?
        let path: String?
        let include: String?
        let limit: Int?
    }
}
