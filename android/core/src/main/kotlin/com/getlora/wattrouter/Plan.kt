// Plan.kt: what a turn is about to do, put to somebody once.
//
// History
//   2026-08-10  A. Sigdel  Created with #595.
//
// Contents
//   Plan      What the turn intends, in what it is about to run.
//   Approval  Whoever answers.
//   Planned   The rule for when to ask, and what silence means.
//
// Autonomy.PLAN existed as a name for a milestone and did nothing, and this is
// the half that makes it mean something. Ask fires per action at the Phone
// seam; this fires once per turn at the loop, which is why it is here and not
// in Autonomy.kt beside Confirmed.
//
// The plan is not a separate request. A turn's first round already says what it
// wants and names the tools it wants run, so the plan is that round rather than
// a promise obtained beforehand and free to disagree with it. That is the whole
// reason this is cheap: no extra model call, and no gap between what was
// approved and what runs next.
//
// It is also the honest limit. What is approved is the first round; the rounds
// after it are unattended by construction, which is what "approved once, then
// runs unattended" means and what Ask is for if it is not what somebody wants.

package com.getlora.wattrouter

/**
 * What a turn is about to do.
 *
 * @property says what the model wrote before asking for anything. Often empty:
 *   a model that goes straight to tools has said nothing, and a surface has to
 *   render that rather than wait for it.
 * @property steps the tools it wants run, in order, by name. Names rather than
 *   arguments for [Confirmed]'s reason turned around: an argument can be a
 *   paragraph or a pasted password, and a dialog somebody has to scroll is one
 *   they dismiss without reading.
 */
data class Plan(val says: String, val steps: List<String>)

/**
 * Whoever answers a plan.
 *
 * A seam for [Consent]'s reason: the answer is a person on a phone, and the
 * rule for when to ask belongs where a JVM test can reach it.
 */
fun interface Approval {
    /**
     * Put the plan, and wait.
     *
     * # Rely
     * Called from the turn loop, at most once per turn, before any tool has
     * run. Suspends for as long as somebody takes, which is unbounded: the wait
     * is a person, and the scope that started the turn is what cancels it.
     *
     * @return whether the turn may go ahead. False ends it.
     */
    suspend fun mayI(plan: Plan): Boolean
}

/**
 * When to ask, and what to do when there is nothing to ask about.
 *
 * @param mode read at the moment of asking rather than held, which is
 *   [Confirmed]'s rule for the same reason: somebody who changed their mind
 *   between typing and the model answering meant this turn.
 */
class Planned(
    private val mode: () -> Autonomy,
    private val approval: Approval,
) {
    /**
     * Whether this turn may proceed.
     *
     * Two silences are true rather than asked. Any mode but [Autonomy.PLAN]
     * governs elsewhere or not at all. And a first round with no tool calls has
     * nothing to approve: the model answered, nothing will touch the phone, and
     * a dialog over an answer is a dialog people learn to dismiss.
     *
     * # Rely
     * As [Approval.mayI], which it calls at most once.
     */
    suspend fun approved(plan: Plan): Boolean = when {
        mode() != Autonomy.PLAN -> true
        plan.steps.isEmpty() -> true
        else -> approval.mayI(plan)
    }

    companion object {
        /**
         * What the model is told when somebody declines.
         *
         * Worded as a person rather than as a policy, for the reason
         * [Confirmed] gives: a model told a rule refused it looks for another
         * way through, and a model told a person declined stops and says so.
         *
         * It names the shape of the mode too. A model that knows it was asked
         * once, at the start, can say what it would have done rather than
         * offering to try the first step on its own.
         */
        const val DECLINED: String =
            "the person using the phone did not approve this plan. They are " +
                "asked once, before anything runs, and they declined. Do not " +
                "try any part of it another way. Say what you were going to do " +
                "and why, so they can decide whether to ask again."
    }
}
