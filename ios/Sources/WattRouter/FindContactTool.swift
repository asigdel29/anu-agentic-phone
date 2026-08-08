// FindContactTool.swift — looking somebody up.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   FindContactTool  Finding somebody in the address book, as a tool.
//
// Three answers, and the wrong version of each is what this file is about.
//
// No match says what it searched for. "No contact called Dave" sends a model to
// try "David"; "no results" sends it to try "Dave" again.
//
// One match gives the numbers and the addresses, because that is what the
// question was — a name the model already had is a round trip for nothing. And
// somebody with neither is still found: the entry exists, looking again will not
// help, and "found them, no way to reach them" is what the model needs to say.
//
// Many matches stop. Five Daves listed is an answer; fifty is not, because a
// model handed fifty picks the first and the wrong Dave is how a message reaches
// somebody it should not. Past the cap this says how many and asks for a
// surname, rather than showing a prefix of them and letting the model choose out
// of an arbitrary slice.

import Foundation

/// Find somebody in the address book.
public struct FindContactTool: Tool {
    /// Past this, the answer is a question rather than a list. Small on purpose:
    /// a model choosing between more than a handful of people is guessing.
    public static let limit = 8

    public let name = "find_contact"

    public let purpose = """
        Find somebody in the address book by name, and get their phone numbers \
        and email addresses. Matching is the address book's own — a first name, \
        a surname, or both. If several people match you will be told who they \
        are and asked to narrow it; ask the person which one rather than \
        guessing.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {
            "name": {"type": "string", "description": "Who to look for."}
          },
          "required": ["name"]
        }
        """

    private let contacts: any Contacts
    private let permission: Permission

    public init(contacts: any Contacts, permission: Permission) {
        self.contacts = contacts
        self.permission = permission
    }

    /// - Returns: who was found and how to reach them, or what to do instead.
    ///
    /// # Rely
    /// Nothing. The permission is obtained here, after the arguments are read.
    public func run(arguments: Data) async throws -> String {
        let request = try JSONDecoder().decode(Request.self, from: arguments)

        // Before the prompt. An empty search would match everybody, and spending
        // the one chance to ask for contacts on it spends it for good.
        let name = request.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            return "no name was given to search for, so nothing was looked up"
        }

        try await permission.obtain(.contacts)
        let found = try await contacts.matching(name)

        guard !found.isEmpty else {
            // The search term, so a model that guessed a spelling can guess
            // another rather than repeating the first.
            return "no contact matching \"\(name)\". Try another spelling, or a surname"
        }
        guard found.count <= Self.limit else {
            return """
                \(found.count) contacts match "\(name)", which is too many to \
                choose between. Ask for a surname, or a more complete name
                """
        }
        return found.map { describe($0) }.joined(separator: "\n")
    }

    /// One person, and the ways to reach them.
    private func describe(_ contact: Contact) -> String {
        guard contact.isReachable else {
            // Found, and a dead end. Said plainly so the model stops looking
            // rather than searching again for somebody it has already got.
            return "\(contact.name) — no phone number or email address on file"
        }

        let ways = (contact.numbers + contact.emails).map { way in
            way.label.isEmpty ? way.value : "\(way.value) (\(way.label))"
        }
        return "\(contact.name) — \(ways.joined(separator: ", "))"
    }

    private struct Request: Decodable {
        let name: String
    }
}
