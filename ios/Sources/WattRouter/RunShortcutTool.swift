// RunShortcutTool.swift — handing a turn to a shortcut.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Opener           The seam to whatever opens a URL.
//   RunShortcutTool  Starting a shortcut, as a tool.
//
// This tool behaves unlike every other one here, and the difference is the
// design rather than a limitation to work around. `shortcuts://run-shortcut`
// foregrounds the Shortcuts app, so the agent goes to the background mid-turn —
// the state #186 and #228 already test for, arriving now on purpose.
//
// Three things follow, each of which is a way to get this wrong.
//
// The result is not coming back. A tool answering "ran the shortcut" invites the
// next turn to reason about output that does not exist, so what this says is
// that it *started* one and that the app is going away.
//
// It should be the last call in a round. A model that calls this and then calls
// read_calendar has queued something that runs after the app is gone. Nothing
// here can enforce that, so it is said in the purpose the model reads, which is
// the only place it can be said.
//
// And a result is possible later without being faked now. x-callback-url exists
// — shortcuts://x-callback-url/run-shortcut?…&x-success=… — and needs the app to
// register a scheme and resume a turn from a cold launch. That is its own change.
// What must not happen meanwhile is this inventing a plausible answer.
//
// There is no permission and nothing to check. Opening a URL needs no capability,
// and `canOpenURL` answers about the scheme rather than the name — so a misspelt
// shortcut foregrounds Shortcuts and fails there. The answer names what was
// tried, which is the only thing that helps afterwards.

import Foundation

/// The seam to whatever opens a URL.
///
/// A protocol rather than `UIApplication.shared.open` at the call site, so this
/// is testable without the test leaving the app — and so a tool cannot be given
/// a way to open arbitrary URLs by accident.
public protocol Opener: Sendable {
    /// Hand the URL to the system.
    ///
    /// - Returns: whether anything could open it. `false` is the answer for a
    ///   scheme nothing handles, which on a phone without Shortcuts is the
    ///   ordinary case rather than a fault.
    func open(_ url: URL) async -> Bool
}

/// Start a shortcut.
public struct RunShortcutTool: Tool {
    public let name = "run_shortcut"

    public let purpose = """
        Run one of the person's Shortcuts by name. This opens the Shortcuts app, \
        which puts this conversation into the background — so call it last in a \
        turn, and do not expect the shortcut's output to come back to you. Say \
        what you are about to run before running it.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "The shortcut's name, exactly as it appears in Shortcuts."
            },
            "input": {
              "type": "string",
              "description": "Text to pass in, if the shortcut takes input."
            }
          },
          "required": ["name"]
        }
        """

    private let opener: any Opener

    public init(opener: any Opener) {
        self.opener = opener
    }

    /// The URL that runs a shortcut by name.
    ///
    /// Built with `URLComponents` rather than by concatenation, which is the
    /// whole reason this is a function worth testing: shortcut names contain
    /// spaces and ampersands as a matter of course, and "Lights & Locks"
    /// concatenated into a query truncates at the ampersand and runs "Lights".
    ///
    /// - Returns: `nil` IF the name is empty, which would open Shortcuts with no
    ///   instruction.
    static func url(name: String, input: String?) -> URL? {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }

        var components = URLComponents()
        components.scheme = "shortcuts"
        components.host = "run-shortcut"
        components.queryItems =
            [URLQueryItem(name: "name", value: name)]
            + (input.map { [URLQueryItem(name: "input", value: "text"), URLQueryItem(name: "text", value: $0)] } ?? [])
        return components.url
    }

    /// - Returns: what was started, and that the app is going away.
    ///
    /// # Rely
    /// Nothing. There is no capability to obtain.
    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        guard let url = Self.url(name: request.name, input: request.input) else {
            return "no shortcut name was given, so nothing was run"
        }
        guard await opener.open(url) else {
            return """
                nothing on this device can run shortcuts, so "\(request.name)" \
                was not started
                """
        }

        // What happened, and what is about to happen. A model told only "done"
        // writes a next message the person will not see for a while.
        return """
            started the shortcut "\(request.name)". The Shortcuts app is now in \
            front and this conversation is in the background, so its result will \
            not come back here
            """
    }

    private struct Request: Decodable {
        let name: String
        let input: String?
    }
}
