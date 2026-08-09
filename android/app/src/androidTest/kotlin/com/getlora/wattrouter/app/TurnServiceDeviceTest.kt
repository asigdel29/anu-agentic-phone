// TurnServiceDeviceTest.kt — the Stop button reaches the turn.
//
// History
//   2026-08-09  A. Sigdel  Created with the fix for #470.
//
// On a device because a Service is a system component: onStartCommand is the
// framework's to call, and a test that called it by hand would be asserting
// about a method rather than about a button.
//
// The intent comes from TurnService.stopping rather than from an action string
// written here. A test naming the action itself keeps passing after somebody
// renames the constant, which is the failure this file exists to prevent a
// second version of.

package com.getlora.wattrouter.app

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnServiceDeviceTest {

    @After
    fun finish() {
        TurnService.onStop = null
    }

    @Test
    fun theStopButtonStopsTheTurn() {
        // Before #470 this failed: STOP called stopSelf() and nothing else, so
        // the notification went away and the turn carried on with nothing left
        // on screen to say it was running.
        val stopped = CountDownLatch(1)
        TurnService.onStop = { stopped.countDown() }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startService(TurnService.stopping(context))

        assertTrue(
            "the Stop action did not reach the turn",
            stopped.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
    }

    @Test
    fun stoppingWithNothingRunningIsNotACrash() {
        // The button is on a notification the system can deliver a press from
        // after the turn has already ended, and the holder is null then. That
        // has to be a no-op rather than the process going down.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startService(TurnService.stopping(context))
    }

    private companion object {
        /** Generous: it is bounding a system round trip, not measuring one. */
        const val WAIT_SECONDS = 5L
    }
}
