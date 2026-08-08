// ThemeTests.swift — the palette is readable, and the formula says so.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// These assert against the WCAG thresholds rather than against the ratios
// somebody wrote down. #136 recorded 15.65:1 for signal on ground and 5.89:1 for
// error; the formula gives 16.55:1 and 6.23:1. Asserting the recorded numbers
// would have made this file agree with the mistake, so it asserts what the
// standard asks for and prints what the palette actually achieves.

import Foundation
import XCTest

@testable import WattRouter

final class ThemeTests: XCTestCase {
    /// WCAG AA for body text.
    private let aa = 4.5
    /// WCAG AAA for body text.
    private let aaa = 7.0

    func testSignalCarriesBodyTextAtTheHighestGrade() {
        let ratio = contrastRatio(Theme.signal, on: Theme.ground)
        XCTAssertGreaterThanOrEqual(
            ratio, aaa, "signal on ground is \(ratio):1, below AAA")
        print("theme: signal on ground \(String(format: "%.2f", ratio)):1")
    }

    func testCyanIsASecondVoiceRatherThanAQuieterOne() {
        // Secondary in role, not in legibility: a colour used for anything a
        // person reads has to clear the same bar as the one they read most.
        let ratio = contrastRatio(Theme.cyan, on: Theme.ground)
        XCTAssertGreaterThanOrEqual(
            ratio, aaa, "cyan on ground is \(ratio):1, below AAA")
        print("theme: cyan on ground \(String(format: "%.2f", ratio)):1")
    }

    func testErrorIsReadableWhereItMattersMost() {
        // AA rather than AAA, and deliberately: the hue has to read as wrong at a
        // glance, and the reds that clear AAA on near-black are pink enough to
        // stop doing that. A message nobody recognises as an error is worse than
        // one a shade under the highest grade.
        let ratio = contrastRatio(Theme.error, on: Theme.ground)
        XCTAssertGreaterThanOrEqual(
            ratio, aa, "error on ground is \(ratio):1, below AA")
        print("theme: error on ground \(String(format: "%.2f", ratio)):1")
    }

    func testTheRecordedRatiosWereWrongAndTheseAreTheRealOnes() {
        // Pinned so that a change to a hex value is a change to this file too.
        // The numbers are the formula's, not the tracking issue's.
        XCTAssertEqual(contrastRatio(Theme.signal, on: Theme.ground), 16.55, accuracy: 0.01)
        XCTAssertEqual(contrastRatio(Theme.error, on: Theme.ground), 6.23, accuracy: 0.01)
        XCTAssertEqual(contrastRatio(Theme.cyan, on: Theme.ground), 14.21, accuracy: 0.01)
    }

    func testTheFormulaAgreesWithItsOwnEndpoints() {
        // Black on white is 21:1 and a colour on itself is 1:1. Both are fixed
        // points of the definition, so a formula that misses either is wrong in a
        // way the palette's own numbers would not reveal.
        let white = Theme.Ink(red: 1, green: 1, blue: 1)
        let black = Theme.Surface(red: 0, green: 0, blue: 0)
        XCTAssertEqual(contrastRatio(white, on: black), 21.0, accuracy: 0.001)

        let sameColour = Theme.Surface(
            red: Theme.signal.red, green: Theme.signal.green, blue: Theme.signal.blue)
        XCTAssertEqual(contrastRatio(Theme.signal, on: sameColour), 1.0, accuracy: 0.001)
    }
}
