// Listening.kt: a turn somebody says rather than types.
//
// History
//   2026-08-11  A. Sigdel  Created with #650.
//
// Contents
//   Heard      What one attempt at listening came back with.
//   Listening  Where spoken words come from, as a seam.
//
// The seam is Location.kt's, for the reason it gives: recognition needs a phone
// and nothing worth arguing about does. Two outcomes rather than a nullable
// string, though, because listening fails in more ways than a fix does. A phone
// with no recognition of its own, a microphone revoked mid-sentence and a room
// that was quiet are three things to be told, and somebody told none of them is
// looking at a button that did nothing.
//
// Nothing here speaks back and nothing waits for a phrase. Both are halves of
// #601: speaking raises what to say when a turn is fifty lines of tool output,
// and a wake word means always listening, which the manifest says is not on
// offer to this app anyway.

package com.getlora.wattrouter

/** What one attempt at listening came back with. */
sealed interface Heard {
    /** Words to send, trimmed and known not to be blank. */
    data class Words(val said: String) : Heard

    /**
     * Nothing to send, and why.
     *
     * @property why a whole sentence addressed to the person who spoke, naming
     *   what they can do about it wherever there is anything. Shown rather than
     *   logged: a microphone answering silently is one people press twice.
     */
    data class Silence(val why: String) : Heard
}

/** Where spoken words come from. */
interface Listening {
    /**
     * Listen once, and answer with what was said or why nothing was.
     *
     * # Rely
     * Called with [Capability.MICROPHONE] already obtained, from a scope that is
     * cancelled when the person leaves: this waits on somebody speaking, with no
     * limit of its own beyond the recognizer's, and holds the microphone for
     * that whole time, so two calls must not overlap. The conformance puts
     * itself on the thread the platform demands rather than asking the caller.
     */
    suspend fun listen(): Heard
}
