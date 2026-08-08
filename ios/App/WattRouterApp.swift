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

            // One `Permission` for the whole app, not one per tool. It holds the
            // record of what has already been asked, so a second instance means a
            // second prompt for one capability — the failure #193 exists to
            // prevent, reintroduced by building the thing that prevents it twice.
            //
            // The two EventKit actors deliberately do *not* share a store.
            // `EKEventStore` is not `Sendable`, so one handed to both would be a
            // value living in two isolation domains, which is what actor
            // isolation is being used here to rule out. The cost is a second
            // connection to the calendar database, which is cheaper than a claim
            // the compiler cannot check.
            // Two frameworks behind one Permission, routed by capability. A
            // second Permission would be a second prompt for one capability,
            // and a fall-through between authorizers would ask the wrong
            // framework on a phone with a device policy — see #262.
            let events = EventKitAuthorizer()
            let permission = Permission(
                ByCapability([
                    .calendar: events, .reminders: events, .contacts: CNContactsAuthorizer(),
                ]))
            let calendars = EventKitCalendars()
            // A third store, on the same reasoning as the second: EKEventStore
            // is not Sendable, and one shared between two actors is a value in
            // two isolation domains. Reminders and events are separate
            // capabilities anyway, so a person may grant one and refuse the
            // other, and each connection is scoped to what was granted.
            let reminders = EventKitReminders()

            // Stateless, so one instance and no lifetime to manage. The three git
            // tools go in together: a git that reads and cannot commit is a half
            // capability, and the workspace is very often not a repository at all
            // — which is a refusal at the first call rather than a failure here,
            // because a phone with no repository on it still has an app to run.
            let git = CoreRepository()

            // `ClarifyTool` is still out. It asks the person a question and waits
            // for the answer, and nothing on this screen can give one — a model
            // that reached for it would stop, correctly, forever. It goes in with
            // the affordance that answers it, not before.
            let tools = ToolBox([
                ReadFileTool(workspace: workspace),
                WriteFileTool(workspace: workspace),
                SearchFilesTool(workspace: workspace),
                PatchTool(workspace: workspace),
                TodoTool(),
                ReadCalendarTool(calendars: calendars, permission: permission),
                AddEventTool(calendars: calendars, permission: permission),
                ReadRemindersTool(reminders: reminders, permission: permission),
                AddReminderTool(reminders: reminders, permission: permission),
                FindContactTool(contacts: CNContacts(), permission: permission),
                RunShortcutTool(opener: UIKitOpener()),
                GitStatusTool(repository: git, workspace: workspace),
                GitAddTool(repository: git, workspace: workspace),
                GitCommitTool(repository: git, workspace: workspace),
            ])

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
