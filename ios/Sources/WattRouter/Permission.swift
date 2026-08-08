// Permission.swift — what the system has to agree to, and how a refusal reads.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   Capability       Something the system must agree to.
//   PermissionState  What the system currently says about one.
//   Authorizer       The seam to whichever framework owns that answer.
//   PermissionError  Why a tool could not go on, written for the model.
//
// #137 states the rule: permission is a turn's problem rather than a launch's.
// Asking at first launch trains people to refuse, so nothing here runs before a
// tool needs it.
//
// The reason this is a shared vocabulary rather than a switch inside each of the
// six tools that are coming is the distinction each copy would have to make
// separately. Refused is not unavailable. A refusal is a choice and the person
// can undo it; a restriction is policy and they cannot, so advice to go and
// grant it spends a turn looking for a switch that is not there. And neither is
// unanswered, which is what a dismissed prompt leaves behind — telling somebody
// to undo a choice they never made is worse than saying nothing.
//
// Nothing here imports a system framework. The wording is the part worth testing
// and it is testable against nothing at all; the conformances arrive with the
// tools that need them.

import Foundation

/// Something the system must agree to before a tool can do its work.
public enum Capability: String, Sendable, CaseIterable {
    case calendar
    case reminders
    case contacts
    case location

    /// What is being asked for, in the words a person would use. This reaches
    /// the model, so it names the thing rather than the framework that owns it.
    public var subject: String {
        switch self {
        case .calendar: "the calendar"
        case .reminders: "reminders"
        case .contacts: "contacts"
        case .location: "location"
        }
    }

    /// Where a person changes their mind, exactly as the screen spells it.
    ///
    /// Part of the refusal the model reads. "Grant it in Settings" is advice
    /// nobody can act on; this is the row they are looking for.
    public var settings: String {
        switch self {
        case .calendar: "Settings > Privacy & Security > Calendars"
        case .reminders: "Settings > Privacy & Security > Reminders"
        case .contacts: "Settings > Privacy & Security > Contacts"
        case .location: "Settings > Privacy & Security > Location Services"
        }
    }
}

/// What the system currently says about a capability.
public enum PermissionState: Sendable, Equatable {
    /// The tool may go ahead.
    case granted
    /// The person said no. Only Settings changes it.
    case refused
    /// Policy, not a choice. The person cannot grant it either.
    case unavailable
    /// Nobody has been asked yet.
    case unasked
}

/// The seam to whichever framework owns the answer.
///
/// # Rely
/// `request` is called only when `state` said `unasked`, and at most once per
/// capability for the life of whatever is driving it. A conformance may show the
/// system's prompt in it without guarding against a second one.
public protocol Authorizer: Sendable {
    /// What the system says now, without showing anything.
    func state(of capability: Capability) async -> PermissionState

    /// Show the prompt and wait for the answer.
    func request(_ capability: Capability) async -> PermissionState
}

/// Why a tool could not go on.
///
/// `LocalizedError`, because `ToolBox` reports `localizedDescription` and the
/// default throws away everything that makes this actionable. Each case says
/// what was refused and what to do about it, which is the contract
/// `WorkspaceError` already keeps: a refusal that does not name the boundary
/// leaves the model to guess, and it guesses the same thing twice.
public enum PermissionError: LocalizedError, Equatable, Sendable {
    /// The person said no.
    case refused(Capability)
    /// The device does not allow it, and the person cannot change that.
    case unavailable(Capability)
    /// The prompt went away without an answer.
    case unanswered(Capability)

    public var errorDescription: String? {
        switch self {
        case .refused(let capability):
            """
            access to \(capability.subject) was refused. Asking again shows \
            nothing — it is changed in \(capability.settings). Carry on without \
            it, or say that is where it is turned back on.
            """
        case .unavailable(let capability):
            """
            access to \(capability.subject) is not available on this device and \
            cannot be granted. This is a restriction rather than a choice, so \
            there is nothing to ask for. Carry on without it.
            """
        case .unanswered(let capability):
            """
            the request for \(capability.subject) was dismissed without an \
            answer. Nothing more can be shown this turn. Carry on without it, \
            or ask whether to try again later.
            """
        }
    }
}
