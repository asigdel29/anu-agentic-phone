// CLLocated.swift — the location seams, against the framework that owns them.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   CLLocationAuthorizer  Location access, asked for and read.
//   CLLocated             One fix, and a name for it when there is one.
//
// CoreLocation is the awkward one of the three frameworks behind `Permission`,
// and the awkwardness is all in obtaining access.
//
// EventKit and Contacts both have an `async` request. This does not:
// `requestWhenInUseAuthorization()` returns immediately and the answer arrives
// on a delegate callback. So obtaining it means holding a continuation across
// that callback, and resuming a continuation twice is a crash rather than a
// warning — hence the `resumed` flag, which is not defensive programming but the
// only thing standing between a second callback and a trap.
//
// The delegate is a main-actor class rather than an actor, because CoreLocation
// calls it on the main thread and an actor would be a second isolation to hop
// through for no gain.
//
// The fix itself needs no delegate. `CLLocationUpdate.liveUpdates()` is an
// AsyncSequence whose elements carry both the location and the authorization
// state, so a denial mid-stream arrives as a value rather than as a silence. One
// element and then stop: nothing here tracks.

import CoreLocation
import Foundation

/// Location access.
public final class CLLocationAuthorizer: NSObject, Authorizer, CLLocationManagerDelegate,
    @unchecked Sendable
{
    private let manager = CLLocationManager()
    /// The prompt in progress, resumed by the delegate callback below.
    private var pending: CheckedContinuation<PermissionState, Never>?

    public override init() {
        super.init()
        manager.delegate = self
    }

    /// The framework's answer, as this app's.
    ///
    /// Static and over the raw value, so the whole mapping is reachable from a
    /// simulator — the pattern the other two authorizers set.
    ///
    /// `authorizedAlways` is the case worth reading twice. It is *more* than this
    /// app asks for rather than less: somebody granted it in Settings, and
    /// reading it as anything but granted would refuse access they deliberately
    /// gave.
    static func state(from status: CLAuthorizationStatus) -> PermissionState {
        switch status {
        case .authorizedWhenInUse, .authorizedAlways: .granted
        case .denied: .refused
        case .restricted: .unavailable
        case .notDetermined: .unasked
        @unknown default: .unasked
        }
    }

    public func state(of capability: Capability) async -> PermissionState {
        guard capability == .location else { return .unavailable }
        return Self.state(from: manager.authorizationStatus)
    }

    public func request(_ capability: Capability) async -> PermissionState {
        guard capability == .location else { return .unavailable }

        // Already answered one way or the other. Asking again shows nothing and
        // the callback never fires, so a continuation here would hang the turn.
        let current = Self.state(from: manager.authorizationStatus)
        guard current == .unasked else { return current }

        return await withCheckedContinuation { resume in
            pending = resume
            manager.requestWhenInUseAuthorization()
        }
    }

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // Fires for every change, including ones nobody here asked for — a
        // person toggling the setting while the app runs. Resuming a
        // continuation that is not there is a crash, and resuming one twice is
        // the same crash, so the take is what makes this safe rather than a
        // check that it is non-nil.
        guard let resume = pending else { return }
        pending = nil
        resume.resume(returning: Self.state(from: manager.authorizationStatus))
    }
}

/// One fix, and a name for it when there is one.
public actor CLLocated: Located {
    /// Why a fix could not be taken.
    public enum Failure: LocalizedError, Equatable, Sendable {
        /// The stream ended without ever producing one.
        case noFix

        public var errorDescription: String? {
            switch self {
            case .noFix:
                "the phone could not get a location fix. Indoors or underground is the usual reason"
            }
        }
    }

    public init() {}

    /// # Rely
    /// The location capability has been obtained. Without it the stream yields
    /// updates carrying a denial rather than a location, which reads as no fix.
    public func here() async throws -> Place {
        for try await update in CLLocationUpdate.liveUpdates(.default) {
            guard let fix = update.location else { continue }
            // One element and then stop. The sequence would go on producing
            // updates, which is tracking rather than answering a question.
            return Place(
                latitude: fix.coordinate.latitude,
                longitude: fix.coordinate.longitude,
                taken: fix.timestamp,
                accuracy: fix.horizontalAccuracy,
                name: await Self.name(of: fix))
        }
        throw Failure.noFix
    }

    /// What the place is called, or `nil`.
    ///
    /// Never throws. Reverse geocoding needs the network and fails on a train,
    /// and #270's contract is that a name is a second question — a failure here
    /// must not turn a working fix into a failed call.
    private static func name(of fix: CLLocation) async -> String? {
        guard let found = try? await CLGeocoder().reverseGeocodeLocation(fix).first else {
            return nil
        }
        // The most specific thing that is not the street number: a model reading
        // "Charing Cross" knows where it is, and "12" tells it nothing.
        return found.name ?? found.locality ?? found.administrativeArea
    }
}
