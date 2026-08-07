// ReadFileTool.swift — showing the model a file.
//
// History
//   2026-08-07  A. Sigdel  Created, from tools/file_operations.py.
//
// Contents
//   ReadFileError  Why a file could not be shown.
//   ReadFileTool   Read a slice of a file, numbered.
//
// The Python shells out — `wc -c`, `sed -n`, `file` — and iOS has no shell, so
// three things it got for free are done here. Binary detection, because a model
// handed forty kilobytes of mangled UTF-8 has lost its context window and learned
// nothing. A size cap, because reading a whole file to return five hundred lines
// of it is how a phone gets jetsammed. And an answer saying which lines these
// were out of how many, so the next page is asked for rather than guessed at.
//
// Line numbers on every line: the next thing a model does with a file is describe
// an edit to it, and an unnumbered read makes it count.

import Foundation

/// Why a file could not be shown.
public enum ReadFileError: LocalizedError, Equatable, Sendable {
    case notFound(String)
    case isDirectory(String)
    case binary(path: String, detail: String)
    case tooLarge(path: String, bytes: Int, cap: Int)

    public var errorDescription: String? {
        switch self {
        case .notFound(let path):
            "\(path) does not exist"
        case .isDirectory(let path):
            "\(path) is a directory, not a file"
        case .binary(let path, let detail):
            "\(path) is not text (\(detail)), so there is nothing useful to show"
        case .tooLarge(let path, let bytes, let cap):
            "\(path) is \(bytes) bytes, over the \(cap)-byte limit for reading a whole file"
        }
    }
}

/// Read a slice of a file.
public struct ReadFileTool: Tool {
    /// Most lines one call may return. A model asking for more is asking for a
    /// context window's worth of one file.
    public static let maxLines = 2000
    /// Lines returned when none is asked for.
    public static let defaultLimit = 500
    /// Longest line shown before it is cut. A minified bundle is one line.
    public static let maxLineLength = 2000
    /// Largest file read at all. Crude on purpose: the whole file is loaded to
    /// slice it, and a chunked line reader is not needed yet.
    public static let maxBytes = 4 * 1024 * 1024

    public let name = "read_file"

    public let purpose = """
        Read a file from the workspace. Lines come back numbered, so you can \
        describe an edit by line without counting. Reads at most \
        \(defaultLimit) lines at a time; the answer says how many the file has, \
        so ask again with a larger `offset` to continue.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "Relative to the workspace root."},
            "offset": {
              "type": "integer",
              "description": "First line, counting from 1. Defaults to 1."
            },
            "limit": {
              "type": "integer",
              "description": "How many lines. Defaults to \(defaultLimit), capped at \(maxLines)."
            }
          },
          "required": ["path"]
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

        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else {
            throw ReadFileError.notFound(shown)
        }
        guard !isDirectory.boolValue else { throw ReadFileError.isDirectory(shown) }

        let data = try Data(contentsOf: url)
        guard data.count <= Self.maxBytes else {
            throw ReadFileError.tooLarge(path: shown, bytes: data.count, cap: Self.maxBytes)
        }
        // A zero byte is what separates an image from source in an odd encoding.
        // Checked over the whole file rather than a prefix, which costs nothing at
        // this size and catches an archive that begins with readable text.
        guard !data.contains(0) else {
            throw ReadFileError.binary(path: shown, detail: "it contains a zero byte")
        }
        guard let text = String(data: data, encoding: .utf8) else {
            throw ReadFileError.binary(path: shown, detail: "it is not valid UTF-8")
        }

        return Self.render(text, path: shown, offset: request.offset, limit: request.limit)
    }

    /// The slice, numbered, with enough around it to ask for the next one.
    private static func render(_ text: String, path: String, offset: Int?, limit: Int?) -> String {
        // Blank lines are lines, so empty subsequences are kept. A trailing
        // newline would then add a phantom final one, so it goes first: a file
        // ending in a newline has as many lines as one that does not.
        let body = text.hasSuffix("\n") ? String(text.dropLast()) : text
        let lines = body.isEmpty ? [] : body.split(separator: "\n", omittingEmptySubsequences: false)

        guard !lines.isEmpty else { return "\(path) is empty" }

        let first = max(1, offset ?? 1)
        let count = min(max(1, limit ?? defaultLimit), maxLines)
        guard first <= lines.count else {
            return "\(path) has \(lines.count) lines, so line \(first) is past the end"
        }

        let last = min(first + count - 1, lines.count)
        let width = String(last).count
        let numbered = (first...last).map { number -> String in
            let line = String(lines[number - 1])
            let shown =
                line.count > maxLineLength
                ? String(line.prefix(maxLineLength)) + "… [line truncated]" : line
            // Right-aligned, so a column of line numbers is a column.
            return "\(String(format: "%\(width)d", number))  \(shown)"
        }

        let more = last < lines.count ? "; ask again with offset \(last + 1) for more" : ""
        return """
            \(path), lines \(first)-\(last) of \(lines.count)\(more)
            \(numbered.joined(separator: "\n"))
            """
    }

    private struct Request: Decodable {
        let path: String
        let offset: Int?
        let limit: Int?
    }
}
