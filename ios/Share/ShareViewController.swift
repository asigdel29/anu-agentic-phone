// ShareViewController.swift — what another app hands in.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   ShareViewController  Take the shared item and leave it where the app looks.
//
// A separate process with its own sandbox, which is the whole reason this is
// short. It cannot call into the app and the app is usually not running, so
// everything it can do is write into the container both halves have — see
// Inbox.swift for what that protocol has to survive.
//
// No interface. A share sheet that asks a question is a share sheet people stop
// using, and there is nothing to ask: the text goes to the agent, which is what
// choosing this extension meant.
//
// It completes rather than cancels on failure, and says so in the log rather
// than to the person. An extension that puts an alert in front of somebody who
// tapped share by accident is worse than one that quietly did nothing — but
// silently doing nothing is exactly the failure Inbox.swift is written against,
// so the one case that must not be silent is the container being absent, which
// means the App Group is not provisioned and nothing will ever work.

import Foundation
import UniformTypeIdentifiers
import WattRouter
import UIKit

/// Take the shared item and leave it where the app looks.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        Task { await hand(over: extensionContext) }
    }

    /// Write whatever came in, then get out of the way.
    private func hand(over context: NSExtensionContext?) async {
        defer { context?.completeRequest(returningItems: nil) }

        guard let container = Inbox.container else {
            // The App Group is not provisioned, so there is nowhere to put this
            // and there never will be until the build changes. Logged rather
            // than shown: the person cannot fix it and the developer can.
            NSLog("share extension: no App Group container, so nothing was handed over")
            return
        }

        let inbox = Inbox(container: container)
        for text in await Self.text(in: context?.inputItems as? [NSExtensionItem] ?? []) {
            do {
                try inbox.write(text, at: Date())
            } catch {
                NSLog("share extension: could not write what was shared: \(error)")
            }
        }
    }

    /// Everything shareable in what arrived, as text.
    ///
    /// A URL and a string are both text as far as a turn is concerned — somebody
    /// sharing a link wants the agent to have the link. Attachments that are
    /// neither are skipped rather than described, because "an image was shared"
    /// is not something a turn can act on.
    private static func text(in items: [NSExtensionItem]) async -> [String] {
        var found: [String] = []
        for provider in items.flatMap({ $0.attachments ?? [] }) {
            if let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
                found.append(url.absoluteString)
            } else if let text = try? await provider.loadItem(
                forTypeIdentifier: UTType.plainText.identifier) as? String
            {
                found.append(text)
            }
        }
        return found
    }
}
