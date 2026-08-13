// Recorded.kt: the Phone that fills a Replay.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// The third decorator at the Phone seam, after Budgeted and Confirmed, and it
// is there for Budget.kt's reason: every acting tool reaches the phone through
// one object, so a tenth is recorded without knowing this exists.
//
// The question this file answers is which calls count as doing something.
// Replay answers how much of it to keep, and the two are apart because they are
// different questions.
//
// Reading is never a step. A replay of what a turn did should not fill with the
// times it looked, which is the same line Budget.kt draws for the same reason
// and Autonomy.kt draws again.

package com.getlora.wattrouter

/**
 * A [Phone] that records what it did.
 *
 * Wrap inside everything else: `Confirmed(Budgeted(Recorded(phone)))`. A step
 * the budget refused or a person declined did not happen, and a replay showing
 * one would be a replay of things the phone did not do.
 *
 * The recording is after the action rather than before, which is how [Budgeted]
 * and [Confirmed] order theirs turned round. They must not let through what
 * they have not accounted for; this must not record what did not happen.
 */
class Recorded(private val phone: Phone, private val replay: Replay) : Phone {

    override suspend fun barredNow(): String? = phone.barredNow()

    override suspend fun attached(): Boolean = phone.attached()

    override suspend fun read(): Reading? = phone.read()

    // Forwarded rather than left to the default, which is null: a decorator
    // answering that would report nothing in front of a phone driving an app.
    override suspend fun inFront(): String? = phone.inFront()

    override suspend fun capture(): Image? = phone.capture()

    override suspend fun apps(): List<Launchable>? = phone.apps()

    override suspend fun tap(at: Handle, from: Generation): Done? =
        recording("tapped ${asked(at)}") { phone.tap(at, from) }

    // What was typed is not in the step, for the reason it is not in a
    // confirmation prompt: it can be a paragraph, and it can be a password
    // somebody pasted.
    override suspend fun type(at: Handle, from: Generation, text: String): Done? =
        recording("typed into ${asked(at)}") { phone.type(at, from, text) }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? =
        recording("scrolled ${onward.word} in ${asked(at)}") { phone.scroll(at, from, onward) }

    override suspend fun navigate(way: Way): Done? =
        recording("pressed ${way.word}") { phone.navigate(way) }

    // The package name rather than the label, as Confirmed words it: resolving
    // one to the other asks the package manager, which answers with whatever an
    // app calls itself.
    override suspend fun open(packageName: String): Done? =
        recording("opened $packageName") { phone.open(packageName) }

    /**
     * Do it, and keep what it left behind.
     *
     * Only [Done.Did] is a step. [Done.Refused] is the phone saying it would
     * not and null is it saying it could not, and a replay showing either would
     * show somebody a step that never happened over a picture of a screen
     * nothing changed.
     *
     * # Rely
     * Costs a capture per action: a frame grab, a PNG compress and a base64 of
     * the result, after the action rather than during it.
     */
    private suspend fun recording(did: String, act: suspend () -> Done?): Done? {
        val done = act()
        if (done is Done.Did) {
            replay.add(Acted(did, phone.capture()))
        }
        return done
    }
}
