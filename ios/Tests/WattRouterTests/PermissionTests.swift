// PermissionTests.swift — the wording, which is the whole of this half.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// There is no behaviour here yet, only what a refusal says, and that is worth
// testing precisely because it looks like it is not. These strings are read by a
// model deciding what to do next and by a person deciding where to tap. A
// refusal that does not name the row sends them looking; a restriction that
// names one sends them looking for a switch that does not exist; and a
// copy-pasted row sends them to the wrong screen while reading correctly.

import Foundation
import XCTest

@testable import WattRouter

final class PermissionTests: XCTestCase {
    func testARefusalNamesTheRowInSettings() {
        // "Grant it in Settings" is advice nobody can act on.
        let said = PermissionError.refused(.calendar).localizedDescription
        XCTAssertTrue(said.contains("Settings > Privacy & Security > Calendars"), said)
    }

    func testARefusalSaysAskingAgainDoesNothing() {
        // The system shows its prompt once. A model told only "refused" tries
        // again next turn, and the person sees nothing happen for ever.
        let said = PermissionError.refused(.contacts).localizedDescription
        XCTAssertTrue(said.contains("Asking again shows"), said)
    }

    func testARestrictionDoesNotSendAnybodyToSettings() {
        // Policy rather than a choice. There is no switch to find.
        let said = PermissionError.unavailable(.location).localizedDescription
        XCTAssertTrue(said.contains("cannot be granted"), said)
        XCTAssertFalse(said.contains("Settings"), said)
    }

    func testADismissedPromptDoesNotReadAsARefusal() {
        // Telling somebody to undo a choice they never made is worse than
        // saying nothing, so this case names neither Settings nor a refusal.
        let said = PermissionError.unanswered(.reminders).localizedDescription
        XCTAssertTrue(said.contains("dismissed"), said)
        XCTAssertFalse(said.contains("Settings"), said)
        XCTAssertFalse(said.contains("refused"), said)
    }

    func testEveryCapabilityNamesItsOwnRow() {
        // The realistic mistake is a copy-paste: two capabilities sharing a row,
        // which reads correctly and sends the person to the wrong screen.
        let rows = Capability.allCases.map(\.settings)
        XCTAssertEqual(
            Set(rows).count, Capability.allCases.count,
            "two capabilities share a row in Settings: \(rows)")

        for capability in Capability.allCases {
            XCTAssertTrue(
                capability.settings.hasPrefix("Settings > "),
                "\(capability) does not name a path a person can follow")
            XCTAssertFalse(capability.subject.isEmpty)
        }
    }

    func testEverySubjectIsItsOwn() {
        let subjects = Capability.allCases.map(\.subject)
        XCTAssertEqual(
            Set(subjects).count, Capability.allCases.count,
            "two capabilities describe themselves the same way: \(subjects)")
    }
}
