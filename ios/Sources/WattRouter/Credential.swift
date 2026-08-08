// Credential.swift — putting the provider key somewhere the core can find it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Credential  Storing one, and the two ways a stored one is unusable.
//
// `Startup.router()` reads the Keychain and nothing ever wrote to it, so a fresh
// install could not leave its fallback screen. This is the writer.
//
// It is a type rather than a call for one reason: a pasted key carries a trailing
// newline more often than not, and stored with it every request is signed with a
// credential the provider does not recognise. The failure is a 401 with nothing
// to point at, and the whitespace is invisible in every place a person would look
// for it. So trimming is the behaviour, and it is tested.

import Foundation

/// The provider credential, as the app stores it.
public enum Credential {
    /// Why a credential could not be stored.
    public enum Failure: LocalizedError, Equatable, Sendable {
        /// Nothing, or nothing but whitespace.
        case empty
        /// The Keychain refused to write it.
        case notStored

        public var errorDescription: String? {
            switch self {
            case .empty:
                "there is nothing there to save."
            case .notStored:
                "the key could not be saved to the keychain, so it would be gone on the next launch."
            }
        }
    }

    /// Store a key, trimmed.
    ///
    /// - Parameter typed: what the person typed or pasted, whitespace and all.
    /// - Throws: [`Failure`].
    public static func store(_ typed: String) throws {
        let key = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw Failure.empty }
        guard Keychain.write(key, to: Startup.account) else { throw Failure.notStored }
    }

    /// Whether one is stored. Read rather than remembered: the Keychain outlives
    /// the process and a flag in the app would not.
    public static var isStored: Bool {
        Keychain.read(Startup.account)?.isEmpty == false
    }

    /// Forget it, for signing out and for a test that must not leave one behind.
    @discardableResult
    public static func forget() -> Bool {
        Keychain.delete(Startup.account)
    }
}
