// AutonomyTest.kt: what is asked, what is not, and what the prompt says.
//
// History
//   2026-08-10  A. Sigdel  Created with #552.
//   2026-08-10  A. Sigdel  The six held back for the size guard, with #554.
//
// On the JVM, which is where all of this belongs: every decision in Autonomy.kt
// is about a mode and a handle, and none of it needs a phone to be wrong.
//
// The test to read first is the shadowing one. A prompt that says Cancel over
// an action that sends is worse than no prompt at all, and it is reachable:
// the model writes the handle, and resolve keys on the durable field while the
// friendly one is only decoration.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Acting : Phone {
    var acts = 0
    var reads = 0

    override suspend fun attached() = true

    override suspend fun barredNow(): String? = null

    override suspend fun read(): Reading? = null.also { reads++ }

    override suspend fun apps(): List<Launchable> = emptyList<Launchable>().also { reads++ }

    override suspend fun tap(at: Handle, from: Generation) = Done.Did(null).also { acts++ }

    override suspend fun type(at: Handle, from: Generation, text: String) =
        Done.Did(null).also { acts++ }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward) =
        Done.Did(null).also { acts++ }

    override suspend fun navigate(way: Way) = Done.Did(null).also { acts++ }

    override suspend fun open(packageName: String) = Done.Did(null).also { acts++ }
}

/**
 * Somebody who answers the same way every time, and remembers being asked.
 *
 * Not private, and here rather than in each file that wants one: [Consent] is
 * declared beside [Autonomy], and this is the fake for it. `ShownTest` gates a
 * `Terminal` through the same seam and had a byte-identical copy, which Kotlin
 * reads as a redeclaration rather than as two fakes.
 */
internal class Decided(private val answer: Boolean) : Consent {
    val asked = mutableListOf<Intent>()

    override suspend fun mayI(intent: Intent): Boolean {
        asked += intent
        return answer
    }
}

class AutonomyTest {
    private val handle = Handle("send", "button", "Send", null, 0)
    private val generation = Generation("k3f9", 4)

    @Test
    fun autoDoesNotAsk() = runTest {
        val phone = Acting()
        val consent = Decided(answer = false)

        Confirmed(phone, { Autonomy.AUTO }, consent).tap(handle, generation)

        assertEquals(emptyList<Intent>(), consent.asked)
        assertEquals(1, phone.acts)
    }

    @Test
    fun anApprovedPlanRunsWithoutBeingAskedAgain() = runTest {
        // PLAN is AUTO at this seam by design: the approval was given once, at
        // the top of the turn. A plan that then asks per action is Ask with
        // extra steps.
        val phone = Acting()
        val consent = Decided(answer = false)

        Confirmed(phone, { Autonomy.PLAN }, consent).navigate(Way.BACK)

        assertEquals(emptyList<Intent>(), consent.asked)
        assertEquals(1, phone.acts)
    }

    @Test
    fun askAsksBeforeEveryAction() = runTest {
        val phone = Acting()
        val consent = Decided(answer = true)
        val confirmed = Confirmed(phone, { Autonomy.ASK }, consent)

        confirmed.tap(handle, generation)
        confirmed.type(handle, generation, "hello")
        confirmed.scroll(handle, generation, Onward.FORWARD)
        confirmed.navigate(Way.HOME)
        confirmed.open("com.android.deskclock")

        assertEquals(5, consent.asked.size)
        assertEquals(5, phone.acts)
    }

    @Test
    fun nothingHappensWhenTheAnswerIsNo() = runTest {
        val phone = Acting()

        val done = Confirmed(phone, { Autonomy.ASK }, Decided(answer = false))
            .tap(handle, generation)

        assertEquals(0, phone.acts)
        assertTrue("$done", done is Done.Refused)
    }

    @Test
    fun theRefusalNamesAPersonRatherThanARule() = runTest {
        // A model told a rule refused it looks for another way through. One
        // told a person did not allow it stops, which is the point of asking.
        // Not "said no": #556 found the case where nobody could be asked.
        val done = Confirmed(Acting(), { Autonomy.ASK }, Decided(answer = false))
            .tap(handle, generation)

        val why = (done as Done.Refused).why
        assertTrue(why, why.contains("person using the phone did not allow"))
        // "send", the id, not "Send", the label. The refusal is worded from
        // the same field the prompt was, so the two cannot drift apart.
        assertTrue(why, why.contains("tap send"))
        assertTrue(why, why.contains("Do not try it another way"))
    }

    @Test
    fun askingIsNotRequiredToLook() = runTest {
        // A turn that must ask before it can see cannot say what it wants to do.
        val phone = Acting()
        val consent = Decided(answer = false)
        val confirmed = Confirmed(phone, { Autonomy.ASK }, consent)

        confirmed.read()
        confirmed.apps()
        confirmed.barredNow()
        confirmed.attached()

        assertEquals(emptyList<Intent>(), consent.asked)
        assertEquals(2, phone.reads)
    }

    @Test
    fun aHandleCannotSayCancelOverAnActionThatSends() = runTest {
        // The one that matters. resolve keys on viewId when there is one, so
        // this handle taps Send; a prompt built from the friendliest field
        // would ask about Cancel and get a yes for the wrong control.
        val spoofed = Handle(viewId = "send", role = "button", text = "Cancel")
        val consent = Decided(answer = true)

        Confirmed(Acting(), { Autonomy.ASK }, consent).tap(spoofed, generation)

        assertEquals("send", consent.asked.single().what)
    }

    @Test
    fun theWordShownFollowsResolvesOwnPrecedence() {
        // Not the same assertion as above: that one proves the durable field
        // wins, this proves the whole order matches, so a later change to one
        // and not the other is caught here rather than on somebody's phone.
        assertEquals("send", asked(Handle(viewId = "send", text = "Cancel", description = "x")))
        assertEquals("Cancel", asked(Handle(text = "Cancel", description = "x")))
        assertEquals("x", asked(Handle(description = "x")))
        assertEquals("something it did not name", asked(Handle(role = "button")))
    }

    @Test
    fun theTextBeingTypedIsNotInThePrompt() = runTest {
        // It can be a paragraph or a pasted password, and a dialog that has to
        // be scrolled to reach the button is one people dismiss unread.
        val consent = Decided(answer = true)

        Confirmed(Acting(), { Autonomy.ASK }, consent)
            .type(handle, generation, "hunter2 and several more lines of it")

        val intent = consent.asked.single()
        assertEquals("type into", intent.verb)
        assertTrue("$intent", !intent.what.contains("hunter2"))
    }

    @Test
    fun theModeIsReadPerActionRatherThanHeldForATurn() = runTest {
        // Somebody who turns this off has stopped wanting to be asked, and
        // should not go on being asked until the turn ends.
        var mode = Autonomy.ASK
        val phone = Acting()
        val consent = Decided(answer = true)
        val confirmed = Confirmed(phone, { mode }, consent)

        confirmed.tap(handle, generation)
        mode = Autonomy.AUTO
        confirmed.tap(handle, generation)

        assertEquals(1, consent.asked.size)
        assertEquals(2, phone.acts)
    }

    @Test
    fun aDeclinedActionCostsNoBudget() = runTest {
        // Why the wrapping order is Confirmed(Budgeted(phone)). The other way
        // round, a turn refused twenty times has nothing left for the action
        // they would have allowed.
        val phone = Acting()
        val budget = Budget(most = 2)
        val confirmed = Confirmed(Budgeted(phone, budget), { Autonomy.ASK }, Decided(false))
        budget.beginTurn()

        repeat(10) { confirmed.tap(handle, generation) }

        assertEquals(2, budget.left)
    }
}
