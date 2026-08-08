// ChainWalk.swift — trying each model in a chain until one answers.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   TurnEvent  What arrives from a chain: who answered, then what they said.
//   ChainWalk  The loop over a tier's models.
//
// `Upstream::forward` is the same loop, and reads the same way apart from two
// differences that come from the split between this and the client.
//
// The rule about what continues lives in `InferenceError.isWorthAnotherModel`
// rather than in a match arm here, so the client and the loop cannot come to
// different conclusions about the same failure.
//
// And a model can fail here in a way it cannot fail there. In the Rust, a failure
// is either a response head or a transport error and the body has not begun; the
// choice to move on is always available. `complete` is a stream, so a model may
// die partway through a sentence, and by then some of the answer has reached the
// caller. No second model can un-deliver it. So the loop retries only when the
// error says another model is worth trying *and* nothing has been yielded yet —
// the contract `Inference` states and this is the code that leans on it.

/// What arrives from a chain.
///
/// `upstream.rs:172` puts the serving model on the response because otherwise "a
/// fallback is visible only in a warning nobody aggregates, and an operator
/// holding a slow answer cannot tell which model produced it". A stream of
/// `String` has nowhere to put that, so it is an event instead.
public enum TurnEvent: Equatable, Sendable {
    /// Which model took the request. Emitted once, before any text, and only
    /// once a model has actually begun answering — so receiving it means the
    /// chain has stopped moving and this model is the one that served the turn.
    case answering(model: String, backend: Backend)
    /// Text, in the order the model produced it.
    case text(String)
    /// A tool the model wants run. Forwarded rather than handled here: what to do
    /// about it is the turn loop's, and a walk that dropped it would leave the
    /// model waiting on a result nothing was ever going to produce.
    case toolCall(ToolCall)
    /// What a tool produced. Never from a walk: the loop above yields this, and
    /// shares the type so an interface has one stream rather than two.
    case toolResult(ToolResult)
    /// How the round was routed, and the chain standing behind it. Never from a
    /// walk either — the turn loop decides and yields this before the walk
    /// starts, so an interface can say which tier is answering while it answers.
    ///
    /// Carried here rather than re-derived, because `Router.decide` mutates the
    /// session cache: asking a second time is not a free observation, and the
    /// answer it gives is not necessarily the one the turn ran on.
    case decided(Decision, chain: [Step])
}

/// The loop over a tier's models.
public struct ChainWalk: Sendable {
    private let inference: any Inference

    /// - Parameter inference: how one model is asked. A chain is a list of
    ///   models, and what it means to ask one is not this type's business.
    public init(asking inference: any Inference) {
        self.inference = inference
    }

    /// Ask each model in `steps` until one answers.
    ///
    /// - Parameters:
    ///   - conversation: everything said so far.
    ///   - steps: the models to try, in order, from `Router.chain(for:)`.
    ///   - maxTokens: a cap on the reply, or `nil` for the provider's default.
    /// - Returns: `answering` naming the model that took the request, then its
    ///   text. Nothing at all if every model failed.
    ///
    /// # Rely
    /// Cancellation is not a model failure and does not advance the chain: a
    /// `CancellationError` ends the walk where it stands.
    public func complete(
        _ conversation: Conversation, following steps: [Step], maxTokens: Int? = nil
    ) -> AsyncThrowingStream<TurnEvent, any Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                var tried = 0
                var last = "the chain was empty"

                for step in steps {
                    tried += 1

                    // `Config` refuses to start a server with a tier configured
                    // local, so `upstream.rs` checks this once and never again.
                    // The FFI makes no such promise, and nothing in this app can
                    // run a model in process — so it is counted as an attempt and
                    // skipped, rather than quietly shortening the chain.
                    guard step.backend == .remote else {
                        last = "\(step.model) is configured to run locally, and nothing here can"
                        continue
                    }

                    var delivered = false
                    do {
                        for try await event in inference.complete(
                            conversation, model: step.model, maxTokens: maxTokens)
                        {
                            // A tool call counts as delivered exactly as text
                            // does: once one has been handed up, a second model
                            // would produce a different one.
                            if !delivered {
                                delivered = true
                                continuation.yield(
                                    .answering(model: step.model, backend: step.backend))
                            }
                            switch event {
                            case .text(let text): continuation.yield(.text(text))
                            case .toolCall(let call): continuation.yield(.toolCall(call))
                            }
                        }
                        // A model that answered with nothing still answered. The
                        // alternative is spending the rest of the chain on a
                        // question whose answer is legitimately empty.
                        if !delivered {
                            continuation.yield(.answering(model: step.model, backend: step.backend))
                        }
                        return continuation.finish()
                    } catch let error as InferenceError
                        where !delivered && error.isWorthAnotherModel
                    {
                        last = String(describing: error)
                        continue
                    } catch {
                        // Either nothing here can help — a refused request, a
                        // cancellation — or text has already gone out and moving
                        // on would splice two models into one answer.
                        return continuation.finish(throwing: error)
                    }
                }

                continuation.finish(throwing: InferenceError.exhausted(tried: tried, last: last))
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
