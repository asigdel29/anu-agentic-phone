// PlanTest.kt: the rule for when somebody is interrupted.
//
// History
//   2026-08-10  A. Sigdel  Created with #595.
//
// On the JVM and without an Agent: this is three conditions and they are worth
// reading apart from the loop that calls them. What the loop does with the
// answer is AgentPlanTest's.
//
// The case that matters most is the second silence. A first round that asks for
// no tool has nothing to approve, and a dialog over an answer is a dialog
// people learn to dismiss without reading, which costs the mode its meaning on
// the turns where it has one.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    /** Records what it was shown and answers as told. */
    private class Answers(private val answer: Boolean) : Approval {
        val seen = mutableListOf<Plan>()
        override suspend fun mayI(plan: Plan): Boolean {
            seen += plan
            return answer
        }
    }

    private val acting = Plan("I will open the clock", listOf("open_app", "read_screen"))

    @Test
    fun autoIsNotGovernedHere() = runTest {
        val approval = Answers(answer = false)

        assertTrue(Planned({ Autonomy.AUTO }, approval).approved(acting))
        assertTrue(approval.seen.isEmpty())
    }

    @Test
    fun askIsNotGovernedHereEither() = runTest {
        // Ask fires per action at the Phone seam. Firing here as well would put
        // two questions in front of somebody for one turn, and the second would
        // be about work the first already approved.
        val approval = Answers(answer = false)

        assertTrue(Planned({ Autonomy.ASK }, approval).approved(acting))
        assertTrue(approval.seen.isEmpty())
    }

    @Test
    fun planAsksAndAnswersWhatItWasTold() = runTest {
        val yes = Answers(answer = true)
        val no = Answers(answer = false)

        assertTrue(Planned({ Autonomy.PLAN }, yes).approved(acting))
        assertFalse(Planned({ Autonomy.PLAN }, no).approved(acting))
        assertEquals(1, yes.seen.size)
        assertEquals(acting, yes.seen.single())
    }

    @Test
    fun aPlanThatTouchesNothingIsNotWorthADialog() = runTest {
        // The model answered. Nothing will reach the phone.
        val approval = Answers(answer = false)

        assertTrue(Planned({ Autonomy.PLAN }, approval).approved(Plan("Tuesday", emptyList())))
        assertTrue(approval.seen.isEmpty())
    }

    @Test
    fun sayingNothingIsStillAPlanWhenItWantsATool() = runTest {
        // A model that goes straight to tools has written no text, and the
        // steps are what makes it worth asking about rather than the prose.
        val approval = Answers(answer = false)

        assertFalse(Planned({ Autonomy.PLAN }, approval).approved(Plan("", listOf("tap"))))
        assertEquals(1, approval.seen.size)
    }

    @Test
    fun theModeIsReadWhenTheQuestionWouldBeAsked() = runTest {
        // Somebody who changed their mind between typing and the model
        // answering meant this turn, which is Confirmed's rule for the same
        // reason. Held rather than read, this would ask on the turn after.
        var mode = Autonomy.AUTO
        val approval = Answers(answer = true)
        val planned = Planned({ mode }, approval)

        assertTrue(planned.approved(acting))
        assertTrue(approval.seen.isEmpty())

        mode = Autonomy.PLAN
        assertTrue(planned.approved(acting))
        assertEquals(1, approval.seen.size)
    }

    @Test
    fun whatAModelIsToldNamesAPersonAndTheShapeOfTheMode() = runTest {
        // A model told a rule refused it looks for another way through; a model
        // told a person declined stops and says so. Naming the shape as well
        // lets it say what it would have done rather than offering to try the
        // first step alone.
        assertTrue(Planned.DECLINED.contains("the person using the phone"))
        assertTrue(Planned.DECLINED.contains("asked once"))
        assertTrue(Planned.DECLINED.contains("Do not try any part of it another way"))
    }
}
