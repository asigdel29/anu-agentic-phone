// ChatScreenTest.kt: what the field holds after somebody speaks into it.
//
// History
//   2026-08-11  A. Sigdel  Created with #659.
//   2026-09-17  A. Sigdel  The result's tail line, #713.
//
// On the JVM against `spokenInto` and `clipped`, in ConnectionsScreenTest's
// shape and for its reason: the screen is Compose and belongs on a device, and
// these are the decisions in it that are not layout. A press of the microphone
// must not be a way to lose a sentence, and a result cut to six lines must say
// so rather than read as the whole of what the tool answered.

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

    @Test
    fun aResultInsideTheBoundIsShownWhole() {
        // The bound is a display choice, not a fact about the result: under
        // and at it, the row shows everything there is.
        assertEquals("one\ntwo", clipped("one\ntwo"))
    }

    @Test
    fun theSixthLineCarriesNoTail() {
        // Exactly the bound is not a cut. A tail saying "and 0 more" would be
        // a lie repeated on every row of exactly six lines.
        val six = List(6) { "line $it" }.joinToString("\n")
        assertEquals(six, clipped(six))
    }

    @Test
    fun aLongerResultSaysWhatWasCut() {
        // The tail line is describe's, which is how everything here that
        // truncates says so. Without it, six lines of a twenty-line build log
        // read as the whole of what the command printed, #713's defect.
        val ten = List(10) { "line $it" }.joinToString("\n")
        assertEquals(
            List(6) { "line $it" }.joinToString("\n") + "\nand 4 more not shown",
            clipped(ten),
        )
    }

    @Test
    fun anEmptyResultStaysEmpty() {
        // A run_command's blank output is worded "it printed nothing" below
        // this layer, so the row is never handed one for it. Other tools'
        // results are not so worded, and the row shows what it is given.
        assertEquals("", clipped(""))
    }
}
