// AnswerTests.swift — what one sentence gets made out of a turn.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Everything here ends up spoken by Siri, which is what makes the cases worth
// writing: the failure is not an exception, it is a person hearing a paragraph
// of JSON or hearing nothing at all.

import Foundation
import XCTest

@testable import WattRouter

final class AnswerTests: XCTestCase {
    /// A turn, as a stream of the events it would have produced.
    private func turn(_ events: [TurnEvent]) -> AsyncThrowingStream<TurnEvent, any Error> {
        AsyncThrowingStream { continuation in
            for event in events { continuation.yield(event) }
            continuation.finish()
        }
    }

    func testTextArrivingInPiecesIsOneSentence() async throws {
        // Which is how it always arrives: the split points are the provider's
        // and mean nothing.
        let said = try await Answer.from(
            turn([.text("You have "), .text("two things "), .text("on today.")]))

        XCTAssertEqual(said, "You have two things on today.")
    }

    func testAToolResultIsNotPartOfTheAnswer() async throws {
        // Written for the model, not for a person. Spoken aloud it is a
        // paragraph of somebody's calendar read out before the sentence about it.
        let said = try await Answer.from(
            turn([
                .toolCall(ToolCall(id: "a", name: "read_calendar", arguments: "{}")),
                .toolResult(ToolResult(id: "a", content: "2026-08-09 10:00 Dentist (Personal)")),
                .text("You have the dentist at ten."),
            ]))

        XCTAssertEqual(said, "You have the dentist at ten.")
    }

    func testHowTheTurnWasRoutedIsNotPartOfTheAnswer() async throws {
        // `answering` and `decided` are about how it was served rather than what
        // it found, and a person asking about their day did not ask which model
        // answered.
        let said = try await Answer.from(
            turn([
                .decided(
                    Decision(tier: .mid, reason: .unscored, score: nil),
                    chain: [Step(backend: .remote, model: "a-model")]),
                .answering(model: "a-model", backend: .remote),
                .text("Nothing today."),
            ]))

        XCTAssertEqual(said, "Nothing today.")
    }

    func testATurnThatSaidNothingIsAFailureRatherThanSilence() async {
        // A turn that ran tools and never spoke has not answered. Returning ""
        // is a response of nothing at all, which the person hears as being
        // ignored rather than as an error.
        let ran = turn([
            .toolCall(ToolCall(id: "a", name: "todo", arguments: "{}")),
            .toolResult(ToolResult(id: "a", content: "nothing on the list")),
        ])

        do {
            _ = try await Answer.from(ran)
            XCTFail("answered with nothing")
        } catch {
            XCTAssertEqual(error as? AnswerError, .nothingSaid)
            XCTAssertTrue(
                (error as? AnswerError)?.errorDescription?.contains("did not say") ?? false)
        }
    }

    func testTextThatIsOnlyWhitespaceIsAlsoNothing() async {
        // A model that emitted a newline and stopped. Indistinguishable to a
        // listener from having said nothing, so it is treated the same.
        do {
            _ = try await Answer.from(turn([.text("  \n ")]))
            XCTFail("answered with whitespace")
        } catch {
            XCTAssertEqual(error as? AnswerError, .nothingSaid)
        }
    }

    func testASpaceBetweenTwoChunksIsKeptRatherThanTrimmedAway() async throws {
        // Trimming per chunk rather than at the end runs the words together.
        let said = try await Answer.from(turn([.text("two"), .text(" "), .text("words")]))

        XCTAssertEqual(said, "two words")
    }

    func testWhatTheTurnThrewComesOutRatherThanBeingSwallowed() async {
        // An intent that reported "nothing said" for a network failure would
        // send the person looking at the wrong thing.
        struct Refused: Error {}
        let broken = AsyncThrowingStream<TurnEvent, any Error> { continuation in
            continuation.yield(.text("half a"))
            continuation.finish(throwing: Refused())
        }

        do {
            _ = try await Answer.from(broken)
            XCTFail("swallowed it")
        } catch {
            XCTAssertTrue(error is Refused, "\(error)")
        }
    }
}
