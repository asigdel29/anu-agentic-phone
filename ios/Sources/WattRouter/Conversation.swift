// Conversation.swift — the state a turn accumulates, and the request it becomes.
//
// History
//   2026-08-06  A. Sigdel  Created.
//
// Contents
//   Role          Who produced a message.
//   Message       One message: a role and its text.
//   Conversation  The messages so far, and the body the core classifies.
//
// The core takes an OpenAI-shaped body as a string, and before this every caller
// wrote that JSON by hand. That is a second copy of a format, free to drift from
// the one the core actually parses, and it puts escaping at each call site — where
// a message containing a quote stops being ordinary text and becomes a malformed
// request. Built here, once, from state that is already being kept anyway.

import Foundation

/// Who produced a message.
public enum Role: String, Codable, CaseIterable, Sendable {
    /// Standing instructions, not part of the exchange.
    case system
    /// The person.
    case user
    /// The model.
    case assistant
    /// The result of a tool the model asked for.
    case tool
}

/// One message: a role, its text, and — for the two shapes a tool exchange
/// needs — what was asked for or which request is being answered.
public struct Message: Codable, Equatable, Sendable {
    /// Who produced it.
    public let role: Role
    /// What it says. Empty on an assistant turn that only asked for tools, which
    /// is ordinary rather than a fault.
    public let content: String
    /// What the model asked to run. Only on an assistant message.
    public let toolCalls: [ToolCall]
    /// Which call this answers. Only on a tool message.
    public let toolCallId: String?

    /// Pair text with who produced it.
    public init(
        role: Role, content: String, toolCalls: [ToolCall] = [], toolCallId: String? = nil
    ) {
        self.role = role
        self.content = content
        self.toolCalls = toolCalls
        self.toolCallId = toolCallId
    }

    /// Standing instructions.
    public static func system(_ content: String) -> Message {
        Message(role: .system, content: content)
    }

    /// Something the person said.
    public static func user(_ content: String) -> Message {
        Message(role: .user, content: content)
    }

    /// Something the model said, and anything it asked to have run.
    ///
    /// # Rely
    /// A turn that asked for tools must be appended before any result is, and
    /// with its calls intact. A tool message names a call id, and a provider that
    /// was never sent the message announcing that id rejects the whole request —
    /// naming, in the way of these things, nothing in particular.
    public static func assistant(_ content: String, toolCalls: [ToolCall] = []) -> Message {
        Message(role: .assistant, content: content, toolCalls: toolCalls)
    }

    /// What a tool produced, answering the call that asked for it.
    public static func tool(_ content: String, answering id: String) -> Message {
        Message(role: .tool, content: content, toolCallId: id)
    }

    /// What a tool produced. The result already knows which call it answers.
    public static func tool(_ result: ToolResult) -> Message {
        .tool(result.content, answering: result.id)
    }

    private enum CodingKeys: String, CodingKey {
        case role, content
        case toolCalls = "tool_calls"
        case toolCallId = "tool_call_id"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            role: try container.decode(Role.self, forKey: .role),
            content: try container.decodeIfPresent(String.self, forKey: .content) ?? "",
            toolCalls: try container.decodeIfPresent([ToolCall].self, forKey: .toolCalls) ?? [],
            toolCallId: try container.decodeIfPresent(String.self, forKey: .toolCallId))
    }

    /// Absent, not empty.
    ///
    /// `"tool_calls": []` on a user message and `"tool_call_id": null` on an
    /// assistant one are both things a provider may refuse, and refusing a whole
    /// request over a key that means nothing is an expensive way to find out.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(role, forKey: .role)
        try container.encode(content, forKey: .content)
        if !toolCalls.isEmpty { try container.encode(toolCalls, forKey: .toolCalls) }
        try container.encodeIfPresent(toolCallId, forKey: .toolCallId)
    }
}

/// The messages so far, and the request body the routing core reads off them.
public struct Conversation: Codable, Equatable, Sendable {
    /// Every message, oldest first. The core scores the last one from the person
    /// and estimates context from all of them.
    public private(set) var messages: [Message]

    /// Start a conversation, optionally with standing instructions.
    public init(system: String? = nil) {
        messages = system.map { [Message.system($0)] } ?? []
    }

    /// Add a message to the end.
    public mutating func append(_ message: Message) {
        messages.append(message)
    }


    /// The request body `Router.decide` classifies.
    ///
    /// - Parameters:
    ///   - maxTokens: a cap on the reply, or `nil` for none. The core reads a cap
    ///     of 32 or less as housekeeping, so a genuinely short answer to a
    ///     person's question is better marked with `background` than starved.
    ///   - background: housekeeping — a title, a summary, a compaction — rather
    ///     than a person waiting. Stated rather than implied: the core infers it
    ///     from a small cap only because agents that cannot say so exist, and
    ///     this one can.
    /// - Returns: a JSON object. Total: every field is a `String`, `Int` or
    ///   `Bool`, none of which `JSONEncoder` can fail on, and its output is
    ///   always UTF-8. The unreachable branch yields an empty conversation, which
    ///   the core routes by its unscored path — a worse answer than the right one,
    ///   and a better outcome than trapping inside somebody's app.
    public func requestBody(maxTokens: Int? = nil, background: Bool = false) -> String {
        let body = Body(
            messages: messages,
            maxTokens: maxTokens,
            background: background ? true : nil)

        guard let data = try? JSONEncoder().encode(body),
            let json = String(data: data, encoding: .utf8)
        else { return #"{"messages":[]}"# }
        return json
    }

    /// The wire shape. Its keys are the three facts `classify` reads off a
    /// request, and this is the only place in Swift that spells them — a caller
    /// that hand-wrote them would be keeping a second copy of the format.
    private struct Body: Encodable {
        let messages: [Message]
        let maxTokens: Int?
        let background: Bool?

        enum CodingKeys: String, CodingKey {
            case messages
            case maxTokens = "max_tokens"
            case background = "x_wattrouter_background"
        }
    }
}
