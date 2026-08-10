// Budget.kt: how much a turn may actually do.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Budget    What a turn has left.
//   Budgeted  A Phone that spends it.
//
// Agent's eight-round cap bounds model round-trips and not effects: one round
// carries as many tool calls as the model wrote, so a turn tapping its way down
// a list can act an unbounded number of times inside a bounded number of rounds.
//
// This sits at the Phone seam rather than in DrivingService, which is a
// departure from how-the-agent-drives.md worth stating. That file says safety is
// enforced where the action is dispatched, and the reason it gives is that a
// rule in a tool is one the ninth tool forgets. A budget is per turn, and the
// service has no notion of one: it is bound by the system and outlives every
// conversation, so putting it there means inventing a turn signal for it to
// hold. The seam satisfies the reason: every acting tool reaches the phone
// through one object, and a tenth is counted without knowing this exists.
//
// Reading does not spend. A model that reads twice before acting is being
// careful, and charging it for that teaches the opposite.

package com.getlora.wattrouter

/**
 * What a turn has left to do.
 *
 * One per conversation, reset by [Agent] at the top of every turn, including a
 * resumed one, which is what an interrupt produces and which should get a fresh
 * allowance rather than inheriting a spent one.
 */
class Budget(private val most: Int = DEFAULT) {
    private var spent = 0

    /** How many more actions this turn may take. */
    val left: Int get() = (most - spent).coerceAtLeast(0)

    /** Start again. Called once per turn, not once per round. */
    fun beginTurn() {
        spent = 0
    }

    /**
     * Take one, if there is one.
     *
     * @return whether there was. False is a refusal for the caller to word:
     *   this type knows the count and not what was being attempted.
     */
    fun spend(): Boolean {
        if (left == 0) return false
        spent++
        return true
    }

    companion object {
        /**
         * Actions in a turn when nobody says otherwise.
         *
         * Generous enough for a real task (opening an app, finding a row,
         * filling two fields and confirming is under ten) and low enough that
         * a loop is stopped while somebody is still watching it.
         */
        const val DEFAULT = 25
    }
}

/**
 * A [Phone] that spends a [Budget] on everything that changes the screen.
 *
 * Wrapping rather than checking inside each tool: this is the one place, and a
 * tool added later is counted whether or not its author knew.
 */
class Budgeted(private val phone: Phone, private val budget: Budget) : Phone {

    override suspend fun barredNow(): String? = phone.barredNow()

    // Passed through uncounted, with barredNow and read: asking whether the
    // service exists is not acting on the phone, and a turn that spent its
    // budget still has to be able to say why it cannot read.
    override suspend fun attached(): Boolean = phone.attached()

    override suspend fun read(): Reading? = phone.read()

    override suspend fun apps(): List<Launchable>? = phone.apps()

    override suspend fun tap(at: Handle, from: Generation): Done? =
        ifAffordable { phone.tap(at, from) }

    override suspend fun type(at: Handle, from: Generation, text: String): Done? =
        ifAffordable { phone.type(at, from, text) }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? =
        ifAffordable { phone.scroll(at, from, onward) }

    override suspend fun navigate(way: Way): Done? = ifAffordable { phone.navigate(way) }

    override suspend fun open(packageName: String): Done? = ifAffordable { phone.open(packageName) }

    /**
     * Spend one and do it, or refuse.
     *
     * Spent before rather than after. An action that ran and then failed to be
     * accounted for is one the phone did and the budget did not see.
     */
    private suspend fun ifAffordable(act: suspend () -> Done?): Done? =
        if (budget.spend()) {
            act()
        } else {
            Done.Refused(
                "this turn has done as much to the phone as it is allowed to. " +
                    "Say what you were trying to do and let the person decide " +
                    "whether to carry on",
            )
        }
}
