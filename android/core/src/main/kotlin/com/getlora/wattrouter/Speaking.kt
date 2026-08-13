// Speaking.kt: the half of a conversation this app did not have.
//
// History
//   2026-08-13  A. Sigdel  Created with #709.
//
// Contents
//   Spoken    What one attempt at speaking came back with.
//   Speaking  Where words go to be said, as a seam.
//   worthSaying  Which line of a turn is the one to say.
//
// Listening.kt's shape, deliberately: it was written as one half of a pair and
// says so, and the pair should not be two designs. Two outcomes rather than a
// nullable for its reason, one layer along: a phone with no voice installed, an
// engine that failed to start and a turn with nothing worth saying are three
// things, and somebody told none of them is looking at a switch that did
// nothing.
//
// What it says is the question #601 deferred this on: "what to say when a turn
// is fifty lines of tool output". worthSaying is the answer and it needed no
// invention, because the transcript already separates the two. Row.Answered is
// what the model said and Row.Used is what a tool printed, and ChatScreen has
// truncated the second and left the first whole since it was written.
//
// No capability and no permission. Android grants none for playback, and
// permissionFor is an exhaustive when over Capability that PermissionDeclaration
// Test checks against the merged manifest, so a capability invented here would
// fail on a device against a permission that does not exist. It is the one place
// the microphone's shape is not copied.

package com.getlora.wattrouter

/** What one attempt at speaking came back with. */
sealed interface Spoken {
    /** It was said. */
    data object Said : Spoken

    /**
     * Nothing was said, and why.
     *
     * @property why a whole sentence addressed to the person, naming what they
     *   can do about it wherever there is anything. [Heard.Silence]'s reasoning:
     *   a control that does nothing and says nothing is one people press twice.
     */
    data class Silence(val why: String) : Spoken
}

/** Where words go to be said. */
interface Speaking {
    /**
     * Say it, and wait for it to be said.
     *
     * # Rely
     * Called from the composition when a turn ends, on a scope cancelled when
     * the person leaves. Suspends until the phone has finished speaking, which
     * is as long as the text takes to read aloud, so two calls must not overlap:
     * the second would either queue behind the first or cut it off, and which
     * one is the platform's choice rather than this seam's. The conformance puts
     * itself on the thread the platform demands rather than asking the caller.
     *
     * @param text what to say, WHERE it is not blank. [worthSaying] is what
     *   decides that; a blank string reaching here is a caller that skipped it.
     * @return whether it was said, and never null: a phone that cannot speak is
     *   an ordinary outcome for somebody to read.
     */
    suspend fun say(text: String): Spoken
}

/**
 * The line of a finished turn worth saying aloud, or null if there is none.
 *
 * The whole of #601's deferred question, and it is a read rather than a
 * judgement: [Row.Answered] is the model's answer and [Row.Used] is what a tool
 * printed. A turn of fifty lines of tool output has an answer at the end of it,
 * and the answer is the thing worth hearing.
 *
 * [Row.Failed] is said too, because the case somebody most wants to be told
 * about is the one where the phone is in a pocket and the turn did not work.
 * Saying nothing there is the failure this exists to avoid, one level up.
 *
 * The last of either, rather than the last answer: a turn that answered and then
 * failed ends failed, and reading the answer aloud would report a turn that
 * worked.
 *
 * Public rather than internal because the caller is the application module and
 * `internal` does not cross one. It is part of what this seam offers: [Speaking]
 * says how to say something and this says what.
 */
fun worthSaying(rows: List<Row>): String? =
    when (val last = rows.lastOrNull { it is Row.Answered || it is Row.Failed }) {
        is Row.Answered -> last.text.trim().ifEmpty { null }
        is Row.Failed -> last.reason.trim().ifEmpty { null }
        else -> null
    }
