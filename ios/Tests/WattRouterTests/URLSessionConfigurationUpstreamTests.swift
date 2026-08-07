// URLSessionConfigurationUpstreamTests.swift — the two timeouts, not swapped.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// A test over two constants looks like a test of nothing, and would be if the
// constants were arbitrary. These are not: they map two named settings from
// `Upstream::new` onto two `URLSession` properties whose names point the opposite
// way. Reversing them compiles, runs, and passes every other test in this target,
// because both failures need a real network and either minutes or a dead socket
// to show themselves.

import Foundation
import XCTest

@testable import WattRouter

final class URLSessionConfigurationUpstreamTests: XCTestCase {
    func testTheIdleTimeoutIsTheOneNamedForTheRequest() {
        // The trap. `timeoutIntervalForRequest` is documented as the wait for
        // additional data, so it takes `read_timeout`, not `timeout`.
        let configuration = URLSessionConfiguration.upstream()
        XCTAssertEqual(
            configuration.timeoutIntervalForRequest, 120,
            "upstream.rs:65 read_timeout — how long the wire may stay silent")
        XCTAssertEqual(
            configuration.timeoutIntervalForResource, 1800,
            "upstream.rs:64 timeout — the budget for a whole answer")
    }

    func testAnAnswerMayTakeLongerThanTheWireMayGoQuiet() {
        // The relationship, stated separately from the values: whichever way the
        // numbers are tuned later, an answer's budget has to exceed the tolerance
        // for silence, or a slow model can never finish.
        let configuration = URLSessionConfiguration.upstream()
        XCTAssertGreaterThan(
            configuration.timeoutIntervalForResource, configuration.timeoutIntervalForRequest)
    }

    func testAWaitingPersonIsNotQueuedBehindALostNetwork() {
        // `waitsForConnectivity` defaults to true, which turns an unreachable
        // provider into a request that sits there — indistinguishable, from the
        // caller, from a model thinking hard. The chain has somewhere else to go
        // and cannot go there while this call has not failed.
        XCTAssertFalse(URLSessionConfiguration.upstream().waitsForConnectivity)
    }

    func testNothingReachesDisk() {
        // Every request carries a bearer token and every response carries what a
        // model said. An ephemeral configuration still has a cache, a cookie jar
        // and a credential store — they are memory-only, not absent, which is why
        // this asserts the disk capacity rather than their absence. Written down,
        // both would leave in a device backup.
        let cache = URLSessionConfiguration.upstream().urlCache
        XCTAssertEqual(cache?.diskCapacity, 0, "responses would be cached to disk")
    }

    func testEachCallerGetsItsOwnConfiguration() {
        // A shared instance would let a test's stubbed `URLProtocol` leak into
        // whatever the app built next, which is the sort of thing that passes on
        // one machine.
        let one = URLSessionConfiguration.upstream()
        one.timeoutIntervalForRequest = 1
        XCTAssertEqual(URLSessionConfiguration.upstream().timeoutIntervalForRequest, 120)
    }
}
