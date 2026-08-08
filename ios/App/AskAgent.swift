// AskAgent.swift — the system's way in.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   AskAgent            One turn, as an intent.
//   WattRouterShortcuts The phrase Siri listens for.
//
// #137 ranks App Intents first of everything an iOS app may do, and the reason
// is not the tool count: this is the only route by which the agent can be
// started by the system rather than by somebody opening the app.
//
// Thin on purpose, and the thinness is the design. Nothing in a test bundle can
// run an intent, so everything with a decision in it lives in the library where
// it is reachable: `PhoneTools.all` builds the tools, `Startup.router` brings the
// core up, and `Answer.from` folds a turn's events into the one sentence this has
// somewhere to put. What is left here is the declaration.
//
// `openAppWhenRun` is deliberately not set. The point of the item is that the
// system can start the agent without the app being opened, and an intent that
// foregrounds the app to answer is a slower way of tapping the icon.
//
// One turn, and no continuity with the conversation on screen. Threading an
// intent into that transcript is a real design question rather than an omission:
// `Conversation` is main-actor state owned by a view, and this runs somewhere
// else.

import AppIntents
import Foundation
import WattRouter

/// Ask the agent something, and hear the answer.
struct AskAgent: AppIntent {
    static let title: LocalizedStringResource = "Ask the agent"

    static let description = IntentDescription(
        """
        Ask a question or give an instruction. The agent can read your calendar \
        and reminders, look somebody up, and work on files in the app.
        """)

    @Parameter(title: "What to ask", requestValueDialog: "What would you like to ask?")
    var prompt: String

    static var parameterSummary: some ParameterSummary {
        Summary("Ask the agent \(\.$prompt)")
    }

    /// Run one turn and return what it said.
    ///
    /// - Throws: `Startup.Failure.noCredential` when nobody has signed in, which
    ///   an intent cannot fix — there is no screen here to sign in on, and
    ///   failing plainly is better than a turn that cannot be served.
    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let router = try Startup.router()
        guard let credential = Keychain.read(Startup.account),
            let workspace = try? Workspace(root: URL.documentsDirectory)
        else {
            throw Startup.Failure.noCredential
        }

        let agent = Agent(
            router: router,
            inference: NeuralWattInference(apiKey: credential),
            tools: PhoneTools.all(workspace: workspace))

        let said = try await Answer.from(await agent.send(prompt))
        // Both: the value is what a shortcut chains onwards, the dialog is what
        // Siri speaks. Returning only the value leaves Siri silent.
        return .result(value: said, dialog: IntentDialog(stringLiteral: said))
    }
}

/// The phrase Siri listens for.
struct WattRouterShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskAgent(),
            // Every phrase must contain the app name, which the system
            // substitutes — a phrase without one is dropped silently at build
            // time rather than refused.
            phrases: [
                "Ask \(.applicationName)",
                "Ask \(.applicationName) a question",
            ],
            shortTitle: "Ask the agent",
            systemImageName: "bubble.left.and.text.bubble.right")
    }
}
