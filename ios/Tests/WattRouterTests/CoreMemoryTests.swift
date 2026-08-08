// CoreMemoryTests.swift — the store, held open for real.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Unlike the other three conformances, this one can be exercised end to end on a
// simulator: a memory store needs no permission and no hardware, only a
// directory. So these are round trips rather than refusals.
//
// What a simulator cannot show is anything about data protection. It is not
// implemented there: setting the attribute is silently ignored and reading it
// back gives nil, so a case asserting it would fail on the simulator and pass on
// a device, which is the wrong way round for a gate.
//
// What is checkable is *which* files a store is — three, not one — and that is
// the half that gets forgotten anyway.

import Foundation
import XCTest

@testable import WattRouter

final class CoreMemoryTests: XCTestCase {
    private var directory = URL(filePath: "/")

    override func setUpWithError() throws {
        directory = URL(filePath: NSTemporaryDirectory())
            .appending(path: "core-memory-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }

    private var path: URL { directory.appending(path: "memory.db") }

    func testAStoreOpensWhereThereWasNothing() throws {
        // A fresh install: no directory, no database, no history to bound.
        let store = try XCTUnwrap(CoreMemory(path: directory.appending(path: "new/memory.db")))

        XCTAssertNoThrow(try store.remember("hello", speaker: "user", session: "s", at: .now))
    }

    func testWhatWasRememberedIsRecalled() throws {
        // The whole point, through Swift, C, and SQLite and back.
        let store = try XCTUnwrap(CoreMemory(path: path))
        _ = try store.remember(
            "the spare key is with Dave next door", speaker: "user", session: "s", at: .now)

        let found = try store.recall("where is the spare key", most: 5)
        XCTAssertFalse(found.isEmpty, "recalled nothing")
        XCTAssertTrue(
            found.evidence.contains { $0.text.contains("spare key") },
            "recalled the wrong turn: \(found)")
    }

    func testAnEmptyStoreRecallsNothingRatherThanFailing() throws {
        // A fresh install being asked a question, which is ordinary.
        let store = try XCTUnwrap(CoreMemory(path: path))

        XCTAssertTrue(try store.recall("anything", most: 5).isEmpty)
    }

    func testATurnWithNoTextIsRefusedInThisLayersVocabulary() throws {
        // The core's message, arriving as a MemoryError rather than a GitError —
        // which is what the shared envelope's failure parameter is for.
        let store = try XCTUnwrap(CoreMemory(path: path))

        do {
            _ = try store.remember("   ", speaker: "user", session: "s", at: .now)
            XCTFail("stored it")
        } catch {
            guard case .refused(let why) = error else {
                return XCTFail("refused as \(error)")
            }
            XCTAssertTrue(why.contains("no text"), why)
        }
    }

    func testTheStoreSurvivesBeingClosedAndOpenedAgain() throws {
        // Which is every launch. The horizon runs on the way in, so a store that
        // did not survive it would fail here rather than on somebody's phone.
        let first = try XCTUnwrap(CoreMemory(path: path))
        _ = try first.remember("the bins go out on Tuesday", speaker: "user", session: "s", at: .now)

        let second = try XCTUnwrap(CoreMemory(path: path))
        XCTAssertFalse(try second.recall("bins", most: 5).isEmpty)
    }

    func testAStoreIsThreeFilesRatherThanOne() {
        // Protecting the database alone leaves -wal and -shm unprotected, which
        // is an unprotected store with extra steps: they hold pages that have not
        // been checkpointed yet.
        let named = CoreMemory.files(of: path).map(\.lastPathComponent)

        XCTAssertEqual(named, ["memory.db", "memory.db-wal", "memory.db-shm"])
    }

    func testSqliteReallyDoesWriteThoseTwoBesideIt() throws {
        // The list above is a claim about SQLite. This is the claim checked, so
        // a journal mode that stopped producing them would fail here rather than
        // leaving two files protected that no longer exist.
        let store = try XCTUnwrap(CoreMemory(path: path))
        _ = try store.remember("something to write", speaker: "user", session: "s", at: .now)

        let written = CoreMemory.files(of: path).filter {
            FileManager.default.fileExists(atPath: $0.path(percentEncoded: false))
        }
        XCTAssertEqual(written.count, 3, "SQLite wrote \(written.map(\.lastPathComponent))")
    }
}
