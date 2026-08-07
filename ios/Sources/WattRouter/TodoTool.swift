// TodoTool.swift — the todo list, as something the model can call.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   TodoTool  Read the plan, or write it.
//
// An actor, because this is the first thing in the stack that holds state a turn
// mutates. A `struct` would need the loop to thread the list through every call
// and put it back afterwards; a class would need a lock, written by hand and
// forgotten once. The protocol's `run` is already `async`, so isolation costs the
// call sites nothing.
//
// `purpose` and the descriptions inside `schema` are prompt text. They are the
// only instructions the model gets about when to use this, so they are written
// for a reader who has to decide in one pass and cannot ask.

import Foundation

/// The plan the model keeps, as a tool.
public actor TodoTool: Tool {
    public nonisolated let name = "todo"

    public nonisolated let purpose = """
        Keep a short plan for the current task. Call with no arguments to read it \
        back. Call with `todos` to write it: use `merge: true` to change the \
        status of items you already wrote, and `merge: false` to replace the plan \
        outright. List items in priority order, highest first. Mark exactly one \
        item `in_progress` at a time, and mark it `completed` before starting the \
        next. Use `cancelled` for work you have decided not to do, which is not \
        the same as work you finished.
        """

    public nonisolated let schema = """
        {
          "type": "object",
          "properties": {
            "todos": {
              "type": "array",
              "description": "The plan. Omit to read the current one without changing it.",
              "items": {
                "type": "object",
                "properties": {
                  "id": {
                    "type": "string",
                    "description": "Stable across calls. Reuse it to update an item."
                  },
                  "content": {
                    "type": "string",
                    "description": "What the task is, in one line."
                  },
                  "status": {
                    "type": "string",
                    "enum": ["pending", "in_progress", "completed", "cancelled"]
                  }
                },
                "required": ["id"]
              }
            },
            "merge": {
              "type": "boolean",
              "description": "Update matching ids and append the rest, instead of replacing."
            }
          }
        }
        """

    private var list = TodoList()

    public init() {}

    public func run(arguments: Data) async throws -> String {
        // A read is naturally "no arguments", and a provider may render that as
        // empty bytes rather than `{}`. Decoding empty bytes fails, which would
        // cost a correction turn for a call that was not wrong.
        let request =
            arguments.isEmpty
            ? Request(todos: nil, merge: nil)
            : try JSONDecoder().decode(Request.self, from: arguments)

        if let todos = request.todos {
            list.write(todos, merge: request.merge ?? false)
        }
        return try rendered()
    }

    /// The current plan and its counts, as JSON.
    private func rendered() throws -> String {
        // Every state, including the ones nothing is in. `TodoList.summary` omits
        // those, which is right for a count and wrong for a model: an absent key
        // has to be inferred, and a zero does not.
        var counts = Dictionary(uniqueKeysWithValues: TodoStatus.allCases.map { ($0.rawValue, 0) })
        for (status, count) in list.summary { counts[status.rawValue] = count }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(
            Response(todos: list.items, total: list.items.count, summary: counts))
        return String(decoding: data, as: UTF8.self)
    }

    private struct Request: Decodable {
        let todos: [TodoEdit]?
        let merge: Bool?
    }

    private struct Response: Encodable {
        let todos: [Todo]
        let total: Int
        /// Keyed by the status's wire name rather than by `TodoStatus`.
        /// `JSONEncoder` writes a dictionary with a custom key type as an array
        /// of alternating keys and values — valid JSON, and not an object, so the
        /// model would receive `["pending", 2, "completed", 1]`.
        let summary: [String: Int]
    }
}
