// ClarifyTool.swift — asking the person, and waiting.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   Clarifier    A question waiting to be answered.
//   ClarifyTool  Asking one, as a tool.
//
// Every other tool returns from a computation. This one returns from a person,
// which makes it the only one whose duration is unbounded and the only one where
// cancellation is an ordinary ending rather than a fault.
//
// Nothing here draws anything; the view belongs with the rest of the interface.
// The waiting is built first because retrofitting suspension into a tool that
// returned immediately means changing the loop around it.
//
// Two questions at once are refused rather than queued. A person answers one
// thing at a time, and a queue leaves the model waiting on an answer to a
// question nobody has been shown.
//
// Cancellation clears the question as well as resuming the tool. A plain
// `withCheckedContinuation` does the second and not the first, and the interface
// then goes on showing a prompt whose answer nothing is waiting for.

import Foundation

/// The question the interface should be showing, if any.
public actor Clarifier {
    /// Something the model wants to know.
    public struct Question: Identifiable, Equatable, Sendable {
        public let id: UUID
        /// What to show. One question, in the person's terms.
        public let text: String
        /// Answers offered as a tap. Empty means the person types one.
        public let options: [String]
    }

    /// Why a question could not be asked.
    public enum Failure: LocalizedError, Equatable, Sendable {
        case alreadyAsking(String)

        public var errorDescription: String? {
            switch self {
            case .alreadyAsking(let text):
                "already waiting on an answer to: \(text). Ask one thing at a time."
            }
        }
    }

    /// What the interface renders. `nil` when nothing is being asked.
    public private(set) var pending: Question?
    private var waiting: CheckedContinuation<String, any Error>?

    public init() {}

    /// Ask, and wait for an answer.
    ///
    /// - Throws: [`Failure.alreadyAsking`] IF a question is already up, and
    ///   `CancellationError` if the turn ends first.
    public func ask(_ text: String, options: [String] = []) async throws -> String {
        guard pending == nil else { throw Failure.alreadyAsking(pending?.text ?? "") }

        let question = Question(id: UUID(), text: text, options: options)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                // Cancelled before the handler was installed: resume here, or
                // this suspends for ever with nothing able to reach it.
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                pending = question
                waiting = continuation
            }
        } onCancel: {
            Task { await self.cancel() }
        }
    }

    /// Answer the question being shown. Ignored when there is none, which is what
    /// a second tap on a sheet already dismissing produces.
    public func answer(_ text: String) {
        guard let continuation = waiting else { return }
        pending = nil
        waiting = nil
        continuation.resume(returning: text)
    }

    /// Take the question down and let the tool go.
    private func cancel() {
        guard let continuation = waiting else { return }
        pending = nil
        waiting = nil
        continuation.resume(throwing: CancellationError())
    }
}

/// Ask the person something.
public struct ClarifyTool: Tool {
    public let name = "clarify"

    public let purpose = """
        Ask the person a question and wait for their answer. Use this when a \
        choice is theirs to make — which of two approaches, whether to overwrite \
        something, what a name should be — and not to check work you can check \
        yourself. Offer `options` when the answer is one of a few things; leave \
        them out when it is not.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "question": {"type": "string", "description": "One question, in their terms."},
            "options": {
              "type": "array",
              "items": {"type": "string"},
              "description": "Answers to offer as a choice. Omit for a free answer."
            }
          },
          "required": ["question"]
        }
        """

    private let clarifier: Clarifier

    public init(clarifier: Clarifier) {
        self.clarifier = clarifier
    }

    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)
        return try await clarifier.ask(request.question, options: request.options ?? [])
    }

    private struct Request: Decodable {
        let question: String
        let options: [String]?
    }
}
