// RoutingPerformanceTests.swift — what a routing decision costs on a phone.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   RoutingPerformanceTests  Latency and sustained-load measurements for `decide`.
//
// `decide` sits in front of every turn, so its cost is paid before the model is
// even asked. On a board that is a fraction of a network round trip and invisible;
// on a phone the same code runs on a smaller core, and nothing here had ever
// measured it there.
//
// These assert a ceiling rather than a number. A measurement that fails whenever
// the machine is busy teaches people to re-run it until it passes, so the bounds
// are set well above what the simulator produces — they exist to catch a change
// that makes routing an order of magnitude worse, not to police a few
// microseconds. `measure` records the distribution either way, and that is what a
// reader should look at.

import Foundation
import XCTest

@testable import WattRouter

final class RoutingPerformanceTests: XCTestCase {
    /// A body of roughly `words` words, as a turn would send.
    private func body(words: Int) -> String {
        var conversation = Conversation()
        conversation.append(.user(Array(repeating: "token", count: words).joined(separator: " ")))
        return conversation.requestBody()
    }

    /// One decision, at the size most turns are.
    ///
    /// The heuristic pass answers a short prompt without embedding it, so this is
    /// the path nearly every turn takes.
    func testASingleDecisionIsFastEnoughToPrecedeEveryTurn() throws {
        let router = try makeRouter()
        let request = body(words: 40)

        var decided = 0
        measure {
            for _ in 0..<100 where router.decide(body: request) != nil {
                decided += 1
            }
        }

        XCTAssertGreaterThan(decided, 0, "no decision was reached, so nothing was measured")
    }

    /// The same decision when the conversation is long enough to score.
    ///
    /// Separated from the short case because they are different paths, and an
    /// average over both would hide whichever regressed.
    func testALongConversationIsStillDecidedPromptly() throws {
        let router = try makeRouter()
        let request = body(words: 4000)

        measure {
            for _ in 0..<20 {
                _ = router.decide(body: request)
            }
        }
    }

    /// Building the body a decision reads.
    ///
    /// Encoding is per turn and grows with the transcript, so it is worth knowing
    /// separately from the decision it feeds.
    func testBuildingARequestBodyIsCheap() {
        var conversation = Conversation(system: "be brief")
        for i in 0..<200 {
            conversation.append(i.isMultiple(of: 2) ? .user("ask \(i)") : .assistant("answer \(i)"))
        }

        measure {
            for _ in 0..<50 {
                _ = conversation.requestBody()
            }
        }
    }

    /// Sustained load down one session.
    ///
    /// Stickiness keeps a session's tier in a cache that `decide` mutates, so a
    /// long conversation is the case where that cache is doing the most work.
    /// A thousand decisions is more than a person will have in a sitting.
    func testAThousandDecisionsDownOneSessionStayCorrect() throws {
        let router = try makeRouter()
        let request = body(words: 40)

        let turns = 1000
        let started = Date()
        var decisions = 0
        for _ in 0..<turns where router.decide(body: request) != nil {
            decisions += 1
        }
        let elapsed = Date().timeIntervalSince(started)

        XCTAssertEqual(decisions, turns, "a decision went missing under load")

        // Generous by design: see the note at the top of this file.
        XCTAssertLessThan(
            elapsed, 10.0,
            "1000 decisions took \(elapsed)s, which is far beyond anything a phone should need")
        // Divided by the count rather than by the literal it happens to equal:
        // at 1000 iterations `elapsed / 1000 * 1000` is `elapsed`, so the wrong
        // formula reads correctly here and silently stops doing so if the count
        // ever changes.
        print("routing load: \(turns) decisions in \(elapsed)s, "
            + "\(elapsed / Double(turns) * 1000)ms each")
    }

    /// Many sessions at once, which is what a conversation list looks like.
    ///
    /// Each session id is its own entry in the stickiness cache, so this grows the
    /// cache rather than reusing one row of it.
    func testManySessionsDoNotDegradeADecision() throws {
        let router = try makeRouter()
        let request = body(words: 40)

        let sessions = 500
        let started = Date()
        for session in 0..<sessions {
            _ = router.decide(body: request, session: "session-\(session)")
        }
        let elapsed = Date().timeIntervalSince(started)

        XCTAssertLessThan(
            elapsed, 10.0,
            "\(sessions) distinct sessions took \(elapsed)s, so the cache is not holding its shape")
        print("session load: \(sessions) distinct sessions in \(elapsed)s, "
            + "\(elapsed / Double(sessions) * 1000)ms each")
    }
}
