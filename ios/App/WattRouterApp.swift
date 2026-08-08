// WattRouterApp.swift — the application, and the one thing it does at launch.
//
// History
//   2026-08-07  A. Sigdel  Created.
//   2026-08-07  A. Sigdel  Show the conversation once the core comes up.
//   2026-08-08  A. Sigdel  Wire the calendar tools in, over one permission.
//   2026-08-08  A. Sigdel  Three outcomes rather than two, so a fresh install can
//                          be signed in rather than stuck on the fallback.
//
// Contents
//   WattRouterApp  The entry point.
//
// What this target was for was proving that the routing core links and runs on a
// phone, which `CoreCheck` does and still does. It is now also where a turn
// happens, and where a fresh install is asked for the one thing it needs.
//
// Three outcomes rather than two, because `Startup.router` already distinguishes
// them and the app was collapsing two of them into one screen. No credential is
// the ordinary state of a fresh install and asks for one. A credential the core
// refused is a configuration fault, and `CoreCheck` — which reaches the core
// directly — is the screen that says whether the core itself is alive. A router
// is the conversation.
//
// The driver is built by a view rather than by a static, and that is the change
// the sign-in forced. What it holds still wants to live as long as the process:
// `Router` owns the decision cache, which is what keeps a session's tier from
// being dropped between turns, and `NeuralWattInference` owns the connection
// pool. A `@State` that is assigned once and never reassigned keeps both, and a
// `static let` cannot be rebuilt the moment a credential exists.

import SwiftUI
import WattRouter

@main
struct WattRouterApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// Whichever of the three screens the app is currently entitled to.
@MainActor
struct RootView: View {
    /// Built once a credential exists, and kept for the life of the process.
    @State private var driver: TurnDriver?
    /// Whether the core refused a credential that is present, which is a fault
    /// rather than a fresh install.
    @State private var refused = false

    var body: some View {
        Group {
            if let driver {
                ConversationView(driver: driver)
            } else if refused || Credential.isStored {
                CoreCheck()
            } else {
                SignInView(stored: build)
            }
        }
        .onAppear(perform: build)
    }

    /// Try to bring a turn runner up, and record which way it failed.
    private func build() {
        guard driver == nil else { return }

        do {
            let router = try Startup.router()
            guard let credential = Keychain.read(Startup.account),
                let workspace = try? Workspace(root: URL.documentsDirectory)
            else {
                refused = true
                return
            }

            // Every tool, over one permission, assembled in the library so an
            // App Intent gets the same set rather than a copy of it — see
            // PhoneTools.swift for the invariants that assembly holds.
            let tools = PhoneTools.all(workspace: workspace)

            driver = TurnDriver(
                agent: Agent(
                    router: router,
                    inference: NeuralWattInference(apiKey: credential),
                    tools: tools))
        } catch Startup.Failure.noCredential {
            // A fresh install, which is not a fault. The sign-in stays up.
            refused = false
        } catch {
            refused = true
        }
    }
}
