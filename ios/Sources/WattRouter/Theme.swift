// Theme.swift — the colours, and which of them may be drawn on which.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   Theme            The palette, and the one background everything sits on.
//   Theme.Surface    A colour something is drawn on.
//   Theme.Ink        A colour drawn on a surface.
//   contrastRatio    WCAG 2.1 contrast between an ink and a surface.
//
// Dark only, and that is a decision rather than a default: the palette is a
// signal yellow and a cyan against near-black, and both are unreadable on white.
// A light variant is a second palette, not a flag.
//
// `Surface` and `Ink` are separate types because the rule that matters here
// cannot survive as a comment. Cyan on signal yellow is 1.17:1 — invisible, and
// the kind of pairing that looks deliberate in a mock-up and fails on a phone in
// sunlight. There is one surface and everything else is ink, so ink on ink is not
// a thing the code can express and the rule needs no enforcing.
//
// The ratios are computed rather than recorded. #136 wrote 15.65:1 for signal on
// ground and 5.89:1 for error, and the real figures are 16.55:1 and 6.23:1; the
// tests assert what the formula gives against the WCAG thresholds, so a colour
// that stops passing says so rather than a comment going quietly out of date.

import SwiftUI

/// The palette.
public enum Theme {
    /// A colour something is drawn on. There is one.
    public struct Surface: Sendable {
        /// sRGB components, each in `0...1`.
        public let red: Double
        public let green: Double
        public let blue: Double

        /// The colour as SwiftUI draws it.
        public var color: Color {
            Color(red: red, green: green, blue: blue)
        }
    }

    /// A colour drawn on a `Surface`, and never a background itself.
    public struct Ink: Sendable {
        /// sRGB components, each in `0...1`.
        public let red: Double
        public let green: Double
        public let blue: Double

        /// The colour as SwiftUI draws it.
        public var color: Color {
            Color(red: red, green: green, blue: blue)
        }
    }

    /// Near-black, `#08080A`. The only background.
    public static let ground = Surface(red: 8 / 255, green: 8 / 255, blue: 10 / 255)

    /// Signal yellow, `#FCEE0A`. Body text and anything that carries meaning.
    public static let signal = Ink(red: 252 / 255, green: 238 / 255, blue: 10 / 255)

    /// Cyan, `#00F0FF`. A second voice, for what is secondary rather than lesser.
    public static let cyan = Ink(red: 0 / 255, green: 240 / 255, blue: 255 / 255)

    /// Red, `#FF4D6D`. Only for what has gone wrong.
    public static let error = Ink(red: 255 / 255, green: 77 / 255, blue: 109 / 255)
}

/// Relative luminance, as WCAG 2.1 defines it.
///
/// # Arguments
/// * `red`, `green`, `blue` — sRGB components, WHERE each is in `0...1`.
///
/// # Returns
/// Luminance in `0...1`, where 0 is black and 1 is white.
private func luminance(red: Double, green: Double, blue: Double) -> Double {
    // The piecewise transfer function: linear below the threshold, where the
    // gamma curve would otherwise misdescribe near-black, and a power curve
    // above it.
    func channel(_ value: Double) -> Double {
        value <= 0.039_28 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/// The contrast between an ink and the surface it is drawn on.
///
/// Takes an `Ink` and a `Surface` rather than two colours, so it cannot be asked
/// about a pairing the design does not have — which is also why it cannot be
/// asked about cyan on signal yellow.
///
/// # Returns
/// A ratio in `1...21`. WCAG asks 4.5 for body text and 7 for AAA.
public func contrastRatio(_ ink: Theme.Ink, on surface: Theme.Surface) -> Double {
    let inkLuminance = luminance(red: ink.red, green: ink.green, blue: ink.blue)
    let surfaceLuminance = luminance(red: surface.red, green: surface.green, blue: surface.blue)
    let lighter = max(inkLuminance, surfaceLuminance)
    let darker = min(inkLuminance, surfaceLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}
