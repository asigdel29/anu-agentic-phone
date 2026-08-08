// UIKitOpener.swift — the opener seam, against the system that owns it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   UIKitOpener  Hand a URL to the system.
//
// Its own file for the reason EventKitCalendars is: the seam is testable and
// this is not, and keeping them apart is what stops the untestable half being
// dragged into every case that touches the testable one.
//
// Main actor, because `UIApplication.shared` is. That is not a detail to route
// around — asking the system to open a URL is asking it to change what is on
// screen, and the actor is where that decision belongs.
//
// `openSensitiveURL` and friends do not apply: `shortcuts:` is an ordinary
// scheme, and the completion handler's flag is the whole answer.

import Foundation
import UIKit

/// Hand a URL to the system.
public struct UIKitOpener: Opener {
    public init() {}

    /// # Rely
    /// Hops to the main actor, so a tool running off it may call this directly.
    /// The isolation is on the method rather than around the call: `open`'s
    /// options dictionary is not `Sendable`, so anything that crosses an
    /// isolation boundary mid-call is refused by the compiler.
    @MainActor
    public func open(_ url: URL) async -> Bool {
        guard UIApplication.shared.canOpenURL(url) else { return false }
        return await UIApplication.shared.open(url)
    }
}
