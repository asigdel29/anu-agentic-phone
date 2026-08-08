// CLLocatedTests.swift — the status mapping, which is all of this a simulator
// reaches without granting something.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Nothing here prompts. A test cannot answer a system dialog, so "a granted
// manager produces a fix" is not testable and is not pretended to be.
//
// What is testable is the mapping, and the case in it that would silently refuse
// access somebody deliberately gave.

import CoreLocation
import Foundation
import XCTest

@testable import WattRouter

final class CLLocatedTests: XCTestCase {
    func testAlwaysIsMoreThanWasAskedForRatherThanLess() {
        // This app only ever requests whenInUse, and can still find itself
        // holding always — somebody granted it in Settings. Reading it as
        // anything but granted refuses access they chose to give.
        XCTAssertEqual(CLLocationAuthorizer.state(from: .authorizedAlways), .granted)
    }

    func testEveryStatusMeansExactlyOneThing() {
        XCTAssertEqual(CLLocationAuthorizer.state(from: .authorizedWhenInUse), .granted)
        XCTAssertEqual(CLLocationAuthorizer.state(from: .denied), .refused)
        // Restricted is not the person's choice and they cannot change it, which
        // is a different sentence from "you said no".
        XCTAssertEqual(CLLocationAuthorizer.state(from: .restricted), .unavailable)
        // The ordinary first call, and the only state that is worth prompting in.
        XCTAssertEqual(CLLocationAuthorizer.state(from: .notDetermined), .unasked)
    }

    func testTheAuthorizerOwnsLocationAndNothingElse() async {
        // Wired behind ByCapability, which already refuses to route what nobody
        // owns. This is the same claim made by the type, so it holds however it
        // is wired.
        let authorizer = CLLocationAuthorizer()

        for capability in [Capability.calendar, .reminders, .contacts] {
            let state = await authorizer.state(of: capability)
            XCTAssertEqual(state, .unavailable, "\(capability)")
        }
    }

    func testAnAnsweredCapabilityIsNotPromptedAgain() async {
        // A simulator that has never been asked reports notDetermined, so this
        // asserts the branch that matters on a device: requesting something
        // already decided shows nothing, the delegate never fires, and a
        // continuation there would hang the turn rather than answer it.
        let authorizer = CLLocationAuthorizer()
        let before = await authorizer.state(of: .location)

        guard before != .unasked else {
            // Nothing to assert on a fresh simulator, and prompting would hang
            // the suite waiting for a dialog nobody can answer.
            return
        }
        let after = await authorizer.request(.location)
        XCTAssertEqual(after, before)
    }

    func testTheFailureSaysSomethingActionable() {
        // Reaches the model through ToolBox as localizedDescription, and "the
        // operation could not be completed" is the default it must not be.
        let said = CLLocated.Failure.noFix.errorDescription ?? ""

        XCTAssertTrue(said.contains("fix"), said)
        XCTAssertTrue(said.contains("Indoors"), said)
    }
}
