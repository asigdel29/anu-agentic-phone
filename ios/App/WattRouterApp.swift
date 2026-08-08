// WattRouterApp.swift — the application, and the one thing it does at launch.
//
// History
//   2026-08-07  A. Sigdel  Created.
//   2026-08-07  A. Sigdel  Show the conversation once the core comes up.
//   2026-08-08  A. Sigdel  Wire the calendar tools in, over one permission.
//
// Contents
//   WattRouterApp  The entry point.
//
// What this target was for was proving that the routing core links and runs on a
// phone, which `CoreCheck` does and still does. It is now also where a turn
// happens, so the screen is the conversation whenever there is a core to run one
// with.
//
// The fallback is `CoreCheck` rather than an error, and that is deliberate.
// `Startup.router` fails when nobody has signed in, which is the ordinary state
// of a fresh install rather than a fault — and the screen that proves the core
// links is a more useful thing to show somebody in that state than a message
// about a credential they have not been asked for yet. Asking for it is item 6's
// business.

import SwiftUI
import WattRouter

@main
struct WattRouterApp: App {
    var body: some Scene {
        WindowGroup {
            if let driver = Self.driver {
                ConversationView(driver: driver)
            } else {
                CoreCheck()
            }
        }
    }

    /// A turn runner, or `nil` when the core will not come up.
    ///
    /// Built once at launch, because `Router` holds the decision cache and one
    /// per process is what keeps a session's tier from being dropped between
    /// turns. The same argument applies to `NeuralWattInference`, which owns the
    /// connection pool.
    @MainActor
    private static let driver: TurnDriver? = {
        guard let router = try? Startup.router(),
            let credential = Keychain.read(Startup.account),
            let workspace = try? Workspace(root: URL.documentsDirectory)
        else { return nil }

        // One `Permission` for the whole app, not one per tool. It holds the
        // record of what has already been asked, so a second instance means a
        // second prompt for one capability — the failure #193 exists to prevent,
        // reintroduced by building the thing that prevents it twice. A tool each
        // looks natural and is wrong.
        //
        // The two EventKit actors do *not* share a store, and that is deliberate
        // rather than an oversight. `EKEventStore` is not `Sendable`, so one
        // handed to both would be a value living in two isolation domains — the
        // exact thing actor isolation is being used here to rule out. The cost is
        // a second connection to the calendar database, which is cheaper than a
        // claim the compiler cannot check.
        let permission = Permission(EventKitAuthorizer())
        let calendars = EventKitCalendars()

        // `ClarifyTool` is still out. It asks the person a question and waits for
        // the answer, and nothing on this screen can give one — a model that
        // reached for it would stop, correctly, forever. It goes in with the
        // affordance that answers it, not before.
        let tools = ToolBox([
            ReadFileTool(workspace: workspace),
            WriteFileTool(workspace: workspace),
            SearchFilesTool(workspace: workspace),
            PatchTool(workspace: workspace),
            TodoTool(),
            ReadCalendarTool(calendars: calendars, permission: permission),
            AddEventTool(calendars: calendars, permission: permission),
        ])

        return TurnDriver(
            agent: Agent(
                router: router,
                inference: NeuralWattInference(apiKey: credential),
                tools: tools))
    }()
}
