// ReplayTest.kt: how much of a turn is worth keeping.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// On the JVM, against the store alone. Which calls count as doing something is
// Recorded's question and its test's; this one is about the bound, which is the
// part with a cost attached: a capture is a full-resolution PNG as base64, and
// a turn may act twenty-five times.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private fun acted(named: String) = Acted(named, Image("data:image/png;base64,aGk="))

    @Test
    fun aFreshOneHasNothingInIt() {
        assertTrue(Replay().steps.isEmpty())
    }

    @Test
    fun stepsComeBackOldestFirst() {
        // The order they happened, which is the order somebody reads a replay
        // in even though the end is what they are usually looking for.
        val replay = Replay()

        replay.add(acted("opened the clock"))
        replay.add(acted("tapped start"))

        assertEquals(listOf("opened the clock", "tapped start"), replay.steps.map { it.did })
    }

    @Test
    fun theOldestGoesWhenTheBoundIsReached() {
        // A replay is read backwards from what just happened, so the beginning
        // is what can go. Refusing the newest instead would mean a turn that
        // went wrong at step twenty shows nothing about step twenty.
        val replay = Replay(most = 3)

        repeat(5) { replay.add(acted("step $it")) }

        assertEquals(3, replay.steps.size)
        assertEquals(listOf("step 2", "step 3", "step 4"), replay.steps.map { it.did })
    }

    @Test
    fun theDefaultBoundIsWellUnderTheBudget() {
        // Twenty-five captures is tens of megabytes held beside a model
        // conversation. This asserts the two numbers stay apart rather than the
        // exact value, so tuning one does not silently make them equal.
        assertTrue("${Replay.MOST}", Replay.MOST < Budget.DEFAULT)
    }

    @Test
    fun aTurnStartsWithNone() {
        // Where the budget is reset, and for its reason: a resumed turn showing
        // the previous turn's screens is a replay of the wrong thing.
        val replay = Replay()
        replay.add(acted("tapped send"))

        replay.beginTurn()

        assertTrue(replay.steps.isEmpty())
    }

    @Test
    fun aStepWithNoPictureIsStillAStep() {
        // The service can be off and a window may not have arrived. What was
        // done is worth listing without a screen to show for it.
        val replay = Replay()

        replay.add(Acted("tapped send"))

        assertEquals("tapped send", replay.steps.single().did)
        assertNull(replay.steps.single().screen)
    }
}
