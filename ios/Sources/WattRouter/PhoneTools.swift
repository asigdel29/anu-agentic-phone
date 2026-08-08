// PhoneTools.swift — every tool a turn on this phone may call.
//
// History
//   2026-08-08  A. Sigdel  Created, from RootView.build().
//
// Contents
//   PhoneTools  The assembly, in one place.
//
// This lived inside a SwiftUI view's private method, which was fine while one
// screen was the only way in. App Intents is a second way in and needs the same
// assembly, and a second call site copying fourteen of fifteen lines would be
// correct the day it was written and wrong the first time either list changed.
//
// What is being assembled is a set of invariants rather than a list, and each
// was a comment in the view:
//
// One `Permission` for the whole app. It holds the record of what has already
// been asked, so a second instance is a second prompt for one capability — the
// failure #193 exists to prevent, and building this twice is exactly how it
// comes back.
//
// One authorizer per framework, routed by capability rather than tried in turn.
// A fall-through cannot tell "not mine" from "a device policy turned this off",
// and would ask about the wrong framework on somebody else's phone. See #262.
//
// Three EventKit-shaped actors that deliberately do not share a store.
// `EKEventStore` is not `Sendable`, so one handed to two actors is a value in
// two isolation domains — which is the thing the isolation is being used to rule
// out. The cost is a second connection to the calendar database, which is
// cheaper than a claim the compiler cannot check.
//
// And `ClarifyTool` is deliberately absent. It asks the person a question and
// waits for the answer, and nothing that runs a turn today can give one — a
// model that reached for it would stop, correctly, forever. It goes in with the
// affordance that answers it.

import Foundation

/// Every tool a turn on this phone may call.
public enum PhoneTools {
    /// Build them, over one permission.
    ///
    /// - Parameter workspace: which files the file tools and git may touch.
    /// - Returns: the tools, in the order the model is shown them.
    ///
    /// # Rely
    /// Called once per process. Calling it twice yields a second `Permission`,
    /// and a capability may then be prompted for twice.
    /// Where the memory store lives.
    ///
    /// Application Support, deliberately not the workspace. `Workspace` is what
    /// the file tools may touch, and a database inside it is one `read_file`
    /// away from the model reading its own memory as bytes and one `write_file`
    /// away from corrupting it.
    public static var store: URL {
        URL.applicationSupportDirectory.appending(path: "memory/memory.db")
    }

    public static func all(workspace: Workspace, session: String = "phone") -> ToolBox {
        let events = EventKitAuthorizer()
        let permission = Permission(
            ByCapability([
                .calendar: events, .reminders: events,
                .contacts: CNContactsAuthorizer(), .location: CLLocationAuthorizer(),
            ]))

        let calendars = EventKitCalendars()
        let reminders = EventKitReminders()
        // Stateless, so one instance and no lifetime to manage. A workspace that
        // is not a repository is a refusal at the first call rather than a
        // failure here: a phone with no repository on it still has an app to run.
        let git = CoreRepository()
        // A store that will not open leaves the app running without memory
        // rather than not running. The two tools go in only when there is one
        // behind them, because a tool that always refuses is worse than a tool
        // the model was never offered.
        let memory = CoreMemory(path: store)

        return ToolBox([
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
            WhereAmITool(located: CLLocated(), permission: permission),
            GitStatusTool(repository: git, workspace: workspace),
            GitAddTool(repository: git, workspace: workspace),
            GitCommitTool(repository: git, workspace: workspace),
        ] + (memory.map { [RememberTool(memory: $0, session: session), RecallTool(memory: $0)] } ?? []))
    }
}
