// Router.swift — the routing core, as Swift sees it.
//
// History
//   2026-08-06  A. Sigdel  Created.
//
// Contents
//   Tier      A routing role.
//   Reason    Why a tier was chosen.
//   Decision  A tier, its reason, and the score behind it.
//   Router    The core, owning its pointer.
//
// The C API hands back two integers and a float, where 255 means failure and -1
// means unscored. A call site that forgot either would route on a sentinel, so
// these types make both unrepresentable: the codes are enumerations that fail to
// construct from a sentinel, and the score is an Optional. The pointer belongs to
// a class, so leaking it and using it after free — the only two mistakes the C
// API allows — are not reachable from Swift at all.

import WattRouterFFI

/// A routing tier: a role, not a model name. Ordered by capability, and the
/// order is load-bearing — a session may be raised but never quietly dropped.
public enum Tier: UInt8, CaseIterable, Comparable, Sendable {
    /// Background work: titles, summaries, compaction. Never user-facing.
    case aux
    /// Lookups, short answers, chat.
    case cheap
    /// The working default: tool calls and structured output.
    case mid
    /// Code-shaped work below the heavy threshold.
    case code
    /// Contexts too large for any other tier.
    case long
    /// Architecture, multi-file reasoning, debugging.
    case heavy

    /// The tier's stable name, as configuration and metrics spell it.
    ///
    /// Read from the core rather than written out here: a second copy of the
    /// vocabulary is a copy that falls behind the first.
    public var name: String {
        String(cString: wattrouter_tier_name(rawValue))
    }

    public static func < (a: Tier, b: Tier) -> Bool { a.rawValue < b.rawValue }
}

/// Why a tier was chosen. A decision that cannot be explained cannot be debugged.
public enum Reason: UInt8, CaseIterable, Sendable {
    /// The caller named the tier.
    case pinned
    /// Housekeeping, not a person waiting.
    case background
    /// Nothing else can hold the context.
    case contextTooLarge
    /// The score selected the band.
    case scored
    /// Code-shaped work, promoted out of the middle band.
    case codeShaped
    /// No score was available.
    case unscored
    /// The session had already settled on a higher tier.
    case sticky

    /// The reason's stable name, as metrics spell it. Read from the core.
    public var name: String {
        String(cString: wattrouter_reason_name(rawValue))
    }
}

/// A tier, why it was chosen, and the score behind it.
public struct Decision: Equatable, Sendable {
    /// The tier that will serve the request.
    public let tier: Tier
    /// Why this tier and not another.
    public let reason: Reason
    /// Difficulty in `0...1`, higher meaning harder, or `nil` if the prompt was
    /// not scored — no head loaded, or no text to score.
    public let score: Float?
}

/// The routing core: classify, score, apply policy, apply session stickiness.
///
/// One instance may be shared across tasks. Its score cache is behind a mutex and
/// the rest is read-only once built, so concurrent `decide` calls are safe and
/// contend only on a cache hit.
public final class Router: @unchecked Sendable {
    private let handle: OpaquePointer

    /// Build a router, configured from the environment as the server is.
    ///
    /// - Parameter headPath: the scoring head's weights, or `nil` to take the
    ///   configured default. A head that will not load is not a failure; the
    ///   policy has an unscored path.
    /// - Returns: `nil` if configuration was rejected.
    public init?(headPath: String? = nil) {
        let handle = headPath.withCStringOrNull(wattrouter_new)
        guard let handle else { return nil }
        self.handle = handle
    }

    deinit { wattrouter_free(handle) }

    /// Decide which tier serves a request.
    ///
    /// - Parameters:
    ///   - body: an OpenAI-shaped chat completion request.
    ///   - pin: a tier to force, or `nil`.
    ///   - session: identifies the conversation, so a tier it has already been
    ///     raised to is not dropped partway through. Empty means no stickiness.
    /// - Returns: `nil` if the body was not valid JSON, or if the core could not
    ///   decide. No panic crosses the boundary; failure arrives as a value.
    public func decide(body: String, pin: Tier? = nil, session: String = "") -> Decision? {
        let pinned: String? = pin?.name
        let raw = body.withCString { body in
            pinned.withCStringOrNull { pin in
                session.withCString { session in
                    wattrouter_decide(handle, body, pin, session)
                }
            }
        }

        // A sentinel tier fails to construct, so a failed call cannot be read as
        // a routing answer by a caller that forgot to check.
        guard let tier = Tier(rawValue: raw.tier), let reason = Reason(rawValue: raw.reason)
        else { return nil }
        return Decision(tier: tier, reason: reason, score: raw.score < 0 ? nil : raw.score)
    }
}

extension Optional where Wrapped == String {
    /// Call `body` with this string as a C string, or with null when absent.
    ///
    /// `String.withCString` has no optional form, and writing that branch per
    /// call site is where absence turns into an empty string by accident, which
    /// the core reads as a value.
    fileprivate func withCStringOrNull<T>(_ body: (UnsafePointer<CChar>?) -> T) -> T {
        switch self {
        case .some(let value): value.withCString(body)
        case .none: body(nil)
        }
    }
}
