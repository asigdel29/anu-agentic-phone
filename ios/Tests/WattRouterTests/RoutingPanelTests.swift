// RoutingPanelTests.swift — the decision reaches the driver, and reads honestly.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Two things are worth checking and neither is a layout. That the decision leaves
// the loop at all — it never used to — and that an unscored prompt does not
// render as a score of zero, which would state something false about a prompt
// nothing measured.

import Foundation
import XCTest

@testable import WattRouter

@MainActor
final class RoutingPanelTests: XCTestCase {
    private func makeDriver(_ inference: any Inference) throws -> TurnDriver {
        TurnDriver(
            agent: Agent(
                router: try makeRouter(), inference: inference, tools: ToolBox([])))
    }

    func testATurnReportsHowItWasRouted() async throws {
        let driver = try makeDriver(ScriptedInference(chunks: ["hi"]))
        XCTAssertNil(driver.routing, "routed before anything was asked")

        await driver.send("hello")

        let routing = try XCTUnwrap(driver.routing, "the decision never left the loop")
        XCTAssertFalse(
            routing.chain.isEmpty, "a tier with no chain has nothing to fall back to")
        XCTAssertFalse(routing.decision.tier.name.isEmpty)
        XCTAssertFalse(routing.decision.reason.name.isEmpty)
    }

    func testTheDecisionArrivesBeforeTheAnswer() async throws {
        // The panel exists to say which tier is answering while it answers. If the
        // decision arrived last it could only ever describe a finished turn.
        let agent = Agent(
            router: try makeRouter(),
            inference: ScriptedInference(chunks: ["a", "b"]),
            tools: ToolBox([]))

        var seen: [String] = []
        for try await event in await agent.send("hello") {
            switch event {
            case .decided: seen.append("decided")
            case .text: seen.append("text")
            default: break
            }
        }

        XCTAssertEqual(seen.first, "decided", "the decision came after the text: \(seen)")
    }

    func testAnUnscoredPromptSaysSoRatherThanScoringZero() {
        // score is nil when no head is loaded or there was nothing to score.
        // "0.00" is a measurement; this was not measured.
        let unscored = Decision(tier: .mid, reason: .unscored, score: nil)
        let panel = RoutingPanel(decision: unscored, chain: [])
        XCTAssertEqual(panel.scoreText, "unscored")

        let scored = Decision(tier: .heavy, reason: .scored, score: 0.875)
        XCTAssertEqual(RoutingPanel(decision: scored, chain: []).scoreText, "0.88")
    }

    func testTheWholeChainIsShownAndNotJustTheFirst() {
        // What a reader cannot otherwise know is what would have been tried next,
        // which is what explains a slow turn after a model fell over.
        let panel = RoutingPanel(
            decision: Decision(tier: .mid, reason: .scored, score: 0.5),
            chain: [
                Step(backend: .remote, model: "first"),
                Step(backend: .remote, model: "second"),
            ])
        XCTAssertEqual(panel.chainText, "first → second")
    }
}
