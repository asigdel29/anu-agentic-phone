// Located.swift — where the phone is, and the seam to whatever knows.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Place        A position, its age, its accuracy, and a name when there is one.
//   Located      The seam to whatever can find the phone.
//   WhereAmITool Asking where the phone is, as a tool.
//
// No CoreLocation here, as in Calendars.swift and Reminders.swift: the
// conformance is its own change.
//
// A location is a moment, not a state, and that is the whole difference from
// everything else this agent reads. A calendar entry is true until somebody
// changes it. A fix is true for minutes.
//
// So when it was taken travels with it. A fix from three hours ago is a
// different city, and printed as coordinates it is indistinguishable from one
// taken now.
//
// So does how accurate it is. A fix good to three kilometres, printed to five
// decimal places, reads as a doorstep. It is said in words rather than as a
// number nobody scales.
//
// And a name is a different question from a position. Reverse geocoding needs
// the network and fails on a train; coordinates with no name are still an
// answer, and a tool that treats the lookup as part of the fix turns a working
// location into a failure.
//
// Nothing here tracks. One fix, when a turn asks for one.

import Foundation

/// Where the phone was, and how much to trust it.
public struct Place: Equatable, Sendable {
    public let latitude: Double
    public let longitude: Double
    /// When the fix was taken. Not when it was asked for: the system answers
    /// from a cache, and the difference is the whole point of carrying this.
    public let taken: Date
    /// The radius in metres inside which the true position lies. Negative where
    /// the system would not say, which it reports for a fix it does not stand
    /// behind.
    public let accuracy: Double
    /// What the place is called, when something could say. `nil` is ordinary:
    /// the lookup needs the network.
    public let name: String?

    public init(
        latitude: Double, longitude: Double, taken: Date, accuracy: Double, name: String? = nil
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.taken = taken
        self.accuracy = accuracy
        self.name = name
    }
}

/// The seam to whatever can find the phone.
///
/// # Rely
/// `here` is called only after the location capability has been obtained.
public protocol Located: Sendable {
    /// One fix.
    ///
    /// - Returns: where the phone is, or was recently enough for the system to
    ///   answer from a cache — which is why [`Place`] carries when.
    func here() async throws -> Place
}

/// Where the phone is.
public struct WhereAmITool: Tool {
    /// Past this, a fix is history rather than a location. Ten minutes: long
    /// enough that standing still does not re-prompt the hardware, short enough
    /// that a train has not covered a useful distance.
    public static let stale: TimeInterval = 10 * 60

    public let name = "where_am_i"

    public let purpose = """
        Where the phone is now. Takes no arguments. The answer says how old the \
        fix is and how accurate it is — use both: a fix from an hour ago or one \
        good to several kilometres is not somewhere to send anybody.
        """

    public let schema = """
        {
          "type": "object",
          "properties": {},
          "additionalProperties": false
        }
        """

    private let located: any Located
    private let permission: Permission
    private let now: @Sendable () -> Date

    public init(
        located: any Located, permission: Permission,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.located = located
        self.permission = permission
        self.now = now
    }

    /// - Returns: where the phone is, with everything needed to distrust it.
    ///
    /// # Rely
    /// Nothing. The permission is obtained here.
    public func run(arguments: Data) async throws -> String {
        try await permission.obtain(.location)
        return Self.describe(try await located.here(), asOf: now())
    }

    /// One fix, as a sentence.
    ///
    /// Static so the rendering — which is all of the decisions — is exercised
    /// without a permission or a location manager in the way.
    static func describe(_ place: Place, asOf now: Date) -> String {
        let position = String(format: "%.5f, %.5f", place.latitude, place.longitude)
        let named = place.name.map { "\($0) (\(position))" } ?? position

        return "\(named), \(age(place.taken, asOf: now)), \(precision(place.accuracy))"
    }

    /// How old the fix is, in words rather than as a timestamp to subtract.
    private static func age(_ taken: Date, asOf now: Date) -> String {
        let seconds = now.timeIntervalSince(taken)

        // A fix from the future is a clock that moved, not a location from
        // later. Reported as fresh rather than as a negative age, which reads as
        // nonsense and tells the model nothing it can use.
        guard seconds > 0 else { return "taken just now" }
        guard seconds >= stale else { return "taken \(Int(seconds / 60)) minutes ago" }

        // Said as a warning rather than as a number, because the number is the
        // thing a model skims past.
        return "taken \(Int(seconds / 60)) minutes ago, which is old enough to be somewhere else"
    }

    /// How much to trust the position.
    private static func precision(_ metres: Double) -> String {
        // Negative is the system declining to stand behind the fix at all. That
        // is not "very accurate", which is how an unchecked comparison reads it.
        guard metres >= 0 else { return "with no stated accuracy, so treat it as a guess" }
        guard metres >= 1000 else { return "accurate to about \(Int(metres)) m" }
        return "accurate only to about \(Int(metres / 1000)) km"
    }
}
