// PatchTool.swift — changing part of a file.
//
// History
//   2026-08-07  A. Sigdel  Created, from tools/file_operations.py.
//
// Contents
//   PatchError  Why an edit was not applied.
//   PatchTool   Replace a block of text.
//
// Without this the only way to change a file is to rewrite it whole, which costs
// the file in tokens on every edit and loses whatever the model did not repeat.
//
// Ambiguity is a refusal. A block occurring twice with no `replace_all` gets a
// count, the line numbers, and no edit — taking the first is how a tool silently
// changes the wrong one of two similar methods.
//
// The answer names the strategy that matched: an edit that landed exactly is one
// the model can build on, one that landed indentation-flexible is one it should
// read back first.

import Foundation

/// Why an edit was not applied.
public enum PatchError: LocalizedError, Equatable, Sendable {
    case notFound(path: String, block: String)
    case ambiguous(path: String, count: Int, lines: [Int])
    case notText(String)
    case cannotWrite(path: String, detail: String)

    public var errorDescription: String? {
        switch self {
        case .notFound(let path, let block):
            """
            \(block) is not in \(path). Text is matched exactly, or ignoring \
            trailing whitespace, or ignoring indentation — but not by similarity, \
            so it has to be the same lines. Read the file and quote it back.
            """
        case .ambiguous(let path, let count, let lines):
            """
            that text appears \(count) times in \(path), at lines \
            \(lines.map(String.init).joined(separator: ", ")). Include surrounding \
            lines to pick one, or pass replace_all to change them all.
            """
        case .notText(let path):
            "\(path) is not a text file"
        case .cannotWrite(let path, let detail):
            "\(path) could not be written: \(detail)"
        }
    }
}

/// Replace a block of text in a file.
public struct PatchTool: Tool {
    public let name = "patch"

    public let purpose = """
        Replace a block of text in a file. `old_string` must appear exactly once \
        unless you pass `replace_all`; include surrounding lines to make it \
        unique. It is matched exactly, or ignoring trailing whitespace, or \
        ignoring indentation — never by similarity. Pass an empty `new_string` \
        to delete a block.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "Relative to the workspace root."},
            "old_string": {"type": "string", "description": "The text to replace."},
            "new_string": {"type": "string", "description": "What to put there."},
            "replace_all": {
              "type": "boolean",
              "description": "Change every occurrence instead of refusing when there are several."
            }
          },
          "required": ["path", "old_string", "new_string"]
        }
        """

    private let workspace: Workspace

    public init(workspace: Workspace) {
        self.workspace = workspace
    }

    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)
        let url = try workspace.resolve(request.path)
        let shown = workspace.display(url)

        guard let content = FileWalk.text(of: url) else { throw PatchError.notText(shown) }
        guard let found = FuzzyMatch.find(request.oldString, in: content) else {
            throw PatchError.notFound(
                path: shown,
                block: String(request.oldString.prefix(while: { $0 != "\n" })))
        }

        guard request.replaceAll == true || found.ranges.count == 1 else {
            throw PatchError.ambiguous(
                path: shown, count: found.ranges.count,
                lines: found.ranges.map { Self.line(of: $0.lowerBound, in: content) })
        }

        // Back to front, so one replacement does not invalidate the indices after it.
        var edited = content
        for range in found.ranges.reversed() {
            let replacement = Self.reindenting(
                request.newString, from: request.oldString, to: String(content[range]))
            edited.replaceSubrange(range, with: replacement)
        }

        do {
            try Data(edited.utf8).write(
                to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            throw PatchError.cannotWrite(path: shown, detail: error.localizedDescription)
        }

        let times = found.ranges.count == 1 ? "1 place" : "\(found.ranges.count) places"
        return "changed \(times) in \(shown), matched \(found.strategy.rawValue)"
    }

    /// Shift `replacement` to the indentation the matched text actually had.
    /// `indentation-flexible` matches a block quoted back at a different one, so
    /// the replacement was written at the pattern's and unchanged would break the
    /// file it was meant to fix.
    ///
    /// Simpler than the Python's `_reindent_replacement`, and worth saying so: it
    /// strips the pattern's indent from each line and prepends the file's, which
    /// is right for a uniformly indented block and does nothing clever otherwise.
    static func reindenting(_ replacement: String, from pattern: String, to matched: String)
        -> String
    {
        let was = indent(of: pattern)
        let now = indent(of: matched)
        guard was != now else { return replacement }

        return replacement.split(separator: "\n", omittingEmptySubsequences: false)
            .map { line -> String in
                // A blank line gets no indentation: trailing spaces on an empty
                // line are exactly what `line-trimmed` exists to forgive.
                guard !line.allSatisfy(\.isWhitespace) else { return String(line) }
                return now + String(line.hasPrefix(was) ? line.dropFirst(was.count) : line)
            }
            .joined(separator: "\n")
    }

    /// The leading whitespace of the first line that has anything on it.
    private static func indent(of text: String) -> String {
        for line in text.split(separator: "\n", omittingEmptySubsequences: false)
        where !line.allSatisfy(\.isWhitespace) {
            return String(line.prefix(while: \.isWhitespace))
        }
        return ""
    }

    /// Which line an index falls on, counting from 1.
    private static func line(of index: String.Index, in content: String) -> Int {
        content[content.startIndex..<index].count(where: { $0 == "\n" }) + 1
    }

    private struct Request: Decodable {
        let path: String
        let oldString: String
        let newString: String
        let replaceAll: Bool?

        enum CodingKeys: String, CodingKey {
            case path
            case oldString = "old_string"
            case newString = "new_string"
            case replaceAll = "replace_all"
        }
    }
}
