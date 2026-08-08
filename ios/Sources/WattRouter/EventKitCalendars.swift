// EventKitCalendars.swift — the calendar seams, against the framework that owns them.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   EventKitAuthorizer  Calendar and reminder access, asked for and read.
//   EventKitCalendars   Events out of the store, as the tool's own type.
//
// Actors, both of them, and not for concurrency's sake. `EKEventStore` is not
// `Sendable`, and the alternative is an unchecked conformance asserting
// something nobody checked. Actor isolation is the version of that claim the
// compiler enforces.
//
// One store, shared. Each `EKEventStore` is a connection to the calendar
// database, and building one per call is expensive enough to be visible on a
// tool that runs every turn.
//
// The mapping from the framework's status is a static function over raw values
// rather than a method, so all of it is testable without a device and without
// anybody granting anything. That matters more here than usual: iOS 17 split
// calendar access in two, and both halves are "not denied".

import EventKit
import Foundation

/// Calendar and reminder access.
public actor EventKitAuthorizer: Authorizer {
    private let store: EKEventStore

    public init(store: EKEventStore = EKEventStore()) {
        self.store = store
    }

    /// Which of the framework's entities a capability is about.
    ///
    /// `nil` for anything EventKit does not own. An app wiring this as its whole
    /// authorizer genuinely cannot obtain contacts or location, and reporting
    /// them as unavailable is true of that build rather than a placeholder.
    static func entity(for capability: Capability) -> EKEntityType? {
        switch capability {
        case .calendar: .event
        case .reminders: .reminder
        case .contacts, .location: nil
        }
    }

    /// The framework's answer, as this app's.
    ///
    /// `writeOnly` is the case worth reading twice. It is not a refusal, and it
    /// is not enough: a read against write-only access returns an empty calendar
    /// rather than an error, so a model would be told the day is clear. Refused
    /// is the closest true thing, and the refusal already names
    /// `Settings > Privacy & Security > Calendars` — which is exactly the screen
    /// where Full Access and Add Events Only are chosen between.
    ///
    /// An unknown case becomes unasked rather than granted or refused. The
    /// framework has already grown one since this app's floor; the safe default
    /// is the one that asks rather than the one that assumes.
    static func state(from status: EKAuthorizationStatus) -> PermissionState {
        switch status {
        case .fullAccess: .granted
        case .denied, .writeOnly: .refused
        case .restricted: .unavailable
        case .notDetermined: .unasked
        @unknown default: .unasked
        }
    }

    public func state(of capability: Capability) async -> PermissionState {
        guard let entity = Self.entity(for: capability) else { return .unavailable }
        return Self.state(from: EKEventStore.authorizationStatus(for: entity))
    }

    public func request(_ capability: Capability) async -> PermissionState {
        guard let entity = Self.entity(for: capability) else { return .unavailable }
        // The returned flag says granted or not; the status says which kind of
        // not, and the kind is what the person is told about. So the flag is
        // dropped and the status read back.
        switch entity {
        case .event: _ = try? await store.requestFullAccessToEvents()
        case .reminder: _ = try? await store.requestFullAccessToReminders()
        @unknown default: break
        }
        return Self.state(from: EKEventStore.authorizationStatus(for: entity))
    }
}

/// Why the store could not take an event.
public enum EventKitError: LocalizedError, Equatable, Sendable {
    /// No calendar of that name, or none that can be written to.
    case noSuchCalendar(asked: String, available: [String])
    /// Nothing on this device can be written to at all.
    case nowhereToWrite

    public var errorDescription: String? {
        switch self {
        case .noSuchCalendar(let asked, let available):
            // The alternatives, not just the mistake — the same reason `ToolBox`
            // lists the tools it knows when a name does not match one.
            """
            there is no calendar called "\(asked)" that can be written to. \
            Available: \(available.joined(separator: ", ")).
            """
        case .nowhereToWrite:
            """
            no calendar on this device can be written to, so there is nowhere to \
            put this. Nothing was added.
            """
        }
    }
}

/// Events out of the store.
public actor EventKitCalendars: Calendars {
    private let store: EKEventStore

    public init(store: EKEventStore = EKEventStore()) {
        self.store = store
    }

    public func events(in range: TimeRange) async throws -> [CalendarEvent] {
        let predicate = store.predicateForEvents(
            withStart: range.start, end: range.end, calendars: nil)

        return store.events(matching: predicate)
            .sorted { $0.startDate < $1.startDate }
            .map(Self.event)
    }

    /// One event, with the framework's absences turned into this app's.
    ///
    /// `title` and `location` are implicitly unwrapped optionals and the
    /// framework spells their absence both ways. Measured: a fresh `EKEvent` has
    /// a title of `""` rather than `nil`, so a nil-coalesce alone leaves a blank
    /// where the name should be and the line reads as two spaces between a time
    /// and a calendar. An event built outside a granted store has no calendar at
    /// all.
    static func event(_ event: EKEvent) -> CalendarEvent {
        CalendarEvent(
            title: written(event.title) ?? "(untitled)",
            starts: event.startDate,
            ends: event.endDate,
            isAllDay: event.isAllDay,
            calendar: written(event.calendar?.title) ?? "(unknown calendar)",
            location: written(event.location))
    }

    public func add(_ event: CalendarEvent) async throws -> String {
        let target = try writable(named: event.calendar)

        let fresh = EKEvent(eventStore: store)
        fresh.title = event.title
        fresh.startDate = event.starts
        fresh.endDate = event.ends
        fresh.isAllDay = event.isAllDay
        fresh.location = event.location
        fresh.calendar = target

        try store.save(fresh, span: .thisEvent, commit: true)
        return target.title
    }

    /// Where to put it.
    ///
    /// An unmatched name is refused rather than quietly redirected. The return
    /// value says where the event landed, so a fallback would be reported
    /// honestly — but a work meeting silently filed under Personal is a mistake
    /// somebody finds later, and the model can retry with a name from the list.
    private func writable(named asked: String) throws -> EKCalendar {
        let usable = store.calendars(for: .event).filter(\.allowsContentModifications)

        guard !asked.isEmpty else {
            guard let fallback = store.defaultCalendarForNewEvents ?? usable.first else {
                throw EventKitError.nowhereToWrite
            }
            return fallback
        }
        guard let match = usable.first(where: { $0.title == asked }) else {
            throw EventKitError.noSuchCalendar(asked: asked, available: usable.map(\.title))
        }
        return match
    }

    /// Text somebody actually wrote, or nothing. Blank and absent are one thing.
    private static func written(_ text: String?) -> String? {
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed?.isEmpty ?? true) ? nil : trimmed
    }
}
