// AutonomyTest.kt — what is asked, what is not, and what the prompt says.
//
// History
//   2026-08-10  A. Sigdel  Created with #552.
//
// On the JVM, which is where all of this belongs: every decision in Autonomy.kt
// is about a mode and a handle, and none of it needs a phone to be wrong.
//
// The test to read first is the shadowing one. A prompt that says Cancel over
// an action that sends is worse than no prompt at all, and it is reachable —
// the model writes the handle, and resolve keys on the durable field while the
// friendly one is only decoration. The rest of the suite is in #554: the
// defence ships alone to stay inside the size guard, as #530 did.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Acting : Phone {
    var acts = 0

    override suspend fun attached() = true

    override suspend fun barredNow(): String? = null

    override suspend fun read(): Reading? = null

    override suspend fun apps(): List<Launchable> = emptyList()

    override suspend fun tap(at: Handle, from: Generation) = Done.Did(null).also { acts++ }

    override suspend fun type(at: Handle, from: Generation, text: String) =
        Done.Did(null).also { acts++ }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward) =
        Done.Did(null).also { acts++ }

    override suspend fun navigate(way: Way) = Done.Did(null).also { acts++ }

    override suspend fun open(packageName: String) = Done.Did(null).also { acts++ }
}

/** Somebody who answers the same way every time, and remembers being asked. */
private class Decided(private val answer: Boolean) : Consent {
    val asked = mutableListOf<Intent>()

    override suspend fun mayI(intent: Intent): Boolean {
        asked += intent
        return answer
    }
}

class AutonomyTest {
    private val handle = Handle("send", "button", "Send", null, 0)
    private val generation = Generation("k3f9", 4)

    fun autoDoesNotAsk() = runTest {
        val phone = Acting()
        val consent = Decided(answer = false)

        Confirmed(phone, { Autonomy.AUTO }, consent).tap(handle, generation)

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
}
