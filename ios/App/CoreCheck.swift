// CoreCheck.swift — the smallest thing that proves the core is really here.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   CoreCheck  The launch screen, which is a link check with a layout.
//
// It lists the routing vocabulary by the names the Rust gives it, through
// `wattrouter_tier_name` and `wattrouter_reason_name`. Those need no
// configuration and no credential — unlike `Router()`, which reads the
// environment and returns null without one — so they are the only part of the
// core an app can exercise before anything is wired up.
//
// Each row shows the Swift raw value beside the name the core returned for it,
// because that pairing is the thing that can silently be wrong: the enumerations
// are declared in Swift and their cases are pinned to the core's wire codes by
// nothing but agreement. Names written out in Swift instead would prove only that
// Swift can hold six strings.

import SwiftUI
import WattRouter

struct CoreCheck: View {
    var body: some View {
        NavigationStack {
            List {
                Section("Tiers") {
                    ForEach(Tier.allCases, id: \.rawValue) { tier in
                        LabeledContent(tier.name) { code(tier.rawValue) }
                    }
                }
                Section("Reasons") {
                    ForEach(Reason.allCases, id: \.rawValue) { reason in
                        LabeledContent(reason.name) { code(reason.rawValue) }
                    }
                }
            }
            .navigationTitle("Routing core")
        }
    }

    private func code(_ value: UInt8) -> some View {
        Text(String(value))
            .font(.footnote.monospaced())
            .foregroundStyle(.secondary)
    }
}

#Preview {
    CoreCheck()
}
