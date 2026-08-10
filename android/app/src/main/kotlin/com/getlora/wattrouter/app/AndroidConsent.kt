// AndroidConsent.kt — a question on the display, waited on from a turn.
//
// History
//   2026-08-10  A. Sigdel  Created with #556.
//
// The shape AndroidAsking already established for the permission dialog, for
// the same reasons: the launch has to happen on the main thread, a tool can
// reach here from any, and waiting is unbounded because the wait is a person.
// The scope that started the turn is what cancels it, which is what the stop
// button does.
//
// The one decision that is not AndroidAsking's: no service means no. The
// overlay lives on the accessibility service, and open_app is the tool that
// does not need one — it starts an activity from a Context. So a turn in Ask
// mode with the service switched off could otherwise launch an app with nobody
// asked. Refusing is the only safe answer, and Confirmed's wording had to stop
// saying somebody said no to be true of it.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.Consent
import com.getlora.wattrouter.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** How a question is worded, given what is about to happen. */
internal fun wording(intent: Intent): String = "${intent.verb} ${intent.what}?"

/** The question, over the app being driven. */
class AndroidConsent : Consent {

    override suspend fun mayI(intent: Intent): Boolean {
        // Read now rather than held, as AndroidPhone reads it: the person can
        // switch the service off mid-turn, and a held reference would put a
        // window on a service the system has already torn down.
        val service = DrivingService.connected ?: return false

        val answered = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            service.ask(wording(intent)) { answered.complete(it) }
            // Nowhere to show it. A phone that refuses the overlay is one where
            // nobody can be asked, which is the same answer as the service
            // being off rather than a reason to act unasked.
            if (!service.asking) answered.complete(false)
        }

        return try {
            answered.await()
        } finally {
            // However this ended, including cancelled. A question left on the
            // display after its turn is one somebody answers into nothing, and
            // the next turn would find the screen it is about to read covered
            // by it.
            withContext(NonCancellable + Dispatchers.Main) { service.stopAsking() }
        }
    }
}
