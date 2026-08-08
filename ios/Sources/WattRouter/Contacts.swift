// Contacts.swift — somebody in the address book, and the seam to whatever holds it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Contact   Somebody, with the ways to reach them.
//   Contacts  The seam to whatever holds them.
//
// No Contacts framework here, as in Calendars.swift and Reminders.swift: the
// conformance is its own change, and holding it back is what makes this testable
// against a stub.
//
// The seam searches rather than lists, and that is the shape rather than a
// convenience. A calendar read is bounded by a range; an address book read is
// bounded by nothing, and a phone with two thousand contacts answers "who do I
// know" with a context window. There is deliberately no way to ask for everyone.
//
// A `Contact` carries the ways to reach somebody rather than everything known
// about them. The question behind "find Dave" is almost always a number or an
// address, and returning a name the model already had is a round trip for
// nothing. Birthdays, job titles and postal addresses are not here because
// nothing has needed them, and each one added is another thing leaving the phone
// in a tool result.
//
// Both lists can be empty and that is not an error: somebody in the address book
// with neither a number nor an email is a real entry, usually a company or a
// half-finished one, and saying "found them, no way to reach them" is the useful
// answer.

import Foundation

/// Somebody, with the ways to reach them.
public struct Contact: Equatable, Sendable {
    /// As the address book renders it, which is not always given plus family:
    /// a company contact has one name, and some people have one name.
    public let name: String
    /// Every number, in the order the address book keeps them. Labelled, because
    /// "mobile" and "work" are the difference between reaching somebody at
    /// eleven at night and not.
    public let numbers: [Labelled]
    /// Every email address, on the same terms.
    public let emails: [Labelled]

    /// One way to reach somebody, and what it is called.
    public struct Labelled: Equatable, Sendable {
        /// `mobile`, `work`, `home`, or whatever somebody typed. Empty where the
        /// address book has none, which it allows.
        public let label: String
        public let value: String

        public init(label: String, value: String) {
            self.label = label
            self.value = value
        }
    }

    public init(name: String, numbers: [Labelled] = [], emails: [Labelled] = []) {
        self.name = name
        self.numbers = numbers
        self.emails = emails
    }

    /// Whether there is any way to reach this person.
    ///
    /// Not an error and worth asking about separately: an entry with neither is
    /// a real one, and a tool that treats it as a failed search sends the model
    /// looking for a person it has already found.
    public var isReachable: Bool { !numbers.isEmpty || !emails.isEmpty }
}

/// The seam to whatever holds the address book.
///
/// # Rely
/// `matching` is called only after the contacts capability has been obtained.
public protocol Contacts: Sendable {
    /// Everybody whose name matches.
    ///
    /// - Parameter name: what to search for, WHERE matching is the address
    ///   book's own — prefix and word matching on the name parts. Nothing fuzzier
    ///   is attempted anywhere above this: `FuzzyMatch` in this tree is for file
    ///   paths, where the candidate set is small and a wrong guess costs a reread.
    ///   Guessing at a person is how a message goes to the wrong one.
    /// - Returns: everybody matching, in the address book's order, with no cap.
    ///   Capping belongs to the tool, which has to say how many it left out.
    func matching(_ name: String) async throws -> [Contact]
}
