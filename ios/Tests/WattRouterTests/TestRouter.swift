// TestRouter.swift — building the core the way a test needs it.
//
// History
//   2026-08-06  A. Sigdel  Created, from the copy each test file kept.
//
// Contents
//   makeRouter  A router built as an app would build one.
//
// One place, because what the core needs to build is `Config::from_env`'s to
// decide and it can grow a second variable. Three copies meant three files
// failing for one cause, and the Rust suite already answers this with a single
// `with_router` fixture rather than a copy per test.

import Foundation
import XCTest

@testable import WattRouter

extension XCTestCase {
    /// Build a router as an app would.
    ///
    /// The core reads configuration from the environment, as the server does; an
    /// app would supply this from its own storage. One per test method rather
    /// than one shared: `decide` mutates the session cache, so a shared router
    /// would carry stickiness from one test into the next.
    func makeRouter() throws -> Router {
        setenv("NEURALWATT_API_KEY", "ios-test", 1)
        return try XCTUnwrap(Router(), "the core builds without a head")
    }
}
