// PhoneToolsTests.swift — the assembly, which was previously only a comment.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// None of this was reachable while the assembly lived inside a SwiftUI view's
// private method, and each case here is a comment that used to sit in it. That
// is most of the argument for the move: "one Permission for the whole app" is a
// claim, and a claim in a comment is one somebody deletes.

import Foundation
import XCTest

@testable import WattRouter

final class PhoneToolsTests: XCTestCase {
    private var root = URL(filePath: "/")

    override func setUpWithError() throws {
        root = URL(filePath: NSTemporaryDirectory())
            .appending(path: "phone-tools-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: root)
    }

    private func tools() throws -> ToolBox {
        PhoneTools.all(workspace: try Workspace(root: root))
    }

    /// Every tool that is there whatever the device does.
    ///
    /// Written out rather than counted. A count passes when one tool is swapped
    /// for another, which is the change worth noticing.
    private static let unconditional: Set<String> = [
        "read_file", "write_file", "search_files", "patch", "todo",
        "read_calendar", "add_event", "read_reminders", "add_reminder",
        "find_contact", "run_shortcut", "where_am_i",
        "git_status", "git_add", "git_commit",
    ]

    func testEveryToolTheAppOffersIsThere() throws {
        let names = Set(try tools().tools.map(\.name))

        XCTAssertTrue(
            names.isSuperset(of: Self.unconditional),
            "missing: \(Self.unconditional.subtracting(names))")
        // Nothing beyond the unconditional set and the two memory tools, which
        // are the only conditional ones.
        XCTAssertTrue(
            names.subtracting(Self.unconditional).isSubset(of: ["remember", "recall"]),
            "unexpected: \(names.subtracting(Self.unconditional))")
    }

    func testTheMemoryToolsArriveTogetherOrNotAtAll() throws {
        // The store may not open, and then the app runs without memory rather
        // than not running. What must not happen is one of the two appearing: a
        // model that can remember and not recall fills a store it cannot read.
        let names = Set(try tools().tools.map(\.name))

        XCTAssertEqual(
            names.contains("remember"), names.contains("recall"),
            "one memory tool without the other")
    }

    func testClarifyIsDeliberatelyAbsent() throws {
        // It asks the person a question and waits. Nothing that runs a turn
        // today can answer one, so a model reaching for it would stop, correctly,
        // forever. This fails the day somebody adds it without the affordance.
        XCTAssertNil(try tools()["clarify"])
    }

    func testTheToolsSharingACapabilityComeInPairs() throws {
        // The invariant the assembly exists to hold is one `Permission` across
        // every tool, and it cannot be asserted by running them: on a simulator
        // the first calendar call presents a system prompt, and an unattended
        // suite waits for an answer that never comes. That hung this suite once,
        // which is why it is written down here rather than left to be
        // rediscovered.
        //
        // What is checkable is the shape the invariant is about — two tools per
        // capability, which is the situation a second `Permission` would turn
        // into a second prompt.
        let names = Set(try tools().tools.map(\.name))

        XCTAssertTrue(names.isSuperset(of: ["read_calendar", "add_event"]))
        XCTAssertTrue(names.isSuperset(of: ["read_reminders", "add_reminder"]))
    }

    func testTheFileToolsAreScopedToTheWorkspaceGiven() async throws {
        // The workspace is the one argument, and passing it to some tools and
        // not others is the mistake that would let git write outside it.
        let box = try tools()
        let outside = try await box.run(
            ToolCall(id: "a", name: "read_file", arguments: #"{"path":"../../etc/hosts"}"#))

        XCTAssertEqual(outside.isError, true)
        XCTAssertTrue(outside.content.contains(root.lastPathComponent), outside.content)
    }
}
