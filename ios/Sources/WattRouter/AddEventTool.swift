// AddEventTool.swift — putting something on the calendar.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   AddEventTool  Scheduling one, as a tool.
//
// Thin on purpose. What a written date means when it is going on somebody's
// calendar is in `ScheduledEvent.swift`, tested there without a permission or a
// store in the way. What is left is a decode, a permission, a write and a
// sentence.
//
// The arguments are read before the permission is obtained, as in
// `read_calendar`: one prompt exists per capability for the life of the app and
// a call the model can rewrite must not be what spends it.
//
// The write happens without a confirmation, and that is a decision rather than
// an omission. The person granted calendar access and the model was asked to do
// this; `clarify` already exists for when the model wants to check.

import Foundation

/// Put something on the calendar.
public struct AddEventTool: Tool {
    public let name = "add_event"

    public let purpose = """
        Put something on the calendar. Write the start as 2026-08-09T14:30; a \
        bare day is only accepted with all_day set. Leave the end out for an \
        hour. Naming a calendar is optional, and the answer says which one it \
        actually went on.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "title": {"type": "string", "description": "What it is called."},
            "starts": {"type": "string", "description": "When it starts, as 2026-08-09T14:30."},
            "ends": {"type": "string", "description": "When it ends. Omitted means an hour."},
            "all_day": {"type": "boolean", "description": "Whether it takes whole days."},
            "calendar": {"type": "string", "description": "Which calendar. Omitted means the default."},
            "location": {"type": "string", "description": "Where it is."}
          },
          "required": ["title", "starts"]
        }
        """

    private let calendars: any Calendars
    private let permission: Permission
    private let zone: TimeZone

    public init(calendars: any Calendars, permission: Permission, zone: TimeZone = .current) {
        self.calendars = calendars
        self.permission = permission
        self.zone = zone
    }

    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)
        let event = try CalendarEvent.scheduled(
            title: request.title, starts: request.starts, ends: request.ends,
            allDay: request.allDay ?? false, calendar: request.calendar ?? "",
            location: request.location, zone: zone)

        try await permission.obtain(.calendar)
        let landed = try await calendars.add(event)

        let shown = DateFormatter()
        shown.locale = Locale(identifier: "en_US_POSIX")
        shown.timeZone = zone
        shown.dateFormat = event.isAllDay ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm"
        let span = "\(shown.string(from: event.starts)) to \(shown.string(from: event.ends))"
        return "added \"\(event.title)\" \(event.isAllDay ? "all day " : "")\(span) on \(landed)"
    }

    private struct Request: Decodable {
        let title: String
        let starts: String
        let ends: String?
        let allDay: Bool?
        let calendar: String?
        let location: String?

        /// The model writes `all_day`, which is what the schema says. A key
        /// strategy is set per decoder and this decoder reads every tool's
        /// arguments, so the mapping belongs on the one type that needs it.
        private enum CodingKeys: String, CodingKey {
            case title, starts, ends, calendar, location
            case allDay = "all_day"
        }
    }
}
