// RunShortcutToolTests.swift — what gets opened, and what the model is told.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Two halves. The URL, where a shortcut called "Lights & Locks" concatenated
// into a query runs a shortcut called "Lights" — silently, on somebody's actual
// lights. And the sentence, which is the only place the model learns that the
// app is about to disappear and no result is coming.

import Foundation
import XCTest

@testable import WattRouter

/// An opener that records rather than opening, and can decline.
actor StubOpener: Opener {
    private(set) var opened: [URL] = []
    private let succeeds: Bool

    init(succeeds: Bool = true) {
        self.succeeds = succeeds
    }

    func open(_ url: URL) async -> Bool {
        opened.append(url)
        return succeeds
    }
}

final class RunShortcutToolTests: XCTestCase {
    private func tool(_ opener: StubOpener) -> RunShortcutTool {
        RunShortcutTool(opener: opener)
    }

    func testANameWithAnAmpersandSurvivesIntoTheQuery() throws {
        // The case this function exists for. Concatenated, everything after the
        // ampersand becomes a second query parameter and the shortcut that runs
        // is "Lights" — which on somebody's home is a real thing happening.
        let url = try XCTUnwrap(RunShortcutTool.url(name: "Lights & Locks", input: nil))
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)

        XCTAssertEqual(items.first(where: { $0.name == "name" })?.value, "Lights & Locks")
        XCTAssertEqual(items.count, 1)
    }

    func testANameWithSpacesIsEncodedRatherThanBroken() throws {
        let url = try XCTUnwrap(RunShortcutTool.url(name: "Good Morning", input: nil))

        XCTAssertEqual(url.scheme, "shortcuts")
        XCTAssertEqual(url.host, "run-shortcut")
        XCTAssertFalse(url.absoluteString.contains(" "), url.absoluteString)
    }

    func testInputIsPassedAsTextAndAnnouncedAsText() throws {
        // The scheme wants both: `input=text` says what kind, `text=` carries it.
        // One without the other is a shortcut that runs with nothing.
        let url = try XCTUnwrap(RunShortcutTool.url(name: "Note", input: "buy milk"))
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)

        XCTAssertEqual(items.first(where: { $0.name == "input" })?.value, "text")
        XCTAssertEqual(items.first(where: { $0.name == "text" })?.value, "buy milk")
    }

    func testNoInputMeansNoInputParameters() throws {
        let url = try XCTUnwrap(RunShortcutTool.url(name: "Good Morning", input: nil))

        XCTAssertFalse(url.absoluteString.contains("input="), url.absoluteString)
    }

    func testAnEmptyNameBuildsNothing() {
        // Opening Shortcuts with no instruction is worse than not opening it.
        XCTAssertNil(RunShortcutTool.url(name: "", input: nil))
        XCTAssertNil(RunShortcutTool.url(name: "   ", input: nil))
    }

    func testTheAnswerSaysTheAppIsGoingAway() async throws {
        // The only place the model learns this. "Ran the shortcut" invites a next
        // message the person will not see for a while, and reasoning about output
        // that does not exist.
        let opener = StubOpener()
        let said = try await tool(opener)
            .run(arguments: Data(#"{"name":"Good Morning"}"#.utf8))

        XCTAssertTrue(said.contains("Good Morning"), said)
        XCTAssertTrue(said.contains("background"), said)
        XCTAssertTrue(said.contains("will not come back"), said)
    }

    func testWhatIsOpenedIsWhatWasAskedFor() async throws {
        let opener = StubOpener()
        _ = try await tool(opener)
            .run(arguments: Data(#"{"name":"Good Morning","input":"now"}"#.utf8))

        let opened = await opener.opened
        XCTAssertEqual(opened.count, 1)
        XCTAssertEqual(opened.first?.scheme, "shortcuts")
    }

    func testNothingBeingAbleToRunShortcutsIsSaidRatherThanAssumed() async throws {
        // A phone without Shortcuts. Reporting success would leave the model
        // waiting for a foreground transition that never happens.
        let opener = StubOpener(succeeds: false)
        let said = try await tool(opener).run(arguments: Data(#"{"name":"Good Morning"}"#.utf8))

        XCTAssertTrue(said.contains("nothing on this device"), said)
        XCTAssertFalse(said.contains("background"), said)
    }

    func testAnEmptyNameIsSaidAndNothingIsOpened() async throws {
        let opener = StubOpener()
        let said = try await tool(opener).run(arguments: Data(#"{"name":"  "}"#.utf8))

        XCTAssertTrue(said.contains("no shortcut name"), said)
        let opened = await opener.opened
        XCTAssertTrue(opened.isEmpty, "opened something anyway")
    }
}
