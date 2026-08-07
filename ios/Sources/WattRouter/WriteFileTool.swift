// WriteFileTool.swift — putting a file back.
//
// History
//   2026-08-07  A. Sigdel  Created, from tools/file_operations.py.
//
// Contents
//   WriteFileError  Why a file could not be written.
//   WriteFileTool   Write a whole file, atomically.
//
// Atomically, because the failure mode otherwise is a truncated source file and
// nothing saying that is what happened. `.atomic` writes a temporary alongside
// and renames it, which is the one operation the filesystem will not leave half
// done — a crash or a kill leaves the old file, whole.
//
// The protection class is chosen rather than defaulted, for the reason the
// Keychain's is: a turn that goes to the background gets about a minute to
// finish, and a screen locking during it must not make the file unwritable.
//
// The answer says whether the file was created or replaced. A model that cannot
// tell those apart cannot tell that it has just destroyed something.

import Foundation

/// Why a file could not be written.
public enum WriteFileError: LocalizedError, Equatable, Sendable {
    case isDirectory(String)
    case cannotCreateParent(path: String, detail: String)
    case cannotWrite(path: String, detail: String)

    public var errorDescription: String? {
        switch self {
        case .isDirectory(let path):
            "\(path) is a directory, so it cannot be written as a file"
        case .cannotCreateParent(let path, let detail):
            "the directory for \(path) could not be created: \(detail)"
        case .cannotWrite(let path, let detail):
            "\(path) could not be written: \(detail)"
        }
    }
}

/// Write a whole file.
public struct WriteFileTool: Tool {
    public let name = "write_file"

    public let purpose = """
        Write a file in the workspace, replacing it if it is already there. \
        Directories in the path are created as needed. This writes the whole \
        file: send the complete contents, not a fragment.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "Relative to the workspace root."},
            "content": {
              "type": "string",
              "description": "The complete new contents of the file."
            }
          },
          "required": ["path", "content"]
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
        let existed = FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)
        guard !isDirectory.boolValue else { throw WriteFileError.isDirectory(shown) }

        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        } catch {
            throw WriteFileError.cannotCreateParent(
                path: shown, detail: error.localizedDescription)
        }

        let data = Data(request.content.utf8)
        do {
            // `.atomic` writes a temporary alongside and renames. `.completeFile
            // ProtectionUntilFirstUserAuthentication` keeps the file readable
            // from the first unlock after a reboot, which is as long as any turn
            // lives; it is a no-op off iOS, so nothing on a Mac exercises it.
            try data.write(
                to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            throw WriteFileError.cannotWrite(path: shown, detail: error.localizedDescription)
        }

        return Self.describe(shown, content: request.content, bytes: data.count, existed: existed)
    }

    /// What happened, in the terms the model needs to notice an accident.
    private static func describe(
        _ path: String, content: String, bytes: Int, existed: Bool
    ) -> String {
        let verb = existed ? "replaced" : "created"
        guard !content.isEmpty else { return "\(verb) \(path), now empty" }

        // Counted the way `read_file` counts, so the numbers in one answer match
        // the numbers in the other. A trailing newline is not an extra line.
        let body = content.hasSuffix("\n") ? String(content.dropLast()) : content
        let lines = body.split(separator: "\n", omittingEmptySubsequences: false).count
        return "\(verb) \(path), \(lines) line\(lines == 1 ? "" : "s"), \(bytes) bytes"
    }

    private struct Request: Decodable {
        let path: String
        let content: String
    }
}
