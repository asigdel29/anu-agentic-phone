// WattRouterApp.swift — the application, and the one thing it does at launch.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   WattRouterApp  The entry point.
//
// Deliberately almost empty. What this target is for right now is proving that
// the routing core links and runs on a phone: the library builds on a Mac and
// cross-compiles in CI, and neither of those loads the static archive into a
// running iOS process. Everything else — the credential, the turn loop, the
// interface — arrives on top of a target that already launches.

import SwiftUI

@main
struct WattRouterApp: App {
    var body: some Scene {
        WindowGroup {
            CoreCheck()
        }
    }
}
