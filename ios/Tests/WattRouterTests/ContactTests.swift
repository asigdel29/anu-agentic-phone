// ContactTests.swift — the one question a Contact answers on its own.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Small, because the type is. What is worth asserting is that "found somebody"
// and "found a way to reach somebody" are separate questions — a tool that
// conflates them tells the model to keep looking for a person it has already
// found, and the address book is full of entries that are one and not the other.

import Foundation
import XCTest

@testable import WattRouter

final class ContactTests: XCTestCase {
    func testSomebodyWithNoNumberAndNoEmailIsFoundButNotReachable() {
        // A real entry rather than a broken one: companies, and half-finished
        // ones somebody typed a name into and never came back to.
        let contact = Contact(name: "The Plumber")

        XCTAssertFalse(contact.isReachable)
        XCTAssertEqual(contact.name, "The Plumber")
    }

    func testEitherOneIsEnoughToBeReachable() {
        let byPhone = Contact(name: "Dave", numbers: [.init(label: "mobile", value: "07700")])
        let byEmail = Contact(name: "Dave", emails: [.init(label: "work", value: "d@example.com")])

        XCTAssertTrue(byPhone.isReachable)
        XCTAssertTrue(byEmail.isReachable)
    }

    func testALabelMayBeEmptyBecauseTheAddressBookAllowsIt() {
        // Not normalised to "other" here. What a label says is the address book's
        // to report, and inventing one puts a word in front of the model that
        // nobody chose.
        let contact = Contact(name: "Dave", numbers: [.init(label: "", value: "07700")])

        XCTAssertTrue(contact.isReachable)
        XCTAssertEqual(contact.numbers.first?.label, "")
    }
}
