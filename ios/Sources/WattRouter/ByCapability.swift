// ByCapability.swift — one authorizer over several frameworks.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   ByCapability  Route each capability to whatever can answer for it.
//
// `Permission` holds one authorizer, deliberately: it is what remembers which
// capabilities have spent their one prompt, and a second instance is a second
// prompt for one capability. So a second framework cannot arrive as a second
// `Permission`; it has to arrive here.
//
// The map is explicit rather than inferred. The obvious alternative is to ask
// each authorizer in turn and take the first that does not say `.unavailable`,
// and it is wrong for a reason that only shows up on somebody else's phone:
// `EventKitAuthorizer` says `.unavailable` both for a capability it does not own
// and for a calendar restricted by a device policy. Under that scheme, a managed
// phone with calendars turned off would fall through to whichever authorizer
// came next and be asked a question about the wrong framework.
//
// An unmapped capability is `.unavailable`, which is the truth about a build
// that wired no authorizer for it — the same claim `EventKitAuthorizer` already
// makes about contacts and location, and for the same reason.

import Foundation

/// Route each capability to whatever can answer for it.
public struct ByCapability: Authorizer {
    private let owners: [Capability: any Authorizer]

    /// - Parameter owners: which authorizer owns which capability. A capability
    ///   with no entry is reported unavailable rather than passed to somebody
    ///   who would guess at it.
    public init(_ owners: [Capability: any Authorizer]) {
        self.owners = owners
    }

    public func state(of capability: Capability) async -> PermissionState {
        guard let owner = owners[capability] else { return .unavailable }
        return await owner.state(of: capability)
    }

    public func request(_ capability: Capability) async -> PermissionState {
        guard let owner = owners[capability] else { return .unavailable }
        return await owner.request(capability)
    }
}
