// Agent.swift — a turn: ask, run what was asked for, ask again.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   AgentError  Why a turn stopped short.
//   Agent       A conversation, and the loop that advances it.
//
// The first thing that uses every piece before it: the router decides a tier, the
// chain walk streams an answer, tools dispatch, and a conversation records both
// halves of an exchange.
//
// A round is committed atomically. A conversation ending in an assistant message
// with `tool_calls` and no results is one the provider refuses, so a round's
// messages are built aside and appended together — the assistant turn and every
// result, or neither. A turn that fails partway leaves the conversation as it
// was rather than as something that cannot be sent again.
//
// Tools run in order, not at once. A `patch` followed by a `read_file` of the
// same path is a correct sequence and a race if they overlap, and a model that
// asked in that order meant it. Concurrency would buy a few hundred milliseconds
// and cost a class of bug that shows up only on a fast day.

import Foundation

/// Why a turn stopped short.
public enum AgentError: LocalizedError, Equatable, Sendable {
    /// The router would not classify the request.
    case cannotDecide
    /// The model kept asking past the cap. Reported rather than silently
    /// returning the last round, which looks identical and is not an answer.
    case tooManyRounds(Int)

    public var errorDescription: String? {
        switch self {
        case .cannotDecide:
            "the request could not be classified"
        case .tooManyRounds(let cap):
            "the model asked for tools \(cap) times without finishing"
        }
    }
}

/// A conversation, and the loop that advances it.
public actor Agent {
    /// Most rounds one turn may take. A model that loops is a bill, and this is
    /// the number that stops it — visible, and the caller's to change.
    public static let defaultMaxRounds = 8

    /// Everything said so far. Readable so an interface can render it, and only
    /// the loop may add to it.
    public private(set) var conversation: Conversation

    private let router: Router
    private let walk: ChainWalk
    private let tools: ToolBox
    private let maxRounds: Int
    /// Identifies the conversation to the router, so a tier it has been raised to
    /// is not dropped partway through.
    private let session = UUID().uuidString

    public init(
        conversation: Conversation = Conversation(),
        router: Router,
        inference: any Inference,
        tools: ToolBox,
        maxRounds: Int = Agent.defaultMaxRounds
    ) {
        self.conversation = conversation
        self.router = router
        self.walk = ChainWalk(asking: inference)
        self.tools = tools
        self.maxRounds = maxRounds
    }

    /// Say something, and run the turn it starts.
    ///
    /// - Returns: what happened, as it happens — which model answered, its text,
    ///   the tools it asked for and what they produced.
    /// - Throws: [`AgentError`], [`InferenceError`], or `CancellationError`.
    public func send(_ text: String) -> AsyncThrowingStream<TurnEvent, any Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    conversation.append(.user(text))
                    try await loop(continuation)
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Rounds, until the model stops asking for tools.
    private func loop(
        _ continuation: AsyncThrowingStream<TurnEvent, any Error>.Continuation
    ) async throws {
        for _ in 0..<maxRounds {
            let round = try await ask(continuation)

            // Built aside and appended together: never a call without its answer.
            var committed = [Message.assistant(round.text, toolCalls: round.calls)]
            for call in round.calls {
                let result = try await tools.run(call)
                continuation.yield(.toolResult(result))
                committed.append(.tool(result))
            }
            // Only now, and all of it. Appending the assistant turn before the
            // results would leave the conversation unsendable if a tool threw.
            committed.forEach { conversation.append($0) }
            if round.calls.isEmpty { return }
        }
        throw AgentError.tooManyRounds(maxRounds)
    }

    /// One exchange with a model: route, ask, collect.
    ///
    /// The tier is decided afresh each round. Results make the conversation
    /// longer, and a tier that suited the question may not hold the transcript;
    /// stickiness already stops a session being dropped to a cheaper one, so
    /// deciding again can only raise it.
    private func ask(
        _ continuation: AsyncThrowingStream<TurnEvent, any Error>.Continuation
    ) async throws -> (text: String, calls: [ToolCall]) {
        guard let decision = router.decide(body: conversation.requestBody(), session: session)
        else { throw AgentError.cannotDecide }

        var text = ""
        var calls: [ToolCall] = []
        for try await event in walk.complete(conversation, following: router.chain(for: decision.tier)) {
            switch event {
            case .text(let chunk): text += chunk
            case .toolCall(let call): calls.append(call)
            case .answering, .toolResult: break
            }
            continuation.yield(event)
        }
        return (text, calls)
    }
}
