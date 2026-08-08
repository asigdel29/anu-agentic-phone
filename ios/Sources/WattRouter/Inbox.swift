// Inbox.swift — what another app handed in, waiting for the app to run.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Inbox  Writing into a shared container, and draining it.
//
// A share extension is a separate process with its own sandbox. It cannot call
// into the app, and the app is usually not running when somebody shares
// something. So the two halves talk through a container they both have, and this
// is what they say — the extension itself is a view controller and a plist.
//
// Three things, and each is a way to lose somebody's text.
//
// A write is atomic. The extension can be killed the moment its sheet is
// dismissed, and a half-written file read on next launch is a truncated note.
// #125 argued a temporary and a rename for the file tools; here the reader is a
// different process, which makes it sharper rather than the same.
//
// A read removes. Anything else shows the same shared link at every launch.
//
// And order survives. Three things shared in a row and read back in another
// order is a conversation nobody had, and the filesystem promises nothing about
// directory order — so the order is in the names rather than in the directory.

import Foundation

/// What another app handed in.
public struct Inbox: Sendable {
    /// The App Group both halves share.
    ///
    /// Written once here rather than in each target's entitlements *and* in the
    /// code that reads them, which is two places to get one string right.
    public static let group = "group.com.getlora.wattrouter"

    /// Where the shared container is, or `nil` when the group is not
    /// provisioned.
    ///
    /// Nil is the honest answer and both halves have to act on it. An extension
    /// that silently writes nowhere loses somebody's text; an app that silently
    /// reads nowhere shows nothing and looks correct.
    public static var container: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
    }

    /// Where both processes can reach. On a phone this is the App Group
    /// container; in a test it is a temporary directory, which is the whole
    /// reason it is a parameter.
    private let container: URL

    public init(container: URL) {
        self.container = container
    }

    /// The directory items live in, made if it is not there.
    ///
    /// A fresh install can have something shared into it before the app has ever
    /// run, so the extension creates this rather than assuming the app did.
    private var directory: URL { container.appending(path: "inbox") }

    /// Put one in.
    ///
    /// - Parameters:
    ///   - text: what was shared.
    ///   - at: when, which becomes the order it comes back in.
    /// - Throws: whatever the filesystem refused. A share that cannot be written
    ///   must fail visibly in the extension rather than vanish.
    ///
    /// # Atomic
    /// Written to a temporary and renamed, so a reader in another process sees
    /// the whole thing or nothing at all.
    public func write(_ text: String, at: Date) throws {
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true)

        // The instant, then a unique tail. Two things shared in the same
        // millisecond are rare and would otherwise be one file: the second
        // silently replacing the first.
        let name = String(format: "%015.4f-%@", at.timeIntervalSince1970, UUID().uuidString)
        let landing = directory.appending(path: name)
        let temporary = directory.appending(path: ".\(name).partial")

        try Data(text.utf8).write(to: temporary)
        try FileManager.default.moveItem(at: temporary, to: landing)
    }

    /// Take everything out, oldest first.
    ///
    /// - Returns: what was shared, in the order it was shared. Empty when there
    ///   is nothing, which is the ordinary case at almost every launch.
    ///
    /// # Atomic
    /// Not atomic as a whole. Each item is removed after it is read, so a crash
    /// midway loses the ones already returned and keeps the rest — which is the
    /// half worth keeping.
    public func drain() -> [String] {
        let names =
            (try? FileManager.default.contentsOfDirectory(atPath: directory.path(percentEncoded: false)))
            ?? []

        var taken: [String] = []
        // Sorted by name, which is sorted by time because the name begins with
        // it, zero-padded so that "9" sorts before "10". Directory order is not
        // an order.
        for name in names.sorted() where !name.hasPrefix(".") {
            let item = directory.appending(path: name)
            guard let contents = try? String(contentsOf: item, encoding: .utf8) else { continue }
            taken.append(contents)
            try? FileManager.default.removeItem(at: item)
        }
        return taken
    }
}
