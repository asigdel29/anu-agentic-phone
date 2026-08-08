// ByCapabilityTests.swift — which authorizer answers for what.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The case that matters is the last one: a capability nobody owns is
// unavailable, and it does not fall through to somebody who would answer about a
// different framework. That is the failure the explicit map exists to prevent,
// and it would otherwise only show up on a phone with a device policy on it.

import Foundation
import XCTest

@testable import WattRouter

final class ByCapabilityTests: XCTestCase {
    /// Records what it was asked, so "the right one answered" is checkable.
    private actor Recording: Authorizer {
        let says: PermissionState
        private(set) var asked: [Capability] = []
        private(set) var requested: [Capability] = []

        init(_ says: PermissionState) { self.says = says }

        func state(of capability: Capability) async -> PermissionState {
            asked.append(capability)
            return says
        }

        func request(_ capability: Capability) async -> PermissionState {
            requested.append(capability)
            return says
        }
    }

    func testEachCapabilityGoesToItsOwnAuthorizer() async {
        let events = Recording(.granted)
        let people = Recording(.refused)
        let router = ByCapability([.calendar: events, .reminders: events, .contacts: people])

        await XCTAssertEqualAsync(await router.state(of: .calendar), .granted)
        await XCTAssertEqualAsync(await router.state(of: .contacts), .refused)

        let askedOfEvents = await events.asked
        let askedOfPeople = await people.asked
        XCTAssertEqual(askedOfEvents, [.calendar])
        XCTAssertEqual(askedOfPeople, [.contacts])
    }

    func testACapabilityNobodyOwnsIsUnavailableRatherThanPassedOn() async {
        // The whole reason the map is explicit. Falling through to the next
        // authorizer would ask about the wrong framework — and EventKit's
        // `.unavailable` means both "not mine" and "restricted by policy", so
        // the fall-through cannot tell them apart.
        let people = Recording(.granted)
        let router = ByCapability([.contacts: people])

        await XCTAssertEqualAsync(await router.state(of: .location), .unavailable)
        await XCTAssertEqualAsync(await router.request(.location), .unavailable)

        let asked = await people.asked
        let requested = await people.requested
        XCTAssertTrue(asked.isEmpty, "asked somebody who does not own it")
        XCTAssertTrue(requested.isEmpty, "prompted through somebody who does not own it")
    }

    func testARequestGoesToTheSameOwnerAsARead() async {
        // A read and a prompt that disagreed about the owner would ask one
        // framework and report another's answer.
        let people = Recording(.granted)
        let router = ByCapability([.contacts: people])

        await XCTAssertEqualAsync(await router.request(.contacts), .granted)

        let requested = await people.requested
        XCTAssertEqual(requested, [.contacts])
    }

    func testOneAuthorizerMayOwnSeveralCapabilities() async {
        // Which is what EventKit is: one store, two entities, one prompt each.
        let events = Recording(.granted)
        let router = ByCapability([.calendar: events, .reminders: events])

        _ = await router.state(of: .calendar)
        _ = await router.state(of: .reminders)

        let asked = await events.asked
        XCTAssertEqual(asked, [.calendar, .reminders])
    }

    /// `XCTAssertEqual` does not take an async autoclosure, and writing the
    /// `await` outside it does not compile either.
    private func XCTAssertEqualAsync<T: Equatable>(
        _ got: T, _ want: T, file: StaticString = #filePath, line: UInt = #line
    ) async {
        XCTAssertEqual(got, want, file: file, line: line)
    }
}
