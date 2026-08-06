// ChainTests.swift — turning a decision into something to dispatch.
//
// History
//   2026-08-06  A. Sigdel  Created.
//
// A tier is a role. These check that Swift can get from that role to the models
// behind it and to where each one runs, which is what the agent loop needs and
// what a decision alone does not give it.

import XCTest

@testable import WattRouter

final class ChainTests: XCTestCase {
    private func makeRouter() throws -> Router {
        setenv("NEURALWATT_API_KEY", "ios-test", 1)
        return try XCTUnwrap(Router())
    }

    func testEveryTierHasAChainWithSomewhereToFallBackTo() throws {
        let router = try makeRouter()
        for tier in Tier.allCases {
            let chain = router.chain(for: tier)
            XCTAssertFalse(chain.isEmpty, "\(tier.name) has no chain at all")
            XCTAssertLessThanOrEqual(chain.count, 3, "\(tier.name) is longer than MAX_CHAIN")
            // A chain of one means a single failure costs the turn.
            XCTAssertGreaterThan(chain.count, 1, "\(tier.name) has no fallback")
        }
    }

    func testAChainNamesDistinctModels() throws {
        let router = try makeRouter()
        for tier in Tier.allCases {
            let models = router.chain(for: tier).map(\.model)
            XCTAssertEqual(
                Set(models).count, models.count,
                "\(tier.name) repeats a model, which would retry what just failed")
            XCTAssertFalse(models.contains(""), "\(tier.name) has an unnamed model")
        }
    }

    func testADecisionLeadsToSomethingToDispatch() throws {
        let router = try makeRouter()
        var conversation = Conversation()
        conversation.append(.user("hello there"))

        // The whole path the agent loop takes: route, then find out what answers.
        let decision = try XCTUnwrap(router.decide(body: conversation.requestBody()))
        let first = try XCTUnwrap(router.chain(for: decision.tier).first)

        XCTAssertFalse(first.model.isEmpty)
        // The board's configuration, which is what an unconfigured app inherits.
        XCTAssertEqual(first.backend, .remote)
    }

    func testAChainLeadsWithTheTierOwnModel() throws {
        let router = try makeRouter()
        // The configured catalogue, which is what an app inherits until it says
        // otherwise. Written out because the point is that Swift sees the same
        // names the core does, and reading them from the core would assert that
        // the core equals itself.
        let expected: [Tier: String] = [
            .aux: "gemma-4-31b",
            .cheap: "deepseek-v4-flash",
            .mid: "qwen3.6-35b-fast",
            .code: "kimi-k2.7-code",
            .long: "glm-5.2",
            .heavy: "kimi-k3",
        ]
        for (tier, model) in expected {
            XCTAssertEqual(router.chain(for: tier).first?.model, model, "for \(tier.name)")
        }
    }
}
