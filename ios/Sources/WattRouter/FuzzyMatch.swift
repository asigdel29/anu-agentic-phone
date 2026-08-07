// FuzzyMatch.swift — finding a block the model half-remembered.
//
// History
//   2026-08-07  A. Sigdel  Created, from tools/fuzzy_match.py.
//
// Contents
//   FuzzyMatch  Where a block of text occurs, allowing for near misses.
//
// The text to replace is written from memory and is usually not byte-identical
// to the file. The Python tries nine strategies; this tries three, and which six
// are missing is a decision rather than an omission.
//
// `whitespace-normalized`, `block-anchor` — first and last line, then a 50 to 70
// per cent similarity score — and `context-aware`, at 50 per cent per line, can
// each match a region that is not the one the model meant. That is a different
// class of failure from not matching at all: a patch landing in the wrong place
// corrupts a file *and* reports success, and the model carries on believing the
// edit is where it asked for it. Not matching costs a turn and says so.
//
// The three that are here cannot land somewhere else. Each ignores a kind of
// whitespace and nothing else, so a match under any of them is the same lines in
// the same order.
//
// The other three in the Python — escape-normalized, unicode-normalized,
// trimmed-boundary — are defensible and are simply not here yet.

import Foundation

/// Where a block of text occurs in a file.
public enum FuzzyMatch {
    /// How a match was found. Reported so a caller can tell the model what it
    /// actually matched against, which is the difference between an edit it can
    /// trust and one it should re-read.
    public enum Strategy: String, CaseIterable, Sendable {
        /// Byte for byte.
        case exact
        /// Ignoring trailing whitespace on each line.
        case lineTrimmed = "line-trimmed"
        /// Ignoring leading whitespace too, so a block indented differently in
        /// the file than in the pattern still matches.
        case indentationFlexible = "indentation-flexible"
    }

    /// What was found.
    public struct Found: Equatable, Sendable {
        /// Every occurrence, in order, non-overlapping.
        public let ranges: [Range<String.Index>]
        /// Which strategy produced them. All of them came from one: mixing
        /// strategies would mean two occurrences matched on different terms.
        public let strategy: Strategy
    }

    /// Every occurrence of `pattern` in `content`.
    ///
    /// Strategies are tried in order and the first that finds anything wins, so a
    /// pattern that matches exactly is never also matched loosely somewhere else.
    ///
    /// - Returns: `nil` if nothing matched under any strategy.
    public static func find(_ pattern: String, in content: String) -> Found? {
        guard !pattern.isEmpty else { return nil }

        let exactly = exact(pattern, in: content)
        if !exactly.isEmpty { return Found(ranges: exactly, strategy: .exact) }
        for strategy in [Strategy.lineTrimmed, .indentationFlexible] {
            let ranges = byLine(pattern, in: content, strategy: strategy)
            if !ranges.isEmpty { return Found(ranges: ranges, strategy: strategy) }
        }
        return nil
    }

    /// Plain substring search, which is also the only strategy that can match
    /// part of a line.
    private static func exact(_ pattern: String, in content: String) -> [Range<String.Index>] {
        var ranges: [Range<String.Index>] = []
        var from = content.startIndex
        while let found = content.range(of: pattern, range: from..<content.endIndex) {
            ranges.append(found)
            // From the end of the match, so two occurrences never overlap and a
            // pattern like "aa" in "aaa" is one match rather than two.
            from = found.upperBound
            if from >= content.endIndex { break }
        }
        return ranges
    }

    /// Line-aligned matching under a normalisation.
    private static func byLine(
        _ pattern: String, in content: String, strategy: Strategy
    ) -> [Range<String.Index>] {
        // A pattern almost always ends in a newline and the block it names almost
        // never does; comparing without it is what lets the two meet.
        let wanted = lines(of: trimmingFinalNewline(pattern)).map { normalise($0, strategy) }
        guard !wanted.isEmpty else { return [] }

        let here = lines(of: content)
        guard here.count >= wanted.count else { return [] }

        var ranges: [Range<String.Index>] = []
        var start = 0
        while start <= here.count - wanted.count {
            let window = here[start..<(start + wanted.count)]
            guard zip(window, wanted).allSatisfy({ normalise($0.0, strategy) == $0.1 }) else {
                start += 1
                continue
            }
            // Bounded by the lines themselves, so the newline ending the last one
            // stays in the file: replacing it would join two lines together.
            let first = window.first!.startIndex
            let last = window.last!.endIndex
            ranges.append(first..<last)
            start += wanted.count
        }
        return ranges
    }

    private static func normalise(_ line: Substring, _ strategy: Strategy) -> Substring {
        switch strategy {
        case .exact, .lineTrimmed:
            trimmingTrailingWhitespace(line)
        case .indentationFlexible:
            trimmingTrailingWhitespace(line.drop(while: \.isWhitespace))
        }
    }

    private static func lines(of text: String) -> [Substring] {
        text.split(separator: "\n", omittingEmptySubsequences: false)
    }

    private static func trimmingFinalNewline(_ text: String) -> String {
        text.hasSuffix("\n") ? String(text.dropLast()) : text
    }

    private static func trimmingTrailingWhitespace(_ line: Substring) -> Substring {
        var end = line.endIndex
        while end > line.startIndex {
            let previous = line.index(before: end)
            guard line[previous].isWhitespace else { break }
            end = previous
        }
        return line[line.startIndex..<end]
    }
}
