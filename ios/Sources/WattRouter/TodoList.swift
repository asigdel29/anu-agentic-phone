// TodoList.swift — the plan the model writes down and re-reads.
//
// History
//   2026-08-07  A. Sigdel  Created, from tools/todo_tool.py.
//
// Contents
//   TodoStatus  Where a task has got to.
//   Todo        One task, complete.
//   TodoEdit    One task as the model wrote it, which may be partial.
//   TodoList    The list, and the rules that keep it small.
//
// The caps are the part worth explaining, because they look arbitrary and are
// not. This list is re-injected into the conversation after every context
// compression, so an unbounded one defeats the compaction it is riding through.
// Four thousand characters an item and two hundred and fifty-six items, keeping
// the head, because list order is priority.
//
// `TodoEdit` exists because merging updates only the fields the model actually
// sent, and one type where everything is optional cannot say which those were.
// Two shapes put that distinction in the signature.
//
// One divergence from the Python, deliberate. It coerces an unrecognised status
// to `pending`; this rejects it. A model that wrote "done" and is told nothing
// believes an item is finished while the list says otherwise, and it plans from
// that belief. A rejected call costs one turn and names the valid values. The
// other coercions are kept — an empty content becoming a placeholder, an empty id
// becoming `?` — because neither is a lie about state.

import Foundation

/// Where a task has got to.
public enum TodoStatus: String, Codable, CaseIterable, Sendable {
    case pending
    case inProgress = "in_progress"
    case completed
    /// Abandoned on purpose, which is not the same as finished. Keeping the two
    /// apart is what lets a summary say a plan was cut short rather than done.
    case cancelled
}

/// One task on the list.
public struct Todo: Codable, Equatable, Sendable {
    public let id: String
    public let content: String
    public let status: TodoStatus
}

/// One task as the model wrote it. Absent fields mean "leave this alone" when
/// merging, and take a default when replacing.
public struct TodoEdit: Codable, Equatable, Sendable {
    public let id: String
    public let content: String?
    public let status: TodoStatus?

    public init(id: String, content: String? = nil, status: TodoStatus? = nil) {
        self.id = id
        self.content = content
        self.status = status
    }
}

/// The list the model keeps.
public struct TodoList: Equatable, Sendable {
    /// Longest an item may be, in characters. Counted in characters rather than
    /// bytes so a cut never lands inside an emoji.
    public static let maxContent = 4000
    /// Most items kept. The head survives, because order is priority.
    public static let maxItems = 256
    static let truncationMarker = "… [truncated]"
    static let placeholder = "(no description)"
    /// What an item with no id becomes. They then collapse into one another,
    /// which is honest: there is nothing to tell them apart by.
    static let anonymous = "?"

    /// In priority order, oldest first within it.
    public private(set) var items: [Todo] = []

    public init(_ items: [Todo] = []) {
        self.items = Array(items.prefix(Self.maxItems))
    }

    /// Apply what the model wrote.
    ///
    /// - Parameters:
    ///   - edits: the items, in the order given. A repeated id keeps the **last**
    ///     occurrence, in the position the first one held — a model listing an id
    ///     twice has revised it, and revising should not reorder a plan.
    ///   - merge: `false` replaces the list outright. `true` updates matching ids
    ///     with whichever fields were sent and appends the rest.
    public mutating func write(_ edits: [TodoEdit], merge: Bool) {
        let incoming = Self.collapsingDuplicates(edits)

        if merge {
            var byID = Dictionary(items.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            var order = items.map(\.id)
            for edit in incoming {
                let id = Self.identify(edit)
                if let existing = byID[id] {
                    byID[id] = Todo(
                        id: id,
                        content: edit.content.map(Self.clean) ?? existing.content,
                        status: edit.status ?? existing.status)
                } else {
                    byID[id] = Self.complete(edit)
                    order.append(id)
                }
            }
            items = order.compactMap { byID[$0] }
        } else {
            items = incoming.map(Self.complete)
        }

        items = Array(items.prefix(Self.maxItems))
    }

    /// How many items are in each state. What the model is told after a write, so
    /// it can see the shape of its plan without re-reading all of it.
    public var summary: [TodoStatus: Int] {
        items.reduce(into: [:]) { counts, item in counts[item.status, default: 0] += 1 }
    }

    /// The last occurrence of each id, in the position the first one held.
    private static func collapsingDuplicates(_ edits: [TodoEdit]) -> [TodoEdit] {
        var lastByID: [String: TodoEdit] = [:]
        var order: [String] = []
        for edit in edits {
            let id = identify(edit)
            if lastByID[id] == nil { order.append(id) }
            lastByID[id] = edit
        }
        return order.compactMap { lastByID[$0] }
    }

    private static func identify(_ edit: TodoEdit) -> String {
        let trimmed = edit.id.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? anonymous : trimmed
    }

    /// An edit with its absent fields filled in, for the replace path and for a
    /// merge that turned out to be an addition.
    private static func complete(_ edit: TodoEdit) -> Todo {
        Todo(
            id: identify(edit),
            content: edit.content.map(clean) ?? placeholder,
            status: edit.status ?? .pending)
    }

    private static func clean(_ content: String) -> String {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return placeholder }
        guard trimmed.count > maxContent else { return trimmed }
        return String(trimmed.prefix(maxContent - truncationMarker.count)) + truncationMarker
    }
}
