// TurnDriver.swift — running a turn, and keeping what it produced.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   TurnDriver  Sends text, folds the events, and records why a turn stopped.
//
// Small, and worth being a type with a name because it is where the failure path
// lives. `Agent.send` yields a throwing stream: a turn can end by finishing or by
// throwing, and the difference has to reach the transcript rather than a log
// nobody reads. A view that consumed the stream itself would be a view with a
// catch block in it.
//
// On the main actor because the transcript it writes is read by a view on every
// change. Streaming text at a few fragments a second is not work worth moving off
// it, and moving it would buy a hop back for every fragment.

import Foundation

/// Runs turns and keeps the transcript they produce.
@MainActor
@Observable
public final class TurnDriver {
    /// Everything said so far, as rows.
    public private(set) var transcript = Transcript()

    /// Whether a turn is in flight. A second `send` while one is running is
    /// ignored rather than queued: two turns over one conversation interleave
    /// their rounds, and the model is sent a transcript that never happened.
    public private(set) var isRunning = false

    /// How the current round was routed, or `nil` before the first one. Kept
    /// rather than folded into the transcript: it is replaced each round, and a
    /// row that rewrote itself would be a strange thing to scroll back through.
    public private(set) var routing: (decision: Decision, chain: [Step])?

    private let agent: Agent

    public init(agent: Agent) {
        self.agent = agent
    }

    /// Say something, and run the turn it starts.
    ///
    /// # Rely
    /// Called from the main actor. Returns when the turn has finished or failed;
    /// the transcript is updated as events arrive rather than at the end, so a
    /// caller that does not await it still sees the answer stream in.
    ///
    /// # Atomic
    /// Refuses to start a second turn while one is running, so the transcript
    /// only ever has one open answer.
    public func send(_ text: String) async {
        guard !isRunning, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return
        }

        isRunning = true
        transcript.said(text)
        defer { isRunning = false }

        do {
            for try await event in await agent.send(text) {
                if case .decided(let decision, let chain) = event {
                    routing = (decision, chain)
                }
                transcript.apply(event)
            }
        } catch {
            // The reason, not the type. A person reading this has no use for a
            // case name, and `AgentError` already writes itself out in words.
            transcript.failed(error.localizedDescription)
        }
    }
}
