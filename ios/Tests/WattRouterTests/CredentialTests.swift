// CredentialTests.swift — the whitespace, which is the whole of it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Hosted in the application, so there is a keychain to write to — see the note in
// project.yml. Each case forgets what it stored, because the keychain outlives
// the process and a leftover key would make the next run of this file pass for
// the wrong reason.

import Foundation
import XCTest

@testable import WattRouter

final class CredentialTests: XCTestCase {
    override func tearDown() {
        Credential.forget()
        super.tearDown()
    }

    func testAPastedKeyIsStoredWithoutItsTrailingNewline() throws {
        // A pasted key carries one more often than not. Stored with it, every
        // request is signed with a credential the provider does not recognise,
        // and the failure is a 401 with nothing to point at — the whitespace is
        // invisible everywhere a person would look for it.
        try Credential.store("  sk-not-a-real-key\n")

        XCTAssertEqual(Keychain.read(Startup.account), "sk-not-a-real-key")
    }

    func testNothingButWhitespaceIsRefused() {
        // Stored, it is indistinguishable from a key that is present and wrong,
        // and the app would go straight to a screen that cannot work.
        XCTAssertThrowsError(try Credential.store("   \n ")) { error in
            XCTAssertEqual(error as? Credential.Failure, .empty)
        }
        XCTAssertFalse(Credential.isStored)
    }

    func testStoringOneIsVisibleToTheThingThatChecks() throws {
        XCTAssertFalse(Credential.isStored, "something was left behind")
        try Credential.store("sk-not-a-real-key")
        XCTAssertTrue(Credential.isStored)

        Credential.forget()
        XCTAssertFalse(Credential.isStored)
    }

    func testStoringTwiceKeepsTheSecond() throws {
        // The Keychain refuses a duplicate add rather than replacing it, so a
        // person correcting a mistyped key would otherwise keep the mistake.
        try Credential.store("first")
        try Credential.store("second")

        XCTAssertEqual(Keychain.read(Startup.account), "second")
    }
}
