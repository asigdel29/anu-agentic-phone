// CNContacts.swift — the contacts seam, against the framework that owns it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   CNContactsAuthorizer  Address book access, asked for and read.
//   CNContacts            People out of the store, as the tool's own type.
//
// Actors, as the EventKit pair are, and for the same reason: CNContactStore is
// not Sendable, and actor isolation is the version of that claim the compiler
// checks. Neither shares a store with the other — a value in two isolation
// domains is what the isolation is being used to rule out.
//
// Two things the framework does that EventKit does not, both of which decide
// code here rather than being trivia.
//
// Access is one tier rather than two. There is no write-only contacts access, so
// there is no equivalent of the calendar's `writeOnly`-is-not-enough case. What
// takes its place is `limited`, added in iOS 18: the person picked some people
// and not others. That is not a refusal — a search against it returns the subset
// they allowed — so it reads as granted, and the alternative would be an app
// that refuses to look at contacts somebody deliberately shared.
//
// And a contact only carries the keys it was fetched with. Reading one that was
// not requested raises rather than returning nothing, so the key set and the
// mapping below have to agree. That is a crash on a device rather than a warning
// in review, which is why they sit next to each other.

import Contacts
import Foundation

/// Address book access.
public actor CNContactsAuthorizer: Authorizer {
    private let store: CNContactStore

    public init(store: CNContactStore = CNContactStore()) {
        self.store = store
    }

    /// The framework's answer, as this app's.
    ///
    /// Static and over the raw value, so the whole mapping is reachable from a
    /// simulator — the pattern `EventKitAuthorizer.state(from:)` set.
    ///
    /// `limited` is the case worth reading twice. The person chose some contacts
    /// to share; a search returns those and no error. Refusing it would be this
    /// app declining to look at what somebody deliberately handed it.
    ///
    /// An unknown case is unasked rather than granted or refused. The framework
    /// grew `limited` after this app's floor, and the safe default is the one
    /// that asks.
    static func state(from status: CNAuthorizationStatus) -> PermissionState {
        switch status {
        case .authorized, .limited: .granted
        case .denied: .refused
        case .restricted: .unavailable
        case .notDetermined: .unasked
        @unknown default: .unasked
        }
    }

    public func state(of capability: Capability) async -> PermissionState {
        guard capability == .contacts else { return .unavailable }
        return Self.state(from: CNContactStore.authorizationStatus(for: .contacts))
    }

    public func request(_ capability: Capability) async -> PermissionState {
        guard capability == .contacts else { return .unavailable }
        // The returned flag says granted or not; the status says which kind of
        // not, and the kind is what the person is told about. Same reasoning as
        // the EventKit authorizer, and the same discard.
        _ = try? await store.requestAccess(for: .contacts)
        return Self.state(from: CNContactStore.authorizationStatus(for: .contacts))
    }
}

/// People out of the store.
public actor CNContacts: Contacts {
    /// Exactly what the mapping below reads, and nothing else.
    ///
    /// A contact carries only the keys it was fetched with, and reading one that
    /// was not requested raises. Adding a field to `Contact` without adding its
    /// key here is a crash on somebody's phone, so the two are kept adjacent.
    ///
    /// Computed rather than stored: `CNKeyDescriptor` is not `Sendable`, so a
    /// static holding one is shared mutable state the compiler will not allow.
    /// Rebuilding three descriptors per search costs nothing next to the fetch.
    private static var keys: [any CNKeyDescriptor] {
        [
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
            CNContactPhoneNumbersKey as CNKeyDescriptor,
            CNContactEmailAddressesKey as CNKeyDescriptor,
        ]
    }

    private let store: CNContactStore

    public init(store: CNContactStore = CNContactStore()) {
        self.store = store
    }

    /// A label as somebody would say it.
    ///
    /// The framework stores these as `_$!<Mobile>!$_`, and handing that to a
    /// model is handing it a token it will repeat back. An empty label stays
    /// empty rather than becoming "other": what a label says is the address
    /// book's to report, and inventing one puts a word nobody chose in front of
    /// the model.
    static func label(_ raw: String?) -> String {
        guard let raw, !raw.isEmpty else { return "" }
        return CNLabeledValue<NSString>.localizedString(forLabel: raw)
    }

    /// # Rely
    /// The contacts capability has been obtained. Without it the store raises
    /// rather than answering empty, which is why this is not a silent no-match.
    public func matching(_ name: String) async throws -> [Contact] {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }

        let found = try store.unifiedContacts(
            matching: CNContact.predicateForContacts(matchingName: trimmed),
            keysToFetch: Self.keys)

        return found.map(Self.read)
    }

    /// One contact, as this app's type.
    private static func read(_ contact: CNContact) -> Contact {
        Contact(
            // The formatter rather than given-plus-family: a company has one
            // name, and so do some people.
            name: CNContactFormatter.string(from: contact, style: .fullName) ?? "(no name)",
            numbers: contact.phoneNumbers.map {
                Contact.Labelled(label: label($0.label), value: $0.value.stringValue)
            },
            emails: contact.emailAddresses.map {
                Contact.Labelled(label: label($0.label), value: $0.value as String)
            })
    }
}
