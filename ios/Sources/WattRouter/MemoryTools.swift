// MemoryTools.swift — remembering something, and asking what was remembered.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   RememberTool  Putting something in memory.
//   RecallTool    Asking what is in it.
//
// One file because the pair is the decision: the model decides what is worth
// remembering, rather than the turn loop ingesting everything.
//
// Automatic ingest is the obvious alternative and it is worse for a reason that
// is not about cost. A transcript is mostly the shape of a conversation — "yes",
// "do that", "thanks" — and a store full of that recalls the shape rather than
// the facts. A tool makes remembering a decision, and it is a decision the model
// is in a position to make.
//
// That is worth revisiting once there is a store with real history in it. It is
// not worth assuming quietly by wiring ingest into the loop.
//
// The rendering keeps the role. #296 kept `main` apart from `graphBridge` and
// `localNeighbor` so that a tool could tell evidence from context, and this is
// the tool — a turn dragged in across the entity graph, rendered like one that
// matched, is a fact the model states as though somebody said it.

import Foundation

/// Put something in memory.
public struct RememberTool: Tool {
    public let name = "remember"

    public let purpose = """
        Remember something for later turns and later conversations. Use it for \
        facts about the person and their world that would be tedious to be told \
        again — where things are, who people are, how they like things done. Not \
        for the conversation itself, which is already in front of you.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "text": {
              "type": "string",
              "description": "The fact, written as a sentence that will still make sense alone."
            }
          },
          "required": ["text"]
        }
        """

    private let memory: any Remembering
    private let session: String
    private let now: @Sendable () -> Date

    public init(
        memory: any Remembering, session: String,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.memory = memory
        self.session = session
        self.now = now
    }

    /// - Returns: what was remembered, said back.
    ///
    /// # Rely
    /// Nothing. There is no capability to obtain: this is the app's own store.
    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        let text = request.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return "there was nothing to remember, so nothing was stored"
        }

        _ = try memory.remember(text, speaker: "assistant", session: session, at: now())
        // Said back rather than "done". A model that cannot see what landed
        // writes it again next turn.
        return "remembered: \(text)"
    }

    private struct Request: Decodable {
        let text: String
    }
}

/// Ask what is in memory.
public struct RecallTool: Tool {
    /// Most pieces of evidence shown. Past a handful the model is reading a
    /// transcript rather than an answer.
    public static let limit = 8

    public let name = "recall"

    public let purpose = """
        Search everything remembered from earlier conversations. Ask it whenever \
        something depends on what the person told you before — it is the only \
        way to reach anything outside this conversation. Some results are marked \
        context: those are turns that sit near a match rather than answering it, \
        so do not state them as fact.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "What you are trying to find out."}
          },
          "required": ["query"]
        }
        """

    private let memory: any Remembering

    public init(memory: any Remembering) {
        self.memory = memory
    }

    /// - Returns: what was found, marked so context does not read as evidence.
    ///
    /// # Rely
    /// Nothing.
    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        let query = request.query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            return "no question was given, so nothing was looked up"
        }

        return Self.describe(try memory.recall(query, most: Self.limit))
    }

    /// One recollection, as lines.
    ///
    /// Static so the rendering — which is all of the decisions — is exercised
    /// without a store.
    static func describe(_ found: Recollection) -> String {
        guard !found.isEmpty else {
            // Distinguishable from a failure, and from a store that has never
            // been written to, which a model would otherwise keep querying.
            return "nothing remembered about that"
        }

        let clock = DateFormatter()
        clock.locale = Locale(identifier: "en_US_POSIX")
        clock.dateFormat = "yyyy-MM-dd"

        return found.evidence.prefix(limit).map { piece in
            let when = clock.string(from: Date(timeIntervalSince1970: TimeInterval(piece.ts)))
            // Marked, not omitted: context is worth showing and worth not
            // stating. Omitting it would lose the thread a match hangs on.
            let mark = piece.role == .main ? "" : " (context)"
            return "\(when)\(mark)  \(piece.text)"
        }
        .joined(separator: "\n")
    }

    private struct Request: Decodable {
        let query: String
    }
}
