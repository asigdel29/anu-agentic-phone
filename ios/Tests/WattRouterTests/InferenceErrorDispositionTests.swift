// InferenceErrorDispositionTests.swift — the fallback rule, over every status.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The rule is a function of one integer, so these sweep the range rather than
// sampling it. A table of four plausible statuses passes against an off-by-one at
// either boundary, and both boundaries separate opposite answers.

import XCTest

@testable import WattRouter

final class InferenceErrorDispositionTests: XCTestCase {
    func testTheBoundariesFallWhereTheyShould() {
        // Each pair either side of a line. 299/300 and 399/400 are the two that a
        // range written with `...` instead of `..<` would get wrong.
        let boundaries: [(Int, InferenceError.Disposition)] = [
            (199, .retry), (200, .answer), (299, .answer), (300, .retry),
            (399, .retry), (400, .stop), (499, .stop), (500, .retry), (599, .retry),
        ]
        for (status, expected) in boundaries {
            XCTAssertEqual(InferenceError.disposition(ofStatus: status), expected, "\(status)")
        }
    }

    func testEveryClientErrorStopsAndEveryServerErrorRetries() {
        for status in 400..<500 {
            XCTAssertEqual(InferenceError.disposition(ofStatus: status), .stop, "\(status)")
        }
        for status in 500..<600 {
            XCTAssertEqual(InferenceError.disposition(ofStatus: status), .retry, "\(status)")
        }
    }

    func testASuccessIsNeverAFailure() {
        // The case that matters most for cost: a 2xx must never send a chain on to
        // a second model, because the first one has already been paid for.
        for status in 200..<300 {
            XCTAssertEqual(InferenceError.disposition(ofStatus: status), .answer, "\(status)")
        }
    }

    func testTheStatusesWhereTheServersReasonDoesNotHold() {
        // upstream.rs:146 stops on a 4xx because "the next model would reject the
        // same body identically". For these two that is not true — a 404 may be a
        // model this account cannot see, and a 429 a model that is merely busy —
        // and the behaviour still matches the server. A router and its client
        // disagreeing about when to fall back is worse than either rule alone.
        XCTAssertEqual(InferenceError.disposition(ofStatus: 404), .stop)
        XCTAssertEqual(InferenceError.disposition(ofStatus: 429), .stop)

        // And the case that settles which way to be wrong: a bad credential
        // retried down a chain is one mistake spent three times.
        XCTAssertEqual(InferenceError.disposition(ofStatus: 401), .stop)
    }

    func testTheDispositionSaysTheSameThingAsTheErrorItLeadsTo() {
        // Two spellings of one rule, and they have to agree: `.stop` is what the
        // client turns into `.rejected`, `.retry` into `.unavailable`, and it is
        // `isWorthAnotherModel` the chain walk actually reads.
        XCTAssertFalse(
            InferenceError.rejected(model: "m", status: 400, detail: "").isWorthAnotherModel)
        XCTAssertTrue(InferenceError.unavailable(model: "m", detail: "").isWorthAnotherModel)
    }
}
