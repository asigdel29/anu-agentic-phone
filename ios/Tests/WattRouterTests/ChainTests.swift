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
    func testEveryTierHasAChainWithSomewhereToFallBackTo() throws {
        let router = try makeRouter()
        for tier in Tier.allCases {
            let chain = router.chain(for: tier)
            // A chain of one means a single failure costs the turn, and this
            // subsumes the chain being present at all.
            XCTAssertGreaterThan(chain.count, 1, "\(tier.name) has no fallback")
            XCTAssertLessThanOrEqual(chain.count, 3, "\(tier.name) is longer than MAX_CHAIN")
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

        // Model names are checked in `testAChainNamesDistinctModels`; what this
        // adds is the backend, since the board's configuration is what an
        // unconfigured app inherits and nothing else here reads that half.
        XCTAssertEqual(first.backend, .remote)
    }

    func testEachTierCodeReachesItsOwnChain() throws {
        let router = try makeRouter()
        // Every tier leads with its own model and no two tiers share one, so six
        // distinct leads is what proves each tier's code is passed through to the
        // core rather than one code answering for several.
        //
        // The names themselves are deliberately not written out. They are the
        // core's to choose — `Tier::default_model` says the catalogue moves
        // faster than the source does, and `chain_for` is already checked
        // through C for every tier in `a_chain_crosses_the_boundary_in_order`.
        // Transcribing them here would put a tripwire on a routine model swap in
        // a test target about the port.
        let leads = Tier.allCases.compactMap { router.chain(for: $0).first?.model }
        XCTAssertEqual(leads.count, Tier.allCases.count, "a tier has no chain")
        XCTAssertEqual(
            Set(leads).count, Tier.allCases.count,
            "two tiers led with the same model, so a tier code is not reaching the core")
    }
}
