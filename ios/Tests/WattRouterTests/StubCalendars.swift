// StubCalendars.swift — a calendar that is not one, for the tools that read and
// write to it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Shared by the two calendar tools rather than declared twice: a second copy
// would have to keep step with this one on every change to the seam.
//
// It records rather than pretends. The tools' interesting properties are about
// what they do *not* do — reach the calendar after a refusal, or before reading
// their own arguments — so what it was asked and what it was given are the
// assertions.

import Foundation

@testable import WattRouter

/// A calendar that remembers what happened to it.
actor StubCalendars: Calendars {
    private(set) var reads = 0
    private(set) var added: [CalendarEvent] = []
    private let answer: [CalendarEvent]
    private let lands: String

    init(_ answer: [CalendarEvent] = [], lands: String = "Personal") {
        self.answer = answer
        self.lands = lands
    }

    func events(in range: TimeRange) async throws -> [CalendarEvent] {
        reads += 1
        return answer
    }

    func add(_ event: CalendarEvent) async throws -> String {
        added.append(event)
        // Deliberately not the calendar it was asked for. Where an event landed
        // and where it was aimed are different things, and a test that returns
        // the argument cannot tell whether the tool says the right one.
        return lands
    }
}

/// An authorizer that has already made up its mind.
struct FixedAuthorizer: Authorizer {
    let says: PermissionState
    func state(of capability: Capability) async -> PermissionState { says }
    func request(_ capability: Capability) async -> PermissionState { says }
}
