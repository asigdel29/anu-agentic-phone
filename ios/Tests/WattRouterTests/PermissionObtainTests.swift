// PermissionObtainTests.swift — one prompt, and never a second ask.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// That a granted capability proceeds is one line and would pass against almost
// any implementation. What is worth pinning is the rest: that a refusal is never
// asked again, that two tools wanting the same thing produce one prompt, and
// that a person who relents in Settings is noticed rather than held to an answer
// this cached.
//
// The concurrency case is the one with a real failure behind it. Deleting the
// join does not stack two prompts, which is what it reads as protecting against.
// It tells the second tool the prompt was dismissed while the prompt is still on
// screen, because the capability is marked as asked before the answer arrives.

import Foundation
import XCTest

@testable import WattRouter

/// Stands in for the framework that owns the answer, and counts the prompts.
private actor StubAuthorizer: Authorizer {
    private(set) var prompts: [Capability] = []
    private var states: [Capability: PermissionState] = [:]
    private let answer: PermissionState
    private let delay: Duration

    init(
        _ state: PermissionState = .unasked, answers: PermissionState = .granted,
        delay: Duration = .zero
    ) {
        self.answer = answers
        self.delay = delay
        for capability in Capability.allCases { states[capability] = state }
    }

    func state(of capability: Capability) async -> PermissionState {
        states[capability] ?? .unasked
    }

    func request(_ capability: Capability) async -> PermissionState {
        prompts.append(capability)
        try? await Task.sleep(for: delay)
        states[capability] = answer
        return answer
    }

    /// The person going to Settings, which nothing in the app observes.
    func relent(_ state: PermissionState, for capability: Capability) {
        states[capability] = state
    }
}

final class PermissionObtainTests: XCTestCase {
    func testAnUnaskedCapabilityIsAskedExactlyOnce() async throws {
        let authorizer = StubAuthorizer(.unasked, answers: .granted)
        let permission = Permission(authorizer)

        try await permission.obtain(.calendar)
        try await permission.obtain(.calendar)

        let prompts = await authorizer.prompts
        XCTAssertEqual(prompts, [.calendar], "asked more than once: \(prompts)")
    }

    func testAlreadyGrantedShowsNothing() async throws {
        let authorizer = StubAuthorizer(.granted)
        try await Permission(authorizer).obtain(.contacts)

        let prompts = await authorizer.prompts
        XCTAssertTrue(prompts.isEmpty, "prompted for something already granted")
    }

    func testARefusalIsNotAskedAgain() async throws {
        let authorizer = StubAuthorizer(.unasked, answers: .refused)
        let permission = Permission(authorizer)

        for _ in 0..<3 {
            await assertThrows(try await permission.obtain(.calendar)) { error in
                XCTAssertEqual(error as? PermissionError, .refused(.calendar))
            }
        }

        let prompts = await authorizer.prompts
        XCTAssertEqual(prompts.count, 1, "asked \(prompts.count) times after a refusal")
    }

    func testTwoToolsWantingTheSameThingProduceOnePrompt() async throws {
        let authorizer = StubAuthorizer(.unasked, answers: .granted, delay: .milliseconds(50))
        let permission = Permission(authorizer)

        async let first: Void = permission.obtain(.reminders)
        async let second: Void = permission.obtain(.reminders)
        _ = try await (first, second)

        let prompts = await authorizer.prompts
        XCTAssertEqual(prompts, [.reminders], "asked twice for one capability: \(prompts)")
    }

    func testARestrictionIsNeverAskedAbout() async throws {
        let authorizer = StubAuthorizer(.unavailable)
        let permission = Permission(authorizer)

        await assertThrows(try await permission.obtain(.location)) { error in
            XCTAssertEqual(error as? PermissionError, .unavailable(.location))
        }

        let prompts = await authorizer.prompts
        XCTAssertTrue(prompts.isEmpty, "prompted for something policy forbids")
    }

    func testADismissedPromptIsNotARefusal() async throws {
        let authorizer = StubAuthorizer(.unasked, answers: .unasked)
        let permission = Permission(authorizer)

        await assertThrows(try await permission.obtain(.calendar)) { error in
            XCTAssertEqual(error as? PermissionError, .unanswered(.calendar))
        }
    }

    func testRelentingInSettingsIsNoticed() async throws {
        // Nothing tells the app the person went and turned it on, so the only
        // way to find out is to look again. A cached refusal holds them to an
        // answer they have already changed.
        let authorizer = StubAuthorizer(.unasked, answers: .refused)
        let permission = Permission(authorizer)
        await assertThrows(try await permission.obtain(.contacts))

        await authorizer.relent(.granted, for: .contacts)
        try await permission.obtain(.contacts)

        let prompts = await authorizer.prompts
        XCTAssertEqual(prompts.count, 1, "asked again rather than looking again")
    }
}

/// `XCTAssertThrowsError` predates concurrency and will not await its expression.
private func assertThrows<T>(
    _ expression: @autoclosure () async throws -> T,
    file: StaticString = #filePath, line: UInt = #line,
    _ handle: (any Error) -> Void = { _ in }
) async {
    do {
        _ = try await expression()
        XCTFail("did not throw", file: file, line: line)
    } catch {
        handle(error)
    }
}
