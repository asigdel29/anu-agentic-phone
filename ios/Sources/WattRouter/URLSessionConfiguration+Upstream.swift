// URLSessionConfiguration+Upstream.swift — how this stack holds a connection open.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   URLSessionConfiguration.upstream  The transport policy `upstream.rs` specifies.
//
// Separate from the client that uses it because it is a policy rather than a
// mechanism, and because the one decision in it is the one most easily made
// backwards.
//
// `Upstream::new` sets two timeouts and they are not two spellings of the same
// idea:
//
//   upstream.rs:64  .timeout(30 min)      a budget for the whole request
//   upstream.rs:65  .read_timeout(2 min)  how long the wire may stay silent
//
// The comment there says why the pair exists: "a heavy-tier response legitimately
// takes minutes; the read timeout is what catches a genuinely dead connection".
// One number cannot do both jobs, because a long answer and a dead socket look
// identical from a distance and differ only in whether anything is still
// arriving.
//
// `URLSession` spells them `timeoutIntervalForResource` and
// `timeoutIntervalForRequest`, and the names are a trap. The one named for the
// *request* is the *idle* timeout — Apple documents it as the wait for additional
// data — while the one named for the *resource* is the whole-request budget.
// Mapped by their names, both are wrong at once: a legitimate heavy-tier answer
// is cut off after two minutes, and a connection nobody is writing to is held for
// thirty.

import Foundation

extension URLSessionConfiguration {
    /// How long the wire may stay silent before the attempt is a lost cause.
    /// `read_timeout` at upstream.rs:65.
    public static let upstreamSilenceLimit: TimeInterval = 120

    /// The budget for a whole answer, however slowly it arrives. `timeout` at
    /// upstream.rs:64, and below the provider's own ceiling so that whatever
    /// gives up first does so with a reason.
    public static let upstreamAnswerLimit: TimeInterval = 1800

    /// The transport policy for talking to the provider.
    ///
    /// - Returns: a fresh configuration. Mutable by the caller, which is how a
    ///   test installs a stubbed `URLProtocol` without needing a second factory.
    public static func upstream() -> URLSessionConfiguration {
        // Ephemeral: nothing here wants a cookie jar or a disk cache, and every
        // request carries a bearer token that has no business being written down.
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = upstreamSilenceLimit
        configuration.timeoutIntervalForResource = upstreamAnswerLimit

        // A turn is a person waiting. Queueing behind a lost network until it
        // comes back is not an answer, and a chain has somewhere else to go.
        configuration.waitsForConnectivity = false

        // `Upstream::new` also sets `pool_idle_timeout` and
        // `pool_max_idle_per_host`, and neither is set here because neither
        // exists: `URLSession` keeps its own connection pool and exposes no knob
        // for how long an idle connection survives. Guessing at a mapping —
        // `httpMaximumConnectionsPerHost` is the nearest-looking one and means
        // something else entirely — would be a setting with a comment claiming a
        // purpose it does not have.
        return configuration
    }
}
