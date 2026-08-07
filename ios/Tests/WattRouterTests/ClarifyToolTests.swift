// ClarifyToolTests.swift — waiting for a person, and giving up on one.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// A tool that suspends is the only one where a test can hang rather than fail, so
// each of these either resumes the wait itself or cancels it.

import Foundation
import XCTest

@testable import WattRouter

final class ClarifyToolTests: XCTestCase {
    /// Wait until a question is up, so a test never answers one that has not been
    /// asked yet. The tool suspends on another task and there is no ordering
    /// between them without this.
    private func awaitingQuestion(_ clarifier: Clarifier) async throws -> Clarifier.Question {
        for _ in 0..<200 {
            if let question = await clarifier.pending { return question }
            try await Task.sleep(for: .milliseconds(5))
        }
        throw XCTSkip("no question appeared")
    }

    func testAnAnswerIsWhatTheToolReturns() async throws {
        let clarifier = Clarifier()
        let tool = ClarifyTool(clarifier: clarifier)

        let asking = Task {
            try await tool.run(
                arguments: Data(#"{"question":"which one?","options":["a","b"]}"#.utf8))
        }

        let question = try await awaitingQuestion(clarifier)
        XCTAssertEqual(question.text, "which one?")
        XCTAssertEqual(question.options, ["a", "b"])

        await clarifier.answer("b")
        let answer = try await asking.value
        XCTAssertEqual(answer, "b")
    }

    func testTheQuestionComesDownOnceItIsAnswered() async throws {
        // Otherwise the interface goes on showing a prompt nothing is waiting for.
        let clarifier = Clarifier()
        let asking = Task { try await clarifier.ask("still there?") }

        _ = try await awaitingQuestion(clarifier)
        await clarifier.answer("no")
        _ = try await asking.value

        let pending = await clarifier.pending
        XCTAssertNil(pending)
    }

    func testASecondQuestionIsRefusedRatherThanQueued() async throws {
        // A person answers one thing at a time, and a queue leaves the model
        // waiting on an answer to a question nobody has been shown.
        let clarifier = Clarifier()
        let asking = Task { try await clarifier.ask("first") }
        _ = try await awaitingQuestion(clarifier)

        do {
            _ = try await clarifier.ask("second")
            XCTFail("two at once should be refused")
        } catch let error as Clarifier.Failure {
            XCTAssertEqual(error, .alreadyAsking("first"))
        }

        // And the first is still answerable afterwards.
        await clarifier.answer("done")
        let answer = try await asking.value
        XCTAssertEqual(answer, "done")
    }

    func testCancellingTakesTheQuestionDownAndLetsTheToolGo() async throws {
        // The case a plain `withCheckedContinuation` half-handles: it resumes the
        // tool and leaves the prompt on screen.
        let clarifier = Clarifier()
        let asking = Task { try await clarifier.ask("waiting") }
        _ = try await awaitingQuestion(clarifier)

        asking.cancel()
        do {
            _ = try await asking.value
            XCTFail("a cancelled wait does not return an answer")
        } catch is CancellationError {
        } catch {
            XCTFail("expected CancellationError, got \(error)")
        }

        for _ in 0..<200 where await clarifier.pending != nil {
            try await Task.sleep(for: .milliseconds(5))
        }
        let pending = await clarifier.pending
        XCTAssertNil(pending, "the prompt is still up")
    }

    func testAnAnswerWithNothingWaitingIsIgnored() async {
        // A second tap on a sheet that is already dismissing.
        let clarifier = Clarifier()
        await clarifier.answer("nobody asked")
        let pending = await clarifier.pending
        XCTAssertNil(pending)
    }

    func testAQuestionWithoutOptionsIsAFreeAnswer() async throws {
        let clarifier = Clarifier()
        let tool = ClarifyTool(clarifier: clarifier)
        let asking = Task {
            try await tool.run(arguments: Data(#"{"question":"what name?"}"#.utf8))
        }

        let question = try await awaitingQuestion(clarifier)
        XCTAssertTrue(question.options.isEmpty)

        await clarifier.answer("Ada")
        let answer = try await asking.value
        XCTAssertEqual(answer, "Ada")
    }
}
