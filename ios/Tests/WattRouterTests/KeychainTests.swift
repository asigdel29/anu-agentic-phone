// KeychainTests.swift — the one secret, stored and taken back.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// These touch the real Keychain, because a wrapper around `SecItem*` that is
// tested against a fake is a test of the fake. That makes them the only tests in
// this target that need a simulator rather than merely a Swift compiler — the
// same restriction the FFI tests already carry.
//
// Every account name is unique per test and removed afterwards, so a failing run
// leaves nothing behind for the next one to read.

import XCTest

@testable import WattRouter

final class KeychainTests: XCTestCase {
    /// An account no other test uses, cleaned up whatever happens.
    private func scratchAccount(_ name: String = #function) -> String {
        let account = "test-\(name)"
        addTeardownBlock { _ = Keychain.delete(account) }
        return account
    }

    func testWhatIsWrittenIsWhatIsRead() {
        let account = scratchAccount()
        XCTAssertTrue(Keychain.write("nw-secret-value", to: account))
        XCTAssertEqual(Keychain.read(account), "nw-secret-value")
    }

    func testAnAbsentAccountIsNilRatherThanEmpty() {
        // The distinction `Startup` leans on: nothing stored means nobody has
        // signed in, and an empty string would read as a credential.
        XCTAssertNil(Keychain.read("test-nothing-was-ever-written-here"))
    }

    func testWritingTwiceReplacesRatherThanDuplicates() {
        // A second sign-in. `SecItemAdd` over an existing item returns
        // `errSecDuplicateItem`, so a wrapper that only adds would silently keep
        // the old credential and report success.
        let account = scratchAccount()
        XCTAssertTrue(Keychain.write("first", to: account))
        XCTAssertTrue(Keychain.write("second", to: account))
        XCTAssertEqual(Keychain.read(account), "second")
    }

    func testDeletingSomethingAbsentIsNotAFailure() {
        // Signing out twice, or signing out having never signed in.
        XCTAssertTrue(Keychain.delete("test-nothing-was-ever-written-here"))
    }

    func testDeletingTakesItBack() {
        let account = scratchAccount()
        Keychain.write("transient", to: account)
        XCTAssertTrue(Keychain.delete(account))
        XCTAssertNil(Keychain.read(account))
    }

    func testAValueSurvivesCharactersThatAreNotASCII() {
        // The credential is opaque to this code: stored as UTF-8 bytes and read
        // back the same way, with nothing in between inspecting it. The trailing
        // space is the realistic case — it arrives from a paste, and whatever
        // eventually offers a sign-in field is where trimming belongs, not here.
        let account = scratchAccount()
        let awkward = "key-🔑-with-\u{00e9}-and a trailing space "
        XCTAssertTrue(Keychain.write(awkward, to: account))
        XCTAssertEqual(Keychain.read(account), awkward)
    }

    @MainActor
    func testTheCoreIsNotAskedWithoutACredential() throws {
        // `Router()` cannot tell a missing credential from any other rejection,
        // so `Startup` has to decide before asking. Nothing is stored under the
        // real account in a fresh simulator, which is the state this checks.
        Keychain.delete(Startup.account)
        XCTAssertThrowsError(try Startup.router()) { error in
            XCTAssertEqual(error as? Startup.Failure, .noCredential)
        }
    }
}
