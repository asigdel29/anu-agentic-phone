// ServerSentEvent.swift — one line of a streamed completion.
//
// History
//   2026-08-07  A. Sigdel  Created.
//   2026-08-07  A. Sigdel  Tool call fragments and the finish reason; a line can
//                          now mean several things, so `decoding` returns a list.
//
// Contents
//   ServerSentEvent  What a line of the provider's streamed body means.
//
// The provider answers a streaming request in `text/event-stream`: a line-framed
// format carrying JSON, with most of the lines carrying nothing at all. Reading
// it is a pure function of a line, so it is written and tested as one, separately
// from the client that will do the reading — a wire format and a transport fail
// in different ways and are worth being able to debug apart.
//
// One line is not one event. A delta can carry text *and* several tool call
// fragments, and the choice around it can carry a finish reason at the same
// time; parallel tool calls are an array. So this returns a list, and there is no
// `.ignored` case — an ignored line is an empty one, which says the same thing.
// Returning a single event and picking whichever looked most important is how a
// tool call goes missing behind a stray space of content.
//
// The one judgement here is what to do with a `data:` line that will not decode.
// It is treated as an error rather than skipped, because the alternative is
// dropping the model's text and reporting success: an answer that silently loses
// a sentence looks like a short answer, and nothing in the stack would say
// otherwise. Lines that are not data — comments, blanks, fields this client does
// not read — yield nothing, since those are the format working as intended.

import Foundation

/// What one line of a streamed completion means.
enum ServerSentEvent: Equatable, Sendable {
    /// Text the model produced, to be handed to the caller as it stands.
    case text(String)
    /// Part of a tool call. Assembling these is somebody else's job; see
    /// [`ToolCallFragment`].
    case toolCall(ToolCallFragment)
    /// Why the model stopped. Arrives on its own chunk, before `[DONE]`.
    case finished(FinishReason)
    /// The provider says the answer is complete. Nothing follows it.
    case done

    /// Why a model stopped producing.
    ///
    /// A struct rather than an enumeration, because this is an open set: a
    /// `String`-backed enum throws on a value it has not been taught, which would
    /// fail an otherwise good stream over a word almost nothing reads. An unknown
    /// reason arrives intact and can be logged.
    struct FinishReason: RawRepresentable, Equatable, Sendable {
        let rawValue: String
        init(rawValue: String) { self.rawValue = rawValue }

        /// The model finished its answer.
        static let stop = FinishReason(rawValue: "stop")
        /// The model wants tools run, and is waiting for the results.
        static let toolCalls = FinishReason(rawValue: "tool_calls")
        /// The answer was cut off at the cap. Not a failure, and not a complete
        /// answer either — the distinction a caller needs and the reason this
        /// case is read at all.
        static let length = FinishReason(rawValue: "length")
    }

    /// Part of one tool call.
    ///
    /// The id and name arrive once, on the first fragment for an index; the
    /// arguments arrive a few characters at a time across many lines. Nothing
    /// here is a usable call on its own, and saying so in the type keeps a caller
    /// from treating the first fragment as the whole thing.
    struct ToolCallFragment: Equatable, Sendable {
        /// Which call this belongs to. Several may be in flight at once, and the
        /// index is the only thing tying a fragment to the one it continues.
        let index: Int
        /// Present on the first fragment for an index, absent afterwards.
        let id: String?
        /// Likewise.
        let name: String?
        /// A piece of the JSON arguments, sometimes a single character, often
        /// empty on the fragment that carries the id.
        let arguments: String
    }

    /// The field prefix carrying the payload. The space after the colon is
    /// optional in the format and both spellings are seen in the wild.
    private static let field = "data:"
    /// What the provider sends instead of a final chunk.
    private static let terminator = "[DONE]"

    /// Read one line.
    ///
    /// - Parameter line: a single line of the body, without its newline.
    /// - Returns: what it means, in the order it should be handled. Empty for
    ///   anything that is not a payload.
    /// - Throws: a `DecodingError` IF a `data:` line is not a chunk this client
    ///   understands. See the note above: skipping it would lose text.
    static func decoding(_ line: String) throws -> [ServerSentEvent] {
        guard line.hasPrefix(field) else { return [] }
        let payload = line.dropFirst(field.count).trimmingCharacters(in: .whitespaces)
        if payload == terminator { return [.done] }

        let chunk = try JSONDecoder().decode(Chunk.self, from: Data(payload.utf8))
        var events: [ServerSentEvent] = []

        // Concatenated rather than taking the first: the field is an array, and a
        // provider that ever returns two would otherwise lose one silently.
        //
        // An empty delta is not a chunk. The first event of a completion carries
        // the role and no text, and the last carries a finish reason and no text;
        // yielding "" for those would commit a chain walk to a model that has not
        // said anything yet, which is precisely the decision it exists to make.
        let text = chunk.choices.compactMap(\.delta?.content).joined()
        if !text.isEmpty { events.append(.text(text)) }

        for call in chunk.choices.flatMap({ $0.delta?.toolCalls ?? [] }) {
            events.append(
                .toolCall(
                    ToolCallFragment(
                        index: call.index, id: call.id, name: call.function?.name,
                        arguments: call.function?.arguments ?? "")))
        }

        for reason in chunk.choices.compactMap(\.finishReason) {
            events.append(.finished(FinishReason(rawValue: reason)))
        }
        return events
    }

    /// The part of a streamed chunk this client reads.
    ///
    /// Deliberately partial. `id`, `created`, `model` and `usage` are all on the
    /// wire and none is needed here; the model that answered is known to the
    /// caller, which chose it.
    private struct Chunk: Decodable {
        let choices: [Choice]

        struct Choice: Decodable {
            /// Absent on chunks that carry only usage.
            let delta: Delta?
            let finishReason: String?

            enum CodingKeys: String, CodingKey {
                case delta
                case finishReason = "finish_reason"
            }
        }

        struct Delta: Decodable {
            /// Absent on the opening chunk, which carries only the role.
            let content: String?
            let toolCalls: [ToolCall]?

            enum CodingKeys: String, CodingKey {
                case content
                case toolCalls = "tool_calls"
            }
        }

        struct ToolCall: Decodable {
            let index: Int
            let id: String?
            let function: Function?
        }

        struct Function: Decodable {
            let name: String?
            let arguments: String?
        }
    }
}
