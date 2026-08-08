// Remembering.swift — what the store remembered, and the seam to whatever holds it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Remembered    One turn the store thought relevant.
//   Recollection  A route, and the evidence behind it.
//   MemoryError   Why a recall or a write could not be done.
//   Remembering   The seam to whatever holds the store.
//
// No FFI here, as in Repository.swift, and the reason is sharper: a Swift test
// cannot build a memory store either. There is no way to make one except through
// the ABI, so a test that did would be testing the ABI again rather than the
// decoding. Held apart, the decoding is exercised against the literals
// router/src/ffi_memory.rs is asserted to emit.
//
// The role is the part a tool must not flatten. `main` is a turn that matched.
// The other two are turns dragged in beside it — a neighbour in the same
// conversation, or a hop across the entity graph. Rendered identically, a model
// reads a bridge turn as direct evidence and answers with a fact nobody stated.
//
// So the role is closed, and one this build does not know fails rather than
// arriving as one it does. Both halves ship in a single binary, so a vocabulary
// they disagree about is a build mistake and not a runtime condition.

import Foundation

/// One turn the store thought relevant.
public struct Remembered: Equatable, Sendable, Decodable {
    /// Why this turn is here.
    ///
    /// Closed on purpose. The difference between a turn that matched and a turn
    /// standing next to one is the difference between evidence and context, and
    /// a sixth role read as one of these is the wrong one of those two.
    public enum Role: String, Sendable, Decodable {
        /// It matched the question.
        case main = "Main"
        /// It was reached across the entity graph from something that matched.
        case graphBridge = "GraphBridge"
        /// It sits beside a match in the same conversation.
        case localNeighbor = "LocalNeighbor"
    }

    /// What was said. The answer is in here; everything else is about trusting it.
    public let text: String
    /// Who said it.
    public let speaker: String
    /// When, as a Unix timestamp in seconds.
    public let ts: Int64
    /// How well it matched, on the store's own scale — comparable within one
    /// recollection and meaningless across two.
    public let score: Double
    public let role: Role

    private enum CodingKeys: String, CodingKey {
        case text, speaker, ts, score, role
    }
}

/// A route, and the evidence behind it.
public struct Recollection: Equatable, Sendable, Decodable {
    /// How the store found this.
    ///
    /// Kept rather than dropped: it is the only signal about *how* an answer was
    /// reached, and without it a tool cannot say why the evidence looks as it
    /// does. Open, unlike `Role` — a new route changes how an answer was found
    /// and not what it means, so an unknown one is worth showing rather than
    /// failing over.
    public let route: String
    /// What the store found, best first.
    public let evidence: [Remembered]

    /// Whether the store had nothing. Not an error: a fresh install has never
    /// been told anything, and that is the ordinary state for a while.
    public var isEmpty: Bool { evidence.isEmpty }
}

/// Why a recall or a write could not be done.
public enum MemoryError: LocalizedError, Equatable, Sendable, CoreFailure {
    /// What the store refused, in its own words.
    case refused(String)
    /// The store would not open. Nothing above this can fix it.
    case unopened
    /// The call produced no answer at all.
    case unanswered
    /// An answer arrived and could not be read, which means the two halves were
    /// built from different sources.
    case unreadable(String)

    public var errorDescription: String? {
        switch self {
        case .refused(let why): why
        case .unopened: "the memory store could not be opened, so nothing is remembered yet"
        case .unanswered: "the routing core gave no answer to a memory call"
        case .unreadable(let detail): "the memory answer could not be read: \(detail)"
        }
    }
}

/// The seam to whatever holds the store.
///
/// Synchronous, as `Repository` is: this is disk work in the app's own sandbox,
/// and an `async` that never suspends says something untrue about where it
/// happens.
public protocol Remembering: Sendable {
    /// Put a turn in.
    ///
    /// - Returns: the turn's id, which nothing above needs and which is returned
    ///   because the store has one and swallowing it would make a write
    ///   indistinguishable from a no-op.
    func remember(_ text: String, speaker: String, session: String, at: Date) throws(MemoryError)
        -> Int64

    /// Ask it something.
    ///
    /// - Parameter most: how much evidence to bring back, WHERE zero takes the
    ///   store's own default. Zero meaning none would answer every question with
    ///   silence, which reads as an empty store rather than as a bad argument.
    func recall(_ query: String, most: Int) throws(MemoryError) -> Recollection
}
