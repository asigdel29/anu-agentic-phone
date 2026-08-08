// CoreRepositoryTests.swift — the git half, called for real.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// What is reachable without a repository, which is the refusals — a Swift test
// cannot make one, for the reasons Repository.swift gives. That still exercises
// the whole crossing: the path goes out as a C string, an allocation comes back,
// it is decoded and it is freed. The successful decodes are RepositoryTests'.
//
// Each case asks about a directory that exists and is not a repository, rather
// than one that does not exist. Both refuse, and only the first proves the
// refusal is about git rather than about the filesystem.

import Foundation
import XCTest

@testable import WattRouter

final class CoreRepositoryTests: XCTestCase {
    private var directory = URL(filePath: "/")

    override func setUpWithError() throws {
        directory = URL(filePath: NSTemporaryDirectory())
            .appending(path: "core-repository-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }

    /// The message behind a refusal, or a failure naming what came back instead.
    private func refusal<Value>(
        _ body: @autoclosure () throws -> Value,
        file: StaticString = #filePath, line: UInt = #line
    ) -> String {
        do {
            let answered = try body()
            XCTFail(
                "a directory that is not a repository answered \(answered)",
                file: file, line: line)
        } catch let error as GitError {
            guard case .refused(let why) = error else {
                XCTFail("refused as \(error) rather than with a message", file: file, line: line)
                return ""
            }
            return why
        } catch {
            XCTFail("threw something that is not a GitError: \(error)", file: file, line: line)
        }
        return ""
    }

    func testAskingAboutSomethingThatIsNotARepositorySaysWhereItLooked() {
        // A model told only "not a repository" tries the same path again. The
        // message crosses from git.rs through C and up to here unchanged, and
        // this is the only test that covers all three of those steps at once.
        let git = CoreRepository()

        for why in [
            refusal(try git.head(of: directory)),
            refusal(try git.status(of: directory)),
            refusal(try git.add(["a.txt"], in: directory)),
            refusal(try git.commit("a message", in: directory)),
        ] {
            XCTAssertTrue(
                why.contains(directory.path(percentEncoded: false)),
                "did not say where it looked: \(why)")
        }
    }

    func testTheCoresWordsArriveRatherThanThisLayersWords() {
        // Not reworded on the way up. "no git repository at …" is git.rs's
        // phrasing, and a paraphrase here would be a second vocabulary for the
        // model to learn.
        let why = refusal(try CoreRepository().status(of: directory))
        XCTAssertTrue(why.hasPrefix("no git repository at"), "reworded to: \(why)")
    }

    func testAPathWithSpacesAndUnicodeCrossesIntact() {
        // The path goes out as UTF-8 and comes back inside a JSON string, so
        // anything that would be mangled by either is visible in the refusal.
        let awkward = directory.appending(path: "a folder — with ünïcode")
        XCTAssertNoThrow(
            try FileManager.default.createDirectory(
                at: awkward, withIntermediateDirectories: true))

        let why = refusal(try CoreRepository().head(of: awkward))
        XCTAssertTrue(
            why.contains("a folder — with ünïcode"), "the path did not survive: \(why)")
    }
}
