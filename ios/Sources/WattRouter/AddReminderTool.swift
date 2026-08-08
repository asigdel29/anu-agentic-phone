// AddReminderTool.swift — putting something on a reminders list.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   AddReminderTool  Adding one, as a tool.
//
// Thinner than AddEventTool, and the reason is worth stating because the
// asymmetry looks like something is missing.
//
// `add_event` needs ScheduledEvent.swift to decide what a written date means: an
// event with no time is nearly always a mistake, and a backwards one always is.
// A reminder has neither problem. "Remind me to call the plumber" has no date
// and must not acquire one, so no date is the ordinary case rather than a
// refusal. And "remind me on Friday" means the day — a bare day here is a
// legitimate due date, where in an event it is a suspicious one.
//
// So there is no ScheduledReminder.swift, and the one decision left is where a
// day goes: in as a day, so the conformance stores components without an hour
// and the reader renders it as a day. Stored as midnight it is overdue by
// breakfast.
//
// The arguments are read before the permission is obtained, as everywhere else:
// one prompt per capability for the life of the app, and a call the model can
// rewrite must not be what spends it.

import Foundation

/// Put something on a reminders list.
public struct AddReminderTool: Tool {
    public let name = "add_reminder"

    public let purpose = """
        Add a reminder. A due date is optional and most reminders do not have \
        one — leave it out unless a time was actually mentioned. Write a day as \
        2026-08-09, which is due that day rather than at midnight, or an instant \
        as 2026-08-09T14:30. Naming a list is optional, and the answer says \
        which one it went on.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "title": {"type": "string", "description": "What to be reminded of."},
            "due": {
              "type": "string",
              "description": "When, as 2026-08-09 or 2026-08-09T14:30. Omit if none was mentioned."
            },
            "list": {"type": "string", "description": "Which list. Omitted means the default."},
            "notes": {"type": "string", "description": "Anything else worth keeping with it."},
            "priority": {
              "type": "string",
              "enum": ["none", "high", "medium", "low"],
              "description": "Only if one was asked for. Omitted means none."
            }
          },
          "required": ["title"]
        }
        """

    private let reminders: any Reminders
    private let permission: Permission
    private let zone: TimeZone
    private let now: @Sendable () -> Date

    public init(
        reminders: any Reminders, permission: Permission, zone: TimeZone = .current,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.reminders = reminders
        self.permission = permission
        self.zone = zone
        self.now = now
    }

    /// - Returns: what was added and where it landed.
    ///
    /// # Rely
    /// Nothing. The permission is obtained here, after the arguments are read.
    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        let title = request.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else {
            return "a reminder needs something to be reminded of, and the title was empty"
        }

        // Read before the prompt. A date the tool cannot parse must not be what
        // spends the one chance to ask for reminders access.
        let due = try request.due.map { try Self.read($0, zone: zone, now: now()) }

        try await permission.obtain(.reminders)
        let landed = try await reminders.add(
            Reminder(
                title: title, due: due?.at, isAllDay: due?.isAllDay ?? false,
                list: request.list ?? "",
                priority: Reminder.Priority(rawValue: request.priority ?? "none") ?? .none,
                notes: request.notes))

        return "added \"\(title)\"\(said(due)) to \(landed)"
    }

    /// A written due date, and whether it was a day.
    ///
    /// A bare day is kept as a day rather than resolved to an instant, which is
    /// the only real decision in this file. `TimeRange.read` is reused for the
    /// parsing and the refusal wording, with the day given as the start so it
    /// arrives as the start of that day rather than the end of it.
    private static func read(
        _ written: String, zone: TimeZone, now: Date
    ) throws -> (at: Date, isAllDay: Bool) {
        let range = try TimeRange.read(from: written, to: nil, now: now, zone: zone)
        // No time in what was written means a day. Cheaper and more honest than
        // re-deriving it from the parsed instant, which cannot tell a deliberate
        // midnight from an absent time.
        return (range.start, !written.contains("T"))
    }

    /// The due date, said back, or nothing when there is none.
    private func said(_ due: (at: Date, isAllDay: Bool)?) -> String {
        guard let due else { return " with no date" }

        let clock = DateFormatter()
        clock.locale = Locale(identifier: "en_US_POSIX")
        clock.timeZone = zone
        clock.dateFormat = due.isAllDay ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm"
        return " due \(clock.string(from: due.at))"
    }

    private struct Request: Decodable {
        let title: String
        let due: String?
        let list: String?
        let notes: String?
        let priority: String?
    }
}
