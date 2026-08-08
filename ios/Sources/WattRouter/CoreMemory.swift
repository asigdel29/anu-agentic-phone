// CoreMemory.swift — the routing core's memory store, as a Remembering.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   CoreMemory  The store, held open, and protected on disk.
//
// A class, and the only one of the four conformances that has to be. git reopens
// a repository per call because libgit2 is cheap to open; a memory store *is*
// the index, so this holds one for the life of the app and frees it in `deinit`.
// That is a lifetime, and a lifetime needs an identity.
//
// File protection is set here rather than left to the default, because
// docs/decisions/memory-on-a-phone-forgets.md says it has to be deliberate — a
// memory store is the most sensitive thing this app writes.
//
// `.completeUnlessOpen` rather than `.complete`, and the reason is a case rather
// than a preference. `.complete` makes a file unreadable whenever the phone is
// locked, which breaks the thing #276 exists for: an App Intent started by Siri
// on a locked phone could not open the store. `.completeUnlessOpen` keeps a file
// readable if it was opened while unlocked and refuses to open one that was not,
// which is the shape of this problem exactly — the store is opened at launch and
// held. `.completeUntilFirstUserAuthentication` would be weaker than this file
// deserves.
//
// And SQLite writes `-wal` and `-shm` beside the database. Protection set on the
// database alone leaves those two unprotected, which is an unprotected store with
// extra steps. They do not exist until the first write, so the attribute goes on
// the directory too — that is what a new file inherits.

import Foundation
import WattRouterFFI

/// The routing core's memory store, held open.
public final class CoreMemory: Remembering, @unchecked Sendable {
    /// How much history the store is allowed to load at open. Two thousand turns
    /// is about two megabytes of vectors at 256 floats each — see the decision
    /// record for why there is a number here at all.
    public static let horizon = 2000

    private let handle: OpaquePointer

    /// Bound the store, open it, and protect what it wrote.
    ///
    /// - Parameters:
    ///   - path: the database. Its directory is created if absent.
    ///   - keep: how many recent turns to leave in front of the horizon.
    /// - Returns: `nil` if the store could not be opened, which nothing above
    ///   this can fix — the app runs without memory rather than not at all.
    public init?(path: URL, keep: Int = CoreMemory.horizon) {
        try? FileManager.default.createDirectory(
            at: path.deletingLastPathComponent(), withIntermediateDirectories: true)

        // Before opening, so the database is created inside a directory that
        // already carries the attribute rather than being protected afterwards.
        Self.protect(path.deletingLastPathComponent())

        let opened = path.path(percentEncoded: false).withCString {
            wattrouter_memory_open($0, keep)
        }
        guard let opened else { return nil }
        self.handle = opened

        Self.protectStore(at: path)
    }

    deinit { wattrouter_memory_free(handle) }

    public func remember(
        _ text: String, speaker: String, session: String, at: Date
    ) throws(MemoryError) -> Int64 {
        try read(
            session.withCString { session in
                speaker.withCString { speaker in
                    text.withCString { text in
                        wattrouter_memory_remember(
                            handle, session, speaker, text,
                            Int64(at.timeIntervalSince1970))
                    }
                }
            })
    }

    public func recall(_ query: String, most: Int) throws(MemoryError) -> Recollection {
        try read(
            query.withCString { query in
                wattrouter_memory_recall(handle, query, most)
            })
    }

    /// Take ownership of what an entry point returned, and read it.
    private func read<Value: Decodable>(
        _ returned: UnsafeMutablePointer<CChar>?
    ) throws(MemoryError) -> Value {
        guard let returned else { throw .unanswered }
        defer { wattrouter_string_free(returned) }
        return try CoreAnswer<Value>.value(
            from: Data(String(cString: returned).utf8), failing: MemoryError.self)
    }

    /// Every file a store is, which is three rather than one.
    ///
    /// Named rather than globbed: `-wal` and `-shm` are the two SQLite makes, and
    /// a glob over the directory would also protect whatever else lives there,
    /// which is not this type's to decide.
    ///
    /// Separate from applying the attribute because *which files* is the half
    /// that gets forgotten and the half a simulator can check — data protection
    /// itself is a no-op there.
    static func files(of store: URL) -> [URL] {
        let beside = store.deletingLastPathComponent()
        return [store]
            + ["-wal", "-shm"].map {
                beside.appending(path: store.lastPathComponent + $0)
            }
    }

    /// Protect the database and everything SQLite wrote beside it.
    static func protectStore(at path: URL) {
        files(of: path).forEach(protect)
    }

    /// Set protection on one item, if it is there.
    ///
    /// Silent when it is not: `-wal` and `-shm` do not exist until the first
    /// write, and the directory's attribute is what the eventual files inherit.
    private static func protect(_ item: URL) {
        guard FileManager.default.fileExists(atPath: item.path(percentEncoded: false)) else {
            return
        }
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: item.path(percentEncoded: false))
    }
}
