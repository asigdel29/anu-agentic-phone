// Answer.swift — a turn's events, folded into one sentence.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   AnswerError  Why a turn produced nothing to say.
//   Answer       The fold.
//
// A conversation renders every event a turn produces: which model answered, text
// as it arrives, each tool call and each result. Somewhere with one place to put
// one answer — an App Intent, spoken by Siri — cannot. So the stream is folded,
// and this is where, because a fold in the app target is a fold no test can
// reach.
//
// Two things it has to get right, and both produce something a person hears.
//
// Tool results are not the answer. A turn that read the calendar and then said
// one sentence about it has one sentence worth speaking; putting the tool output
// in front of it hands Siri a paragraph of JSON to read aloud.
//
// And an empty answer is a failure rather than silence. A turn that produced
// only tool calls and no text has not answered, and returning "" is a response
// of nothing at all — which reads to the person as the agent having ignored them.

import Foundation

/// Why a turn produced nothing to say.
public enum AnswerError: LocalizedError, Equatable, Sendable {
    /// The turn ran and never produced text.
    case nothingSaid

    public var errorDescription: String? {
        switch self {
        case .nothingSaid:
            "the agent did some work but did not say anything about it. Try asking again"
        }
    }
}

/// A turn's events, folded into one sentence.
public enum Answer {
    /// Fold a turn.
    ///
    /// - Returns: everything the model said, in order, with the surrounding
    ///   whitespace taken off.
    /// - Throws: `AnswerError.nothingSaid` IF the turn produced no text, and
    ///   whatever the turn itself threw.
    ///
    /// # Rely
    /// Consumes the stream to the end. A caller wanting to render as it arrives
    /// wants the stream rather than this.
    public static func from(
        _ events: AsyncThrowingStream<TurnEvent, any Error>
    ) async throws -> String {
        var said = ""
        for try await event in events {
            // Only text. `toolResult` carries what a tool produced, which is
            // written for the model rather than for a person, and `answering`
            // and `decided` are about how the turn was served rather than what
            // it found.
            if case .text(let chunk) = event {
                said += chunk
            }
        }

        // Trimmed at the end rather than per chunk: text arrives split at
        // arbitrary points, and a chunk that is only a space is a word boundary
        // rather than something to drop.
        let trimmed = said.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw AnswerError.nothingSaid }
        return trimmed
    }
}
