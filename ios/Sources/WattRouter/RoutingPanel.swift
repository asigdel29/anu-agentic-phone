// RoutingPanel.swift — which tier answered, and why.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   RoutingPanel  The current round's decision, and the chain behind it.
//
// The turn loop has always known this and never said it. `.answering` names the
// model that ended up serving the turn, which is the outcome; the tier, the
// reason, the score and the chain are the decision, and only the decision
// explains the outcome.
//
// Cyan throughout, because this is the app describing itself rather than talking
// to anyone — the same voice tool rows use. Signal is for what was said.

import SwiftUI

/// How the current round was routed.
public struct RoutingPanel: View {
    private let decision: Decision
    private let chain: [Step]

    public init(decision: Decision, chain: [Step]) {
        self.decision = decision
        self.chain = chain
    }

    public var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(decision.tier.name)
                .foregroundStyle(Theme.signal.color)
            Text(decision.reason.name)
                .foregroundStyle(Theme.cyan.color)
            Text(scoreText)
                .foregroundStyle(Theme.cyan.color.opacity(0.7))
            Spacer(minLength: 8)
            Text(chainText)
                .foregroundStyle(Theme.cyan.color.opacity(0.7))
                .lineLimit(1)
                .truncationMode(.head)
        }
        .font(.caption2.monospaced())
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Theme.ground.color)
    }

    /// The score, or the fact that there was not one.
    ///
    /// Internal rather than private: it and `chainText` are the panel's entire
    /// content, and a rule about how a missing score reads is worth a test rather
    /// than a look at a screen.
    ///
    /// `Decision.score` is `nil` when nothing scored the prompt — no head loaded,
    /// or no text to score — and rendering that as `0.00` would state something
    /// false about a prompt nobody measured.
    var scoreText: String {
        guard let score = decision.score else { return "unscored" }
        return String(format: "%.2f", score)
    }

    /// The chain, first attempt first.
    ///
    /// The whole chain rather than the model that answered: what a reader cannot
    /// otherwise know is what would have been tried next, and that is the part
    /// that explains a slow turn after a model fell over.
    var chainText: String {
        chain.map(\.model).joined(separator: " → ")
    }
}
