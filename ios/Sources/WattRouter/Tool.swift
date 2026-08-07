// Tool.swift — what the model can ask for, and what it gets back.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   ToolCall    Something the model asked to run.
//   ToolResult  What running it produced.
//   Tool        Something that can be asked for.
//
// Arguments arrive as text the model wrote, and the whole design follows from
// that: not a checked value but a string that is usually JSON and sometimes is
// not. So a tool decodes its own, and a decoding failure is an ordinary outcome.
//
// The same reasoning gives `ToolResult` an `isError` rather than making `run` the
// only way to report trouble. A model told a file does not exist looks for the
// right one; a turn that threw is simply over.

import Foundation

/// Something the model asked to run.
public struct ToolCall: Equatable, Sendable {
    /// The provider's identifier. Carried through untouched: a reply has to name
    /// the call it answers, and a turn may have several in flight.
    public let id: String
    /// Which tool.
    public let name: String
    /// The arguments, as the model wrote them: a JSON object, as text, and not
    /// necessarily valid.
    public let arguments: String

    public init(id: String, name: String, arguments: String) {
        self.id = id
        self.name = name
        self.arguments = arguments
    }
}

extension ToolCall: Codable {
    /// The nested shape a call has on the wire, against this type's flat one.
    ///
    /// `ServerSentEvent` already unpacks this nesting on the way in. Encoding it
    /// belongs on the same type rather than in a private struct wherever a call
    /// happens to be sent, or the two halves of one format live in two places and
    /// only one of them gets fixed.
    private enum CodingKeys: String, CodingKey {
        case id, type, function
    }

    private enum FunctionKeys: String, CodingKey {
        case name, arguments
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let function = try container.nestedContainer(
            keyedBy: FunctionKeys.self, forKey: .function)
        self.init(
            id: try container.decode(String.self, forKey: .id),
            name: try function.decode(String.self, forKey: .name),
            arguments: try function.decode(String.self, forKey: .arguments))
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        // The only value the field takes today, and required. A provider reading
        // a call without it treats the message as malformed rather than guessing.
        try container.encode("function", forKey: .type)

        var function = container.nestedContainer(keyedBy: FunctionKeys.self, forKey: .function)
        try function.encode(name, forKey: .name)
        try function.encode(arguments, forKey: .arguments)
    }
}

/// What running a tool produced.
public struct ToolResult: Equatable, Sendable {
    /// The call this answers.
    public let id: String
    /// What the model is told. On failure this is the explanation it will act
    /// on, so it says what was wrong rather than that something was.
    public let content: String
    /// Whether `content` describes a failure. The model is told either way; this
    /// is so a transcript can show the difference and a loop can count them.
    public let isError: Bool

    public init(id: String, content: String, isError: Bool = false) {
        self.id = id
        self.content = content
        self.isError = isError
    }
}

/// Something the model can ask for.
public protocol Tool: Sendable {
    /// The name the model calls it by. Stable: it appears in transcripts.
    var name: String { get }

    /// What it does, in the words the model reads. Prompt text, not documentation.
    var purpose: String { get }

    /// A JSON Schema object describing the arguments, written out as JSON.
    ///
    /// Text rather than a Swift type, which is a trade: deriving it from a
    /// `Decodable` would stop the two drifting, but Swift cannot produce a schema
    /// from a type without a good deal of machinery, and this is what the model
    /// actually reads. Drift is cheaper to catch with a test than with a generator.
    var schema: String { get }

    /// Run it.
    ///
    /// - Parameter arguments: the JSON object the model produced, as bytes.
    /// - Returns: what to tell the model.
    /// - Throws: only for what the model cannot act on. Everything it *can* act
    ///   on — a missing file, an argument out of range — is a returned string.
    ///   `CancellationError` is the case that must be thrown.
    func run(arguments: Data) async throws -> String
}
