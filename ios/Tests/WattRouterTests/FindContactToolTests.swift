// FindContactToolTests.swift — what a turn is told about somebody.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Each case is one of the three answers and the wrong version of it. The
// expensive failure here is not an exception — it is a model picking the first
// of fifty Daves and sending a message to somebody it should not have.

import Foundation
import XCTest

@testable import WattRouter

/// An address book that remembers what it was searched for.
actor StubContacts: Contacts {
    private(set) var searches: [String] = []
    private let answer: [Contact]

    init(_ answer: [Contact] = []) {
        self.answer = answer
    }

    func matching(_ name: String) async throws -> [Contact] {
        searches.append(name)
        return answer
    }
}

final class FindContactToolTests: XCTestCase {
    private func tool(
        _ contacts: StubContacts, says: PermissionState = .granted
    ) -> FindContactTool {
        FindContactTool(contacts: contacts, permission: Permission(FixedAuthorizer(says: says)))
    }

    private func person(_ name: String) -> Contact {
        Contact(name: name, numbers: [.init(label: "mobile", value: "07700 900\(name.count)")])
    }

    func testNoMatchSaysWhatItSearchedFor() async throws {
        // "No results" sends a model to search the same word again. The term
        // sends it to try another spelling.
        let said = try await tool(StubContacts()).run(arguments: Data(#"{"name":"Dave"}"#.utf8))

        XCTAssertTrue(said.contains("Dave"), said)
        XCTAssertTrue(said.contains("spelling") || said.contains("surname"), said)
    }

    func testOneMatchGivesTheWayToReachThemRatherThanTheName() async throws {
        // The name is what the model already had. The number is the question.
        let said = try await tool(StubContacts([person("Dave")]))
            .run(arguments: Data(#"{"name":"Dave"}"#.utf8))

        XCTAssertTrue(said.contains("07700"), said)
        XCTAssertTrue(said.contains("mobile"), said)
    }

    func testSomebodyWithNoWayToReachThemIsFoundRatherThanMissed() async throws {
        // The entry exists and searching again will not improve it, so the model
        // has to be told to stop rather than left to try once more.
        let said = try await tool(StubContacts([Contact(name: "The Plumber")]))
            .run(arguments: Data(#"{"name":"plumber"}"#.utf8))

        XCTAssertTrue(said.contains("The Plumber"), said)
        XCTAssertTrue(said.contains("no phone number or email"), said)
    }

    func testAHandfulOfMatchesAreAllListed() async throws {
        let daves = (1...3).map { person("Dave \($0)") }
        let said = try await tool(StubContacts(daves)).run(arguments: Data(#"{"name":"Dave"}"#.utf8))

        for dave in daves {
            XCTAssertTrue(said.contains(dave.name), "\(dave.name) missing from: \(said)")
        }
    }

    func testTooManyMatchesAskForASurnameRatherThanShowingSome() async throws {
        // Showing the first eight of fifty invites the model to choose out of an
        // arbitrary slice, which is the same mistake as choosing out of fifty and
        // harder to notice afterwards.
        let crowd = (1...FindContactTool.limit + 1).map { person("Dave \($0)") }
        let said = try await tool(StubContacts(crowd)).run(arguments: Data(#"{"name":"Dave"}"#.utf8))

        XCTAssertTrue(said.contains("\(crowd.count) contacts"), said)
        XCTAssertTrue(said.contains("surname"), said)
        XCTAssertFalse(said.contains("07700"), "listed some of them anyway: \(said)")
    }

    func testTheSearchTermReachesTheAddressBookAsWritten() async throws {
        let contacts = StubContacts([person("Dave")])
        _ = try await tool(contacts).run(arguments: Data(#"{"name":"  Dave  "}"#.utf8))

        // Trimmed, and nothing else. Anything cleverer is guessing at a person.
        let searches = await contacts.searches
        XCTAssertEqual(searches, ["Dave"])
    }

    func testAnEmptySearchIsRefusedBeforeThePermissionIsSpent() async throws {
        // An empty name matches everybody, and spending the one prompt on it
        // spends it for good.
        let contacts = StubContacts()
        let said = try await tool(contacts, says: .unasked)
            .run(arguments: Data(#"{"name":"   "}"#.utf8))

        XCTAssertTrue(said.contains("no name was given"), said)
        let searches = await contacts.searches
        XCTAssertTrue(searches.isEmpty)
    }

    func testARefusalReachesTheModelAsSomethingItCanActOn() async throws {
        let box = ToolBox([tool(StubContacts(), says: .refused)])
        let result = try await box.run(
            ToolCall(id: "c1", name: "find_contact", arguments: #"{"name":"Dave"}"#))

        XCTAssertEqual(result.isError, true)
        // PermissionError names the Settings screen, which is the actionable part.
        XCTAssertTrue(result.content.contains("Contacts"), result.content)
    }
}
