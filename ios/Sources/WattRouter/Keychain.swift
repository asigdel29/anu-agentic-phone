// Keychain.swift — where the one secret lives.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   Keychain  Read, write and remove a stored string.
//
// The app holds exactly one secret — the provider credential — and every request
// that leaves the device carries it. It is not in a file, not in `UserDefaults`
// and not in the app bundle, because all three are readable from a backup and the
// first two are readable by anything that gets a foothold in the container.
//
// The accessibility class is the decision worth arguing about, and it is
// `AfterFirstUnlockThisDeviceOnly`.
//
// `WhenUnlocked` would be tighter and is wrong here: a turn that goes to the
// background gets about a minute to finish, and a screen that locks during it
// would take the credential away mid-request. `AfterFirstUnlock` keeps it
// readable from the reboot's first unlock onwards, which is as long as any turn
// lives.
//
// `ThisDeviceOnly` is the half that matters more. Without it the item travels in
// an encrypted backup and restores onto another phone, so a credential the owner
// believes is on one device is silently on two. Signing in again costs a paste.

import Foundation
import Security

/// A named string in the Keychain.
public enum Keychain {
    /// Everything this app stores lives under one service, so `delete` on the
    /// service removes all of it and nothing has to keep a list.
    public static let service = "com.getlora.wattrouter"

    /// The stored value, or `nil` if there is none.
    ///
    /// Absence and failure are one answer on purpose. A caller has the same move
    /// either way — ask the person for the credential — and a `Keychain` error
    /// code shown to somebody who has not signed in explains nothing.
    public static func read(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
            let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Store `value`, replacing whatever was there.
    ///
    /// - Returns: whether it was stored. A caller that ignores this has told
    ///   somebody their credential was saved when it may not have been.
    @discardableResult
    public static func write(_ value: String, to account: String) -> Bool {
        // Delete then add, rather than update-or-add. `SecItemUpdate` returns
        // `errSecItemNotFound` on an absent item, so the two-branch version has
        // to handle the item appearing between the check and the write — and
        // that race is exactly the one a second sign-in creates.
        delete(account)

        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    /// Remove the stored value.
    ///
    /// - Returns: whether the Keychain is now without it, which includes it never
    ///   having been there. Signing out twice is not a failure.
    @discardableResult
    public static func delete(_ account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
