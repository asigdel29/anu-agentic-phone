// ToolCallAssembly.swift — putting a tool call back together.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   ToolCallAssembly  Fragments in, whole calls out.
//
// The reader is a pure function of one line, which is what makes it testable
// without a network. Assembling a call is not: the id and name arrive on the
// first fragment for an index and the arguments arrive a few characters at a
// time across many lines. The state lives here instead, in the one type whose
// job it is.
//
// Nothing is emitted as it arrives. `Inference` promises that a yielded event
// commits the chain — a second model cannot un-deliver one — and a half-built
// call is a commitment to something that does not exist yet. Nothing on the wire
// marks the end of an individual call either; only `finish_reason` marks the end
// of all of them, so the caller decides when to ask.

import Foundation

/// Fragments in, whole calls out.
///
/// Internal, like the reader it consumes: this is how the client puts a stream
/// back together, and nothing outside the module has a fragment to give it.
struct ToolCallAssembly: Sendable {
    /// Keyed by the index the provider used, which is the only thing tying a
    /// fragment to the call it continues.
    private var byIndex: [Int: Partial] = [:]

    init() {}

    /// Whether anything has been collected. A caller flushing on several signals
    /// uses this to avoid asking twice.
    var isEmpty: Bool { byIndex.isEmpty }

    /// Take in a fragment.
    ///
    /// The id and name are written once and never overwritten with nothing: a
    /// later fragment for the same index carries neither, and letting it blank
    /// them is how a call loses the name it was going to be dispatched by.
    mutating func add(_ fragment: ServerSentEvent.ToolCallFragment) {
        var partial = byIndex[fragment.index] ?? Partial()
        partial.id = fragment.id ?? partial.id
        partial.name = fragment.name ?? partial.name
        partial.arguments += fragment.arguments
        byIndex[fragment.index] = partial
    }

    /// Everything assembled, in the provider's index order, and forget it.
    ///
    /// - Returns: the calls. Arguments are handed over exactly as they arrived,
    ///   truncation and all. A call cut off by `finish_reason: length` has
    ///   arguments that will not parse, and `ToolBox` already answers that with
    ///   the decoding fault and the schema — which is what the model needs in
    ///   order to try again. Repairing or dropping them here would be a second,
    ///   worse copy of a policy that already exists.
    mutating func take() -> [ToolCall] {
        defer { byIndex.removeAll() }
        return byIndex.keys.sorted().map { index in
            let partial = byIndex[index] ?? Partial()
            // An empty name reaches `ToolBox`, which reports the unknown tool and
            // lists the real ones. Dropping the call instead would leave the model
            // waiting on a result nothing was going to produce.
            return ToolCall(
                id: partial.id ?? "", name: partial.name ?? "", arguments: partial.arguments)
        }
    }

    /// One call, part-built.
    private struct Partial {
        var id: String?
        var name: String?
        var arguments = ""
    }
}
