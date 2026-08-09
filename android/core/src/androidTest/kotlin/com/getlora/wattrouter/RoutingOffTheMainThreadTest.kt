// RoutingOffTheMainThreadTest.kt — deciding does not hold the UI thread.
//
// History
//   2026-08-09  A. Sigdel  Created with the fix for #474.
//
// On a device because it needs a real Core: the switch is inside Core.routing,
// and a fake Routing would be testing the fake.
//
// The assertion is ordering rather than a thread name, and that is deliberate.
// What a thread name would prove is where a line ran; what somebody actually
// wants to know is whether the main thread was free while it ran, which is the
// difference between a smooth turn and an ANR. So a message is posted to the
// main looper while a decision is outstanding, and the test asserts it got to
// run first.
//
// Before #474 it could not. Routing.decide was an ordinary function, the turn
// loop collected on the main thread, and a blocking native call held the looper
// until it returned — so the posted message ran after the decision rather than
// during it, and this test recorded them in the other order.

package com.getlora.wattrouter

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingOffTheMainThreadTest {

    @Test
    fun aDecisionLeavesTheMainThreadFreeWhileItRuns() {
        val core = requireNotNull(Core.open("nw-test")) { "the core did not open" }
        core.use {
            val routing = it.routing()
            val body = Conversation()
                .apply { append(Message.user("what time is it")) }
                .body()

            val order = mutableListOf<String>()

            runBlocking(Dispatchers.Main) {
                val deciding = launch {
                    routing.decide(body, "")
                    order += DECIDED
                }
                // Queued behind the coroutine above. It runs only when the
                // looper is free, which is the whole question.
                Handler(Looper.getMainLooper()).post { order += TICK }
                deciding.join()
            }

            assertEquals(
                "the main thread was held for the whole decision",
                listOf(TICK, DECIDED),
                order,
            )
        }
    }

    private companion object {
        const val TICK = "the looper ran something else"
        const val DECIDED = "the decision came back"
    }
}
