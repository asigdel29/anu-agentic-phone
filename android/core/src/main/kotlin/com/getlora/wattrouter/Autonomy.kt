// Autonomy.kt: how much the agent may do without being asked.
//
// History
//   2026-08-10  A. Sigdel  Created with #552.
//
// Contents
//   Autonomy   How involved somebody wants to be.
//   Intent     One action, in the words it will be asked in.
//   Consent    Whoever answers.
//   Confirmed  A Phone that asks first.
//
// #452 asked when a confirmation prompt should fire and showed that all three
// obvious rules are guesses. Before every action makes the agent useless.
// Before "dangerous" actions is a guess about somebody else's words in
// somebody else's language, which this repository refuses everywhere else.
// Before the first action of a turn is the banner one tap earlier.
//
// So the rule is not inferred at all: the person says how involved they want to
// be, and it is the same for every action. Nothing here reads a label and
// decides Pay is frightening and Delete draft is not.
//
// This sits beside Budgeted, at the Phone seam, for Budget.kt's reason: every
// acting tool reaches the phone through one object, so a tenth is governed
// without knowing this exists. Wrapping order is Confirmed(Budgeted(phone));
// see Confirmed for why round that way.
//
// Reading is never gated. Asking permission to look is not what anybody means
// by this, and a turn that must ask before it can see cannot say what it wants
// to do.

package com.getlora.wattrouter

/** How much the agent may do without being asked. */
enum class Autonomy {
    /**
     * It says what it intends to do, that is approved once, and it then runs
     * unattended.
     *
     * Indistinguishable from [AUTO] here, and that is the design rather than an
     * omission: the approval happens once at the top of a turn, so by the time
     * an action reaches this seam it has already been given. The mode exists in
     * this enum because the turn loop reads the same setting.
     */
    PLAN,

    /** It acts. The banner, the stop button and the budget are what govern it. */
    AUTO,

    /** Every action is confirmed before it happens. */
    ASK,
}

/**
 * One action, worded as it will be put to somebody.
 *
 * @property verb what is about to happen, in the present tense: a prompt reads
 *   "Tap Send?" rather than "Tapped Send?". ScreenTools.say takes the past
 *   participle of the same word, and the two are deliberately not shared: one
 *   is asked before and one is reported after.
 * @property what it will happen to, already chosen by [asked] rather than by
 *   the caller. Never wording the caller composed: that is the whole of the
 *   defence below.
 */
data class Intent(val verb: String, val what: String)

/**
 * Whoever answers.
 *
 * A seam because the answer is a person on a phone and every decision about
 * when to ask is here, where a JVM test can reach it.
 */
fun interface Consent {
    /**
     * Ask, and wait.
     *
     * # Rely
     * Called from the turn loop, one action at a time. Suspends for as long as
     * somebody takes, which is unbounded: the wait is a person, and the scope
     * that started the turn is what cancels it. Never called for a read.
     *
     * @return which of the three happened, for the caller to word.
     */
    suspend fun mayI(intent: Intent): Said
}

/**
 * What came back from asking.
 *
 * Three rather than a boolean, which is #678 and is [Ran]'s argument one layer
 * up: a person who said no and a person who was never reached are different
 * things, and a caller told only false words the first when it means the
 * second. A model told somebody refused when nobody was asked looks for what it
 * did wrong, and there was nothing.
 */
enum class Said {
    /** Somebody was asked and said yes. */
    YES,

    /** Somebody was asked and said no. */
    NO,

    /**
     * Nobody could be asked.
     *
     * The dialog is a window the accessibility service adds to the display, so
     * with that service off there is nowhere to put a question. For the phone
     * tools that is the same answer either way, since they need the service to
     * do anything at all. For a shell it is not: it runs from this process and
     * would work with the service off forever.
     */
    UNASKED,
}

/**
 * What somebody is shown a handle as.
 *
 * The field [resolve] will key on, chosen by the same precedence and in the
 * same order, and this is a defence rather than a convenience.
 *
 * The model writes the handle. `resolve` requires the most durable field it
 * carries and lets the rest only narrow, so a handle carrying `viewId=send` and
 * `text=Cancel` resolves to the Send button. Showing the friendliest field
 * would put "Cancel" over an action that sends, which is worse than not asking.
 * A field that will not be matched on is never the word in the prompt.
 *
 * The id is what somebody is shown when a node has one, and it is not always
 * pretty. That is the correct trade: a prompt naming the wrong control legibly
 * is worse than one naming the right control awkwardly.
 */
internal fun asked(handle: Handle): String = when {
    !handle.viewId.isNullOrBlank() -> handle.viewId
    !handle.text.isNullOrBlank() -> handle.text
    !handle.description.isNullOrBlank() -> handle.description
    // Findable is checked where a handle is made, so this is a handle the model
    // invented. It resolves to Unusable and never acts; asking about it anyway
    // keeps the two decisions independent.
    else -> "something it did not name"
}

/**
 * A [Phone] that asks before it acts.
 *
 * Wrap outside [Budgeted]: `Confirmed(Budgeted(phone), …)`. The other way
 * round spends a budgeted action on a prompt somebody then declines, so a turn
 * refused twenty times has nothing left for the action they would have allowed.
 *
 * @param mode read per action rather than held for a turn. Somebody who turns
 *   this on while a turn is running means the next action, not the next turn;
 *   somebody who turns it off has stopped wanting to be asked and should not go
 *   on being asked until the turn ends.
 */
class Confirmed(
    private val phone: Phone,
    private val mode: () -> Autonomy,
    private val consent: Consent,
) : Phone {

    override suspend fun barredNow(): String? = phone.barredNow()

    override suspend fun attached(): Boolean = phone.attached()

    override suspend fun read(): Reading? = phone.read()

    // Forwarded rather than left to the default, which is null: a decorator
    // answering that would report nothing in front of a phone driving an app.
    override suspend fun inFront(): String? = phone.inFront()

    // Never gated, for the reason this file's header gives: asking permission
    // to look is not what anybody means by this. Delegated rather than
    // inherited, because the default answers null.
    override suspend fun capture(): Image? = phone.capture()

    override suspend fun apps(): List<Launchable>? = phone.apps()

    override suspend fun tap(at: Handle, from: Generation): Done? =
        ifAllowed(Intent("tap", asked(at))) { phone.tap(at, from) }

    // The text is not in the prompt. It can be a paragraph, it can be a
    // password somebody pasted, and a dialog that has to be scrolled to find
    // the button is one people dismiss without reading.
    override suspend fun type(at: Handle, from: Generation, text: String): Done? =
        ifAllowed(Intent("type into", asked(at))) { phone.type(at, from, text) }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? =
        ifAllowed(Intent("scroll ${onward.word} in", asked(at))) {
            phone.scroll(at, from, onward)
        }

    override suspend fun navigate(way: Way): Done? =
        ifAllowed(Intent("press", way.word)) { phone.navigate(way) }

    // The package name rather than the label. Resolving one to the other means
    // asking the package manager, which can answer with whatever an app calls
    // itself, and an app calling itself Settings is exactly the case a prompt
    // is supposed to catch.
    override suspend fun open(packageName: String): Done? =
        ifAllowed(Intent("open", packageName)) { phone.open(packageName) }

    /**
     * Ask if the mode says to, then do it or refuse.
     *
     * Asked before rather than after, for [Budgeted]'s reason: an action that
     * ran and was then approved is one the phone did and nobody consented to.
     */
    private suspend fun ifAllowed(intent: Intent, act: suspend () -> Done?): Done? = when {
        mode() != Autonomy.ASK -> act()
        consent.mayI(intent) == Said.YES -> act()
        // One sentence for both refusals here, unlike at the Terminal seam.
        // Every action this gates needs the service that raises the dialog, so
        // a phone that cannot ask is a phone that cannot tap either, and naming
        // the difference would name a distinction without one.
        else -> Done.Refused(
            // Named as a person rather than as a policy. A model told a rule
            // refused it looks for another way through; a model told a person
            // did not allow it stops and says so, which is the point of asking.
            //
            // "did not allow" rather than "said no", because #556 found a case
            // where nobody could be asked at all: the question is an overlay on
            // the accessibility service, and open_app does not need one. Both
            // are the same refusal and only one of them is somebody answering.
            "the person using the phone did not allow that. They decide " +
                "each action in this mode, and ${intent.verb} " +
                "${intent.what} was not approved. Do not try it another " +
                "way. Say what you were going to do and why.",
        )
    }
}
