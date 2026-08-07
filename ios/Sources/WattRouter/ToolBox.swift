// ToolBox.swift — the tools a turn may call, and getting to one of them.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   ToolBox  Dispatch a call by name.
//
// Small, and almost all of it is about failing usefully. `run` returns a result
// for every outcome but one: an unknown name, arguments that will not decode and
// a tool that threw are all things the model can act on once it is told, and a
// turn that ended instead has discarded a next move it already had.
//
// The exception is cancellation, rethrown deliberately. Reported as a result,
// "the tool was cancelled" is something a model answers by trying again.

import Foundation

/// The tools a turn may call.
public struct ToolBox: Sendable {
    /// In the order given. Kept alongside the map because the order is what the
    /// model is shown and a dictionary has none.
    public let tools: [any Tool]
    private let byName: [String: any Tool]

    /// - Parameter tools: what the turn may call. A repeated name keeps the first
    ///   and drops the rest, so what is dispatched and what is advertised cannot
    ///   disagree — which they would if the map dropped one and the list kept both.
    public init(_ tools: [any Tool]) {
        var kept: [any Tool] = []
        var byName: [String: any Tool] = [:]
        for tool in tools where byName[tool.name] == nil {
            byName[tool.name] = tool
            kept.append(tool)
        }
        self.tools = kept
        self.byName = byName
    }

    /// The tool of that name, if there is one.
    public subscript(name: String) -> (any Tool)? { byName[name] }

    /// Run what the model asked for.
    ///
    /// - Returns: what to tell the model, always — including that the request
    ///   made no sense.
    /// - Throws: `CancellationError`, and nothing else.
    public func run(_ call: ToolCall) async throws -> ToolResult {
        guard let tool = byName[call.name] else {
            // The alternatives, not just the mistake: a model that guessed a
            // plausible name is one list away from the right one.
            let known = tools.map(\.name).sorted().joined(separator: ", ")
            return ToolResult(
                id: call.id,
                content: "there is no tool called \(call.name). Available: \(known)",
                isError: true)
        }

        do {
            return ToolResult(id: call.id, content: try await tool.run(
                arguments: Data(call.arguments.utf8)))
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as DecodingError {
            // The common failure by a wide margin, and worth spelling out: the
            // model wrote these arguments and can write them again.
            return ToolResult(
                id: call.id,
                content: """
                    \(call.name) could not read its arguments: \(Self.describe(error)).
                    They must match this schema: \(tool.schema)
                    """,
                isError: true)
        } catch {
            return ToolResult(
                id: call.id, content: "\(call.name) failed: \(error.localizedDescription)",
                isError: true)
        }
    }

    /// A decoding failure in a sentence the model can act on.
    ///
    /// `localizedDescription` on a `DecodingError` says only that the data could
    /// not be read, naming neither the key nor the problem. The associated values
    /// carry both.
    private static func describe(_ error: DecodingError) -> String {
        switch error {
        case .keyNotFound(let key, _):
            "\(key.stringValue) is missing"
        case .typeMismatch(let type, let context):
            "\(Self.path(context)) should be \(type)"
        case .valueNotFound(let type, let context):
            "\(Self.path(context)) is null, and a \(type) was needed"
        case .dataCorrupted(let context):
            context.codingPath.isEmpty
                ? "it is not valid JSON" : "\(Self.path(context)) is not valid"
        @unknown default:
            "it did not match the schema"
        }
    }

    /// Where in the arguments a fault was, as the model wrote them.
    private static func path(_ context: DecodingError.Context) -> String {
        context.codingPath.isEmpty
            ? "the arguments" : context.codingPath.map(\.stringValue).joined(separator: ".")
    }
}
