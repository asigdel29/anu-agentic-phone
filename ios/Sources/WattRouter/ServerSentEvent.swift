// ServerSentEvent.swift — one line of a streamed completion.
//
// History
//   2026-08-07  A. Sigdel  Created.
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
// The one judgement here is what to do with a `data:` line that will not decode.
// It is treated as an error rather than skipped, because the alternative is
// dropping the model's text and reporting success: an answer that silently loses
// a sentence looks like a short answer, and nothing in the stack would say
// otherwise. Lines that are not data — comments, blanks, fields this client does
// not read — are ignored, since those are the format working as intended.

import Foundation

/// What one line of a streamed completion means.
enum ServerSentEvent: Equatable, Sendable {
    /// Text the model produced, to be handed to the caller as it stands.
    case text(String)
    /// The provider says the answer is complete. Nothing follows it.
    case done
    /// Framing rather than content: a keep-alive comment, the blank line between
    /// events, or a delta that carried no text.
    case ignored

    /// The field prefix carrying the payload. The space after the colon is
    /// optional in the format and both spellings are seen in the wild.
    private static let field = "data:"
    /// What the provider sends instead of a final chunk.
    private static let terminator = "[DONE]"

    /// Read one line.
    ///
    /// - Parameter line: a single line of the body, without its newline.
    /// - Returns: what it means; `.ignored` for anything that is not a payload.
    /// - Throws: a `DecodingError` IF a `data:` line is not a chunk this client
    ///   understands. See the note above: skipping it would lose text.
    static func decoding(_ line: String) throws -> ServerSentEvent {
        guard line.hasPrefix(field) else { return .ignored }
        let payload = line.dropFirst(field.count).trimmingCharacters(in: .whitespaces)
        if payload == terminator { return .done }

        let chunk = try JSONDecoder().decode(Chunk.self, from: Data(payload.utf8))
        // Concatenated rather than taking the first: the field is an array, and a
        // provider that ever returns two would otherwise lose one silently.
        let text = chunk.choices.compactMap(\.delta?.content).joined()

        // An empty delta is not a chunk. The first event of a completion carries
        // the role and no text, and the last carries a finish reason and no text;
        // yielding "" for those would commit a chain walk to a model that has not
        // said anything yet, which is precisely the decision it exists to make.
        return text.isEmpty ? .ignored : .text(text)
    }

    /// The part of a streamed chunk this client reads.
    ///
    /// Deliberately partial. `id`, `created`, `model`, `usage` and the finish
    /// reason are all present on the wire and none of them is needed here; the
    /// model that answered is known to the caller, which chose it.
    private struct Chunk: Decodable {
        let choices: [Choice]

        struct Choice: Decodable {
            /// Absent on chunks that carry only usage or a finish reason.
            let delta: Delta?
        }

        struct Delta: Decodable {
            /// Absent on the opening chunk, which carries only the role.
            let content: String?
        }
    }
}
