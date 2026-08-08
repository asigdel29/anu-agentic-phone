// SignInView.swift — the one screen a fresh install has.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   SignInView  Somewhere to put the provider key.
//
// Deliberately the smallest thing that unblocks a turn: one field, one button,
// and the reason it did not work when it did not. There is no account, no sign-up
// and no validation against the provider — a key that is the wrong key fails on
// the first turn with the provider's own words, which is more use than anything
// this screen could invent.
//
// In Theme, like everything else the app says about itself: cyan for the app
// talking, signal for what is asked of the person.

import SwiftUI

/// Somewhere to put the provider key.
public struct SignInView: View {
    /// Called once a key is stored, so the root can build a turn runner.
    private let stored: () -> Void

    @State private var typed = ""
    @State private var refusal: String?

    public init(stored: @escaping () -> Void) {
        self.stored = stored
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("A provider key")
                .font(.title2.monospaced())
                .foregroundStyle(Theme.signal.color)

            Text(
                """
                Kept in the keychain on this phone. It is sent to the provider \
                when a turn needs one, and nowhere else.
                """
            )
            .font(.footnote.monospaced())
            .foregroundStyle(Theme.cyan.color.opacity(0.7))

            SecureField("key", text: $typed)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.body.monospaced())
                .foregroundStyle(Theme.signal.color)
                .padding(12)
                .background(Theme.ground.color.opacity(0.6))

            if let refusal {
                Text(refusal)
                    .font(.footnote.monospaced())
                    .foregroundStyle(Theme.signal.color)
            }

            Button("Save", action: save)
                .font(.body.monospaced())
                .foregroundStyle(Theme.cyan.color)
                .disabled(typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.ground.color)
    }

    private func save() {
        do {
            try Credential.store(typed)
            typed = ""
            refusal = nil
            stored()
        } catch {
            refusal = error.localizedDescription
        }
    }
}
