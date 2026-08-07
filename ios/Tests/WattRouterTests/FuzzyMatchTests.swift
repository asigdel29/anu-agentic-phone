// FuzzyMatchTests.swift — near misses that should match, and ones that must not.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Half of these check that something is *not* found. That is the half that
// matters: a block located in the wrong place is applied, reported as a success,
// and believed. A block not located costs one turn.

import Foundation
import XCTest

@testable import WattRouter

final class FuzzyMatchTests: XCTestCase {
    /// The matched text itself, which is easier to read in a failure than an
    /// index range and is what the caller will actually replace.
    private func matched(_ pattern: String, in content: String) -> (texts: [String], how: String)? {
        guard let found = FuzzyMatch.find(pattern, in: content) else { return nil }
        return (found.ranges.map { String(content[$0]) }, found.strategy.rawValue)
    }

    func testAnExactBlockIsFoundExactly() throws {
        let file = "func a() {\n    return 1\n}\n"
        let found = try XCTUnwrap(matched("    return 1", in: file))

        XCTAssertEqual(found.texts, ["    return 1"])
        XCTAssertEqual(found.how, "exact")
    }

    func testTrailingWhitespaceOnEitherSideDoesNotMatter() throws {
        // An editor stripped it, or did not. Nobody meant it either way.
        let file = "let x = 1   \nlet y = 2\n"
        let found = try XCTUnwrap(matched("let x = 1\nlet y = 2\n", in: file))

        XCTAssertEqual(found.how, "line-trimmed")
        XCTAssertEqual(found.texts, ["let x = 1   \nlet y = 2"])
    }

    func testABlockIndentedDifferentlyStillMatches() throws {
        // The commonest near miss: read at one indentation, quoted back at
        // another. What comes back is the file's text, indentation included, so a
        // caller replacing it knows exactly what it is removing.
        let file = "class C {\n        func a() {\n            return 1\n        }\n}\n"
        let found = try XCTUnwrap(matched("func a() {\n    return 1\n}", in: file))

        XCTAssertEqual(found.how, "indentation-flexible")
        XCTAssertEqual(found.texts, ["        func a() {\n            return 1\n        }"])
    }

    func testAnExactMatchIsPreferredToALooseOneElsewhere() throws {
        // Strategies are ordered and the first to find anything wins. Otherwise a
        // pattern present verbatim could be matched loosely somewhere else too,
        // and `replace_all` would edit both.
        let file = "  loose\nexact\n"
        let found = try XCTUnwrap(matched("exact", in: file))

        XCTAssertEqual(found.how, "exact")
        XCTAssertEqual(found.texts.count, 1)
    }

    func testEveryOccurrenceIsFoundAndTheyDoNotOverlap() throws {
        // Overlapping ranges cannot both be replaced, and a count that includes
        // them lies about how many places an edit would touch.
        let found = try XCTUnwrap(matched("aa", in: "aaaa"))
        XCTAssertEqual(found.texts, ["aa", "aa"])

        let twice = try XCTUnwrap(matched("x = 1", in: "x = 1\ny = 2\nx = 1\n"))
        XCTAssertEqual(twice.texts.count, 2)
    }

    func testTheNewlineEndingTheLastLineIsNotPartOfTheMatch() throws {
        // Taking it would join the block to whatever follows when it is replaced.
        let file = "one\ntwo\nthree\n"
        let found = try XCTUnwrap(matched("one \ntwo", in: file))

        XCTAssertEqual(found.texts, ["one\ntwo"])
        XCTAssertFalse(found.texts[0].hasSuffix("\n"))
    }

    func testDifferentTextIsNotMatchedHoweverCloseItLooks() throws {
        // The six strategies that are not ported would match some of these by
        // similarity. That is the failure worth avoiding: a patch in the wrong
        // place corrupts a file and reports success.
        let file = "func encode() {\n    return 1\n}\n"

        XCTAssertNil(matched("func decode() {\n    return 1\n}", in: file), "a different name")
        XCTAssertNil(matched("func encode() {\n    return 2\n}", in: file), "a different body")
        XCTAssertNil(matched("func  encode() {", in: file), "whitespace inside a line")
        XCTAssertNil(matched("return 1\nfunc encode() {", in: file), "the right lines, reordered")
    }

    func testAPatternLongerThanTheFileFindsNothing() {
        XCTAssertNil(matched("a\nb\nc\n", in: "a\n"))
    }

    func testAnEmptyPatternFindsNothingRatherThanEverything() {
        // `range(of: "")` is a match at every position, which as an edit means
        // inserting the replacement between every pair of characters.
        XCTAssertNil(matched("", in: "anything"))
    }
}
