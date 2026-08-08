// CNContactsTests.swift — the mappings, which is all of this a simulator reaches.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Nothing here grants anything, as in EventKitCalendarsTests: a test cannot
// answer a system prompt, so "a granted store returns people" is not testable
// and is not pretended to be.
//
// What is testable is the two decisions the file makes on its own — what each
// authorization status means, and what the framework's label constants turn
// into. Both are static over framework values rather than methods over a store.

import Contacts
import Foundation
import XCTest

@testable import WattRouter

final class CNContactsTests: XCTestCase {
    func testLimitedAccessIsGrantedRatherThanRefused() throws {
        // iOS 18 added it: the person picked some contacts and not others. A
        // search returns those and no error, so refusing it would be this app
        // declining to look at what somebody deliberately shared.
        //
        // Gated because the package's floor is 17, where the case does not
        // exist. The switch in CNContacts.swift handles it either way — a
        // pattern naming a case the running system lacks simply never matches.
        guard #available(iOS 18.0, *) else {
            throw XCTSkip("limited access arrived in iOS 18")
        }
        XCTAssertEqual(CNContactsAuthorizer.state(from: .limited), .granted)
    }

    func testEveryStatusMeansExactlyOneThing() {
        XCTAssertEqual(CNContactsAuthorizer.state(from: .authorized), .granted)
        XCTAssertEqual(CNContactsAuthorizer.state(from: .denied), .refused)
        // Restricted is not the person's choice and they cannot change it, which
        // is a different sentence from "you said no".
        XCTAssertEqual(CNContactsAuthorizer.state(from: .restricted), .unavailable)
        XCTAssertEqual(CNContactsAuthorizer.state(from: .notDetermined), .unasked)
    }

    func testTheAuthorizerOwnsContactsAndNothingElse() {
        // It is wired behind ByCapability, which already refuses to route what
        // nobody owns — this is the second half of that claim, made by the type
        // itself so it is true however it is wired.
        let authorizer = CNContactsAuthorizer()

        let capabilities: [Capability] = [.calendar, .reminders, .location]
        for capability in capabilities {
            let asyncState = expectation(description: "\(capability)")
            Task {
                let state = await authorizer.state(of: capability)
                XCTAssertEqual(state, .unavailable, "\(capability)")
                asyncState.fulfill()
            }
            wait(for: [asyncState], timeout: 2)
        }
    }

    func testAFrameworkLabelBecomesAWordSomebodyWouldSay() {
        // The framework stores these as `_$!<Mobile>!$_`. Handed to a model, that
        // is a token it repeats back at somebody.
        let said = CNContacts.label(CNLabelPhoneNumberMobile)

        XCTAssertFalse(said.contains("_$!"), said)
        XCTAssertFalse(said.isEmpty)
    }

    func testAnAbsentLabelStaysAbsent() {
        // Not normalised to "other". What a label says is the address book's to
        // report, and inventing one puts a word nobody chose in front of the
        // model.
        XCTAssertEqual(CNContacts.label(nil), "")
        XCTAssertEqual(CNContacts.label(""), "")
    }
}
