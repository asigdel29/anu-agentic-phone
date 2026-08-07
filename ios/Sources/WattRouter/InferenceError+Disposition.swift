// InferenceError+Disposition.swift — what a response status means for a chain.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   InferenceError.Disposition  Answer, retry, or stop.
//
// `Upstream::forward` settles this inside a match arm on the request path
// (upstream.rs:146): a server error is worth another model, a client error is
// not, "since the next model would reject the same body identically". Here the
// loop and the client are separate layers, so the rule has to be something a
// caller can ask rather than something a function quietly does.
//
// A function of the status alone, which is all the rule needs and what lets a
// test sweep the whole space instead of sampling it. Boundaries are where a rule
// like this goes wrong, and 399 and 400 are one apart and mean opposite things.

extension InferenceError {
    /// What a response head means for a chain still holding models it has not
    /// tried.
    public enum Disposition: Equatable, Sendable {
        /// A success. The body is the answer and is the caller's to read.
        case answer
        /// A failure another model may not share. Worth trying the next.
        case retry
        /// A failure every model behind the same API would repeat.
        case stop
    }

    /// Classify a response head the way `Upstream::forward` does.
    ///
    /// - Parameter status: an HTTP status code.
    /// - Returns: `.answer` for 2xx, `.stop` for 4xx, `.retry` for anything else.
    ///
    /// A 3xx reaches a caller only when redirects were exhausted or refused,
    /// which is a reachability problem and belongs with the server errors. A 1xx
    /// never surfaces at all, and is classified the same way rather than given a
    /// case nothing can produce.
    ///
    /// The server's stated reason does not hold for every 4xx — a 404 for a model
    /// this account cannot see, or a 429 for one that is merely busy, might well
    /// be answered by the next model in the chain. The behaviour matches the
    /// server anyway, and deliberately: a router and its client disagreeing about
    /// when to fall back is worse than either rule on its own. 401 is the case
    /// that settles which way to be wrong, since a bad credential retried down a
    /// chain is one mistake spent three times.
    public static func disposition(ofStatus status: Int) -> Disposition {
        switch status {
        case 200..<300: .answer
        case 400..<500: .stop
        default: .retry
        }
    }
}
