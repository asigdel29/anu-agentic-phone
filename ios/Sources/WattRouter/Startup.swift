// Startup.swift — bringing the core up, in the one order that works.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   Startup  Build the router this process uses.
//
// `Router()` reads the process environment through `Config::from_env` and returns
// null if it does not like what it finds. Two consequences shape everything here.
//
// It cannot say why. A null covers a missing credential, an unparseable
// `WATTROUTER_ADDR` and a misspelt `WATTROUTER_EMBEDDER` alike. "Nobody has signed
// in" and "the configuration is broken" want entirely different things from an
// interface, so the credential is checked before the core is asked rather than
// inferred from its silence afterwards.
//
// And `config.rs:85` says the read "must not run concurrently with anything
// mutating it". `setenv` is process-wide and `from_env` is a plain read of the
// same table. Swift cannot prove that of a whole process, but it can confine
// every write this app performs to one actor, which removes the only mutation
// there is to race with. Hence `@MainActor` on the two members that touch it.

import Foundation

/// Building the router, with the credential in place first.
public enum Startup {
    /// Why the core could not be built.
    public enum Failure: Error, Equatable, Sendable {
        /// Nothing is stored. Nobody has signed in, and asking is the answer.
        case noCredential
        /// A credential is stored and the core refused anyway. As specific as it
        /// can be: `Router()` reports every configuration fault as the same null.
        case coreRefused
    }

    /// The Keychain account the provider credential lives under.
    public static let account = "neuralwatt-api-key"

    /// Build the router this process uses.
    ///
    /// - Parameter headPath: the scoring head, or `nil` for the configured
    ///   default. No head ships in the app bundle and the default resolves under
    ///   a directory the sandbox does not have, so routing on a phone is unscored
    ///   today — which the policy has a path for, and which is why a head that
    ///   will not load is not an error.
    /// - Returns: a router, ready to decide.
    /// - Throws: [`Failure`].
    ///
    /// # Rely
    /// Main actor. See the note above: this writes the environment, and the core
    /// reads it.
    @MainActor
    public static func router(headPath: String? = nil) throws -> Router {
        guard let credential = Keychain.read(account), !credential.isEmpty else {
            throw Failure.noCredential
        }
        install(credential)

        guard let router = Router(headPath: headPath) else { throw Failure.coreRefused }
        return router
    }

    /// Put the credential where `Config::from_env` looks for it.
    @MainActor
    private static func install(_ credential: String) {
        setenv("NEURALWATT_API_KEY", credential, 1)

        // `WATTROUTER_BACKEND_*` is deliberately not set here. Every tier already
        // defaults to remote (config.rs:121) and only an explicit variable moves
        // it, so setting all six would be six values kept in step with a default
        // they already match. If one ever does arrive as local, `ChainWalk`
        // counts it and skips it rather than trying to load a model this app has
        // no runtime for.
    }
}
