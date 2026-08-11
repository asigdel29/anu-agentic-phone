// AndroidApproval.kt: a plan on the display, waited on once per turn.
//
// History
//   2026-08-10  A. Sigdel  Created with #595.
//
// Contents
//   putting  A plan as the words somebody reads.
//   AndroidApproval  The question, over whatever is in front.
//
// AndroidConsent's shape, and every reason it gives applies unchanged: the
// launch is on the main thread, a turn reaches here from any, the wait is
// unbounded because the wait is a person, and the scope that started the turn
// is what cancels it.
//
// It reuses the same overlay rather than growing a second one. A plan and an
// action are the same question at two sizes, and a second window would be a
// second set of decisions about where it sits, what it does to touches
// underneath, and what happens when the service is torn down while it is up.
//
// No service means no, for AndroidConsent's reason and one more that is worse
// here. Ask refuses a single action when nobody can be asked; this refuses a
// whole turn, so somebody who has not switched the service on gets an answer
// naming that rather than a turn that appears to have failed.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.Approval
import com.getlora.wattrouter.Plan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A plan as the words somebody reads.
 *
 * The model's own sentence first when it wrote one, then the tools it wants
 * run, one per line. Its words rather than a rendering of them: a plan
 * paraphrased is a plan somebody approved and the agent did not make.
 *
 * The steps are named even when the sentence covers them. A model that says it
 * will "check the calendar" and then asks for `open_app` is the case this is
 * for, and it is invisible if only the sentence is shown.
 *
 * # Arguments
 * * `plan`: what the turn is about to do, WHERE its steps are non-empty. A plan
 *   with none is approved without being put, which is [Planned]'s rule.
 *
 * # Returns
 * The question, ending in one.
 */
internal fun putting(plan: Plan): String {
    val said = plan.says.trim()
    val steps = plan.steps.joinToString("\n") { "  $it" }
    return if (said.isEmpty()) {
        "It wants to run:\n$steps\n\nLet it?"
    } else {
        "$said\n\nIt wants to run:\n$steps\n\nLet it?"
    }
}

/** The plan, over whatever is in front, once per turn. */
class AndroidApproval : Approval {

    override suspend fun mayI(plan: Plan): Boolean {
        // Read now rather than held, as AndroidConsent reads it: the person can
        // switch the service off between turns, and a held reference would put
        // a window on a service the system has already torn down.
        val service = DrivingService.connected ?: return false

        val answered = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            service.ask(putting(plan)) { answered.complete(it) }
            // Nowhere to show it, which is the same answer as no service. A
            // phone that refuses the overlay is one where nobody can be asked.
            if (!service.asking) answered.complete(false)
        }

        return try {
            answered.await()
        } finally {
            // However this ended, including cancelled. A question left up after
            // its turn is one somebody answers into nothing, and the next turn
            // would find the screen it is about to read covered by it.
            withContext(NonCancellable + Dispatchers.Main) { service.stopAsking() }
        }
    }
}
