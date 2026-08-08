// InboxTests.swift — what survives between two processes.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The failures here are all quiet: text that vanishes, text that arrives twice,
// or text that arrives in an order nobody sent it in. None of them throws, and
// the person who notices is the one whose note is gone.

import Foundation
import XCTest

@testable import WattRouter

final class InboxTests: XCTestCase {
    private var container = URL(filePath: "/")

    override func setUpWithError() throws {
        container = URL(filePath: NSTemporaryDirectory())
            .appending(path: "inbox-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: container, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: container)
    }

    private var inbox: Inbox { Inbox(container: container) }
    private static let now = Date(timeIntervalSince1970: 1_786_000_000)

    func testWhatWentInComesOut() throws {
        try inbox.write("a shared link", at: Self.now)

        XCTAssertEqual(inbox.drain(), ["a shared link"])
    }

    func testDrainingRemovesRatherThanReads() throws {
        // Anything else shows the same shared link at every launch, forever.
        try inbox.write("once", at: Self.now)

        XCTAssertEqual(inbox.drain(), ["once"])
        XCTAssertTrue(inbox.drain().isEmpty)
    }

    func testOrderIsTheOrderItWasSharedIn() throws {
        // The filesystem promises nothing about directory order, so the order
        // lives in the names. Ten items, because the failure this catches is
        // "10" sorting before "9" and only appearing past the first nine.
        for index in 1...10 {
            try inbox.write("item \(index)", at: Self.now.addingTimeInterval(Double(index)))
        }

        XCTAssertEqual(inbox.drain(), (1...10).map { "item \($0)" })
    }

    func testTwoThingsSharedAtTheSameInstantAreBothKept() throws {
        // A name that is only the timestamp makes the second one replace the
        // first, and the person who shared twice quickly loses one silently.
        try inbox.write("first", at: Self.now)
        try inbox.write("second", at: Self.now)

        XCTAssertEqual(inbox.drain().count, 2)
    }

    func testAContainerThatHasNeverBeenUsedIsEmptyRatherThanAFailure() {
        // Every launch but a few. Nothing has been shared and the directory does
        // not exist.
        XCTAssertTrue(inbox.drain().isEmpty)
    }

    func testSharingIntoAFreshInstallWorksBeforeTheAppHasRun() throws {
        // The extension can be the first thing that ever writes here, so it
        // makes the directory rather than assuming the app did.
        let untouched = Inbox(container: container.appending(path: "never-used"))
        XCTAssertNoThrow(try untouched.write("early", at: Self.now))

        XCTAssertEqual(untouched.drain(), ["early"])
    }

    func testAHalfWrittenFileIsNotReadAsAnItem() throws {
        // What a killed extension leaves behind. The rename is what makes a
        // whole item appear at once; the partial must not be read meanwhile.
        try inbox.write("whole", at: Self.now)
        let partial = container.appending(path: "inbox/.something.partial")
        try Data("half".utf8).write(to: partial)

        XCTAssertEqual(inbox.drain(), ["whole"])
    }

    func testTextWithNewlinesAndUnicodeSurvives() throws {
        // Shared text is whatever was on the screen: quotes, emoji, newlines.
        let shared = "líne one\nline two — “quoted” 🙂"
        try inbox.write(shared, at: Self.now)

        XCTAssertEqual(inbox.drain(), [shared])
    }
}
