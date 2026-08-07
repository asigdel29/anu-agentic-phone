// Inference.swift — asking a model, and reading its answer as it is produced.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   InferenceError     Why an attempt failed, and whether another model is worth trying.
//   Inference          Ask one model; receive the answer in chunks.
//   ScriptedInference  A fixed answer, delivered in chunks. For previews and tests.
//
// Streaming rather than a single return, and that decision is the whole shape of
// this file. Both implementations that will exist produce a token at a time — the
// upstream API over the network, a local model in this process — and a
// `-> String` signature would force each of them to buffer. It would also make
// the latency split unmeasurable: prompt-eval and generation are distinguishable
// only by when the first chunk arrives. Retrofitting streaming means changing
// every caller, so it goes in before there is one.
//
// The other half is the error type. A chain exists so a failed attempt can be
// retried against the next model, and the walk that does that lives above this
// seam rather than inside it. So the distinction `upstream.rs:146` makes in a
// match arm has to be a value here: a server error is worth another model, a
// client error is not, "since the next model would reject the same body
// identically".

import Foundation

/// Why an attempt failed.
///
/// The cases exist to be told apart by a caller walking a chain, which is why
/// this is not one case with a string in it.
public enum InferenceError: Error, Equatable, Sendable {
    /// The model could not be reached, or failed on its own account: a dropped
    /// connection, a timeout, a 5xx. Another model may answer the same request.
    case unavailable(model: String, detail: String)

    /// The request was rejected on its merits — a 4xx. Every model behind the
    /// same API would reject it identically, so a chain stops here rather than
    /// spending its remaining attempts proving that.
    case rejected(model: String, status: Int, detail: String)

    /// Every model in the chain failed. Produced by the walk over a chain, never
    /// by a single attempt; an [`Inference`] must not return it.
    case exhausted(tried: Int, last: String)

    /// Whether the next model in a chain is worth trying.
    ///
    /// The chain walk reads this instead of matching, so the rule is written once
    /// here and a new case cannot silently default to retrying.
    public var isWorthAnotherModel: Bool {
        switch self {
        case .unavailable: true
        case .rejected, .exhausted: false
        }
    }
}

/// One model, asked one question.
///
/// Deliberately below the chain: an implementation speaks to a single model and
/// reports how it failed, and choosing what to try next is somebody else's job.
public protocol Inference: Sendable {
    /// Ask `model` to continue `conversation`.
    ///
    /// - Parameters:
    ///   - conversation: everything said so far, oldest first.
    ///   - model: the model to ask, named as the provider names it. A [`Step`]
    ///     carries one, along with where it runs.
    ///   - maxTokens: a cap on the reply, or `nil` for the provider's default.
    /// - Returns: the answer in the order it is produced. Chunks are fragments of
    ///   text and carry no token boundary — a caller that needs the whole answer
    ///   concatenates them.
    ///
    /// # Rely
    /// A failure before the first chunk is the caller's to retry. A failure after
    /// one has been yielded is not: text has already reached the caller, and no
    /// second model can un-deliver it. Conformances must therefore not yield a
    /// chunk they are not committed to — in particular, nothing may be emitted
    /// before the response status is known.
    ///
    /// # Atomic
    /// Safe to call concurrently. Each call owns its own stream; ending one, by
    /// cancellation or by leaving the loop early, must not disturb another.
    func complete(_ conversation: Conversation, model: String, maxTokens: Int?)
        -> AsyncThrowingStream<String, any Error>
}

/// A fixed answer, delivered in chunks, optionally ending in a failure.
///
/// In the library rather than the test target on purpose: a SwiftUI preview needs
/// something to render, and so does the app before it can reach a model at all.
public struct ScriptedInference: Inference {
    /// Yielded in order, then `failure` if there is one.
    public let chunks: [String]
    /// Waited before each chunk, so a caller can be seen to receive them
    /// separately rather than all at once.
    public let perChunk: Duration
    /// Ends the stream after the last chunk, or `nil` to end it normally.
    public let failure: InferenceError?

    /// Script the chunks exactly.
    ///
    /// Empty `chunks` with a `failure` is a model that fails outright — the case
    /// a chain walk is allowed to retry.
    public init(chunks: [String], perChunk: Duration = .zero, failure: InferenceError? = nil) {
        self.chunks = chunks
        self.perChunk = perChunk
        self.failure = failure
    }

    /// Deliver `answer` a word at a time, as a model would.
    ///
    /// The spaces are kept on the chunks rather than dropped, so concatenating
    /// what arrives reproduces `answer` and a caller can join without knowing it
    /// was ever split.
    public init(answer: String, perChunk: Duration = .zero) {
        let words = answer.split(separator: " ", omittingEmptySubsequences: false)
        self.init(
            chunks: words.enumerated().map { index, word in
                index == words.count - 1 ? String(word) : "\(word) "
            },
            perChunk: perChunk)
    }

    public func complete(_ conversation: Conversation, model: String, maxTokens: Int?)
        -> AsyncThrowingStream<String, any Error>
    {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for chunk in chunks {
                        // Zero is the common case — a test that only wants the
                        // text — and sleeping for it would still suspend.
                        if perChunk > .zero { try await Task.sleep(for: perChunk) }
                        continuation.yield(chunk)
                    }
                    continuation.finish(throwing: failure)
                } catch {
                    // Cancellation arrives here, from the sleep.
                    continuation.finish(throwing: error)
                }
            }
            // A caller that leaves its loop early ends the stream; without this
            // the task would run to the end of the script regardless, which for a
            // real implementation is a request nobody is waiting for.
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
