// ChatScreenTest.kt: what the field holds after somebody speaks into it.
//
// History
//   2026-08-11  A. Sigdel  Created with #659.
//
// On the JVM against `spokenInto` alone, in ConnectionsScreenTest's shape and
// for its reason: the screen is Compose and belongs on a device, and the one
// decision in it that is not layout is what a transcript does to whatever the
// field already held.
//
// That decision is small and it is the whole safety argument of #659 in
// miniature. A press of the microphone must not be a way to lose a sentence,
// which is why it appends and why the first case below is the one to keep.

package com.getlora.wattrouter.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenTest {

    @Test
    fun whatWasAlreadyTypedSurvivesBeingSpokenOver() {
        // The case this exists for. Substituting is simpler and throws away
        // what somebody typed before they pressed, which is the loss the whole
        // approach is arranged to avoid, only smaller.
        assertEquals(
            "remind me to call Ada about the invoice",
            spokenInto("remind me to", "call Ada about the invoice"),
        )
    }

    @Test
    fun anEmptyFieldGainsNoLeadingSpace() {
        // The ordinary press, and a leading space would reach the send path and
        // be trimmed nowhere: TurnDriver refuses blank text and this is not it.
        assertEquals("what is on today", spokenInto("", "what is on today"))
    }

    @Test
    fun aFieldEndingInASpaceDoesNotProduceTwo() {
        // A keyboard puts one there after a word, so this is the common way in
        // rather than a contrived one.
        assertEquals("tell Ada it is done", spokenInto("tell Ada ", "it is done"))
    }

    @Test
    fun aTranscriptWithSpaceAroundItIsTrimmed() {
        assertEquals("say that again", spokenInto("", "  say that again  "))
    }

    @Test
    fun nothingSpokenLeavesTheFieldAsItWas() {
        // Heard.Words is documented as never blank, so this is defence rather
        // than a case the seam produces. It costs one filter and it means the
        // field cannot be emptied or padded by a press that heard nothing.
        assertEquals("half a thought", spokenInto("half a thought", ""))
        assertEquals("half a thought", spokenInto("half a thought", "   "))
        assertEquals("", spokenInto("", ""))
    }
}
