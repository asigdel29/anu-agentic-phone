// Startup.kt: getting from a cold launch to a core, or to a reason there is none.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Startup.swift is the shape, and its decision is that three states are not
// one. `Core.open` answers with a core or a bare null, and null covers both
// "nobody has signed in" and "a key is stored and the core would not take it".
// Those want different screens: the first wants a field to type in, and the
// second wants saying so, because signing in again with the same key is a loop.
//
// So the credential is read before the core is asked, rather than the null
// being interpreted afterwards.
//
// Smaller than the Swift version, and the missing part is the part that was a
// workaround. `Router()` on iOS reads NEURALWATT_API_KEY out of the process
// environment, which Startup.install writes with setenv; Kotlin has no setenv,
// so #314 gave the core an entry point that takes the credential directly.

package com.getlora.wattrouter

import android.content.Context

/** How far a cold launch got. */
sealed interface Startup {

    /** There is a core, and it is the caller's to close. */
    data class Ready(val core: Core) : Startup

    /** Nobody has signed in. Ask. */
    data object NoCredential : Startup

    /**
     * A credential is stored and the core would not start with it.
     *
     * Not a state signing in again fixes on its own, so it is worth saying
     * rather than folding into [NoCredential]: a key can be well-formed and
     * wrong, and the core can be built without a feature it needs.
     */
    data object CoreRefused : Startup

    companion object {
        /**
         * Read the stored credential and open a core with it.
         *
         * The caller owns the returned [Core] and must close it. Nothing here
         * caches: a second call opens a second core, which is a leak rather
         * than a convenience, so callers hold one for the process.
         */
        fun begin(context: Context): Startup = from(Credential(context).read())

        /**
         * As [begin], from a credential already in hand.
         *
         * The seam is a string rather than a [Credential] because that is what
         * makes the three states reachable from a test: storing a credential
         * the core will refuse is not something [Credential] permits, and it is
         * exactly the case worth having a name for.
         */
        fun from(key: String?): Startup {
            if (key == null) return NoCredential
            return Core.open(key)?.let(::Ready) ?: CoreRefused
        }
    }
}
