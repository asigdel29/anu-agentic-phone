// AndroidApprovalTest.kt: what a plan reads as, before anybody sees one.
//
// History
//   2026-08-10  A. Sigdel  Created with #595.
//
// On the JVM against `putting` alone. The overlay, the service and the waiting
// are AndroidConsent's shape and are asked about on a device; the words are a
// decision, and this is where it can be read.
//
// The case that matters is the second. A model that says it will "check the
// calendar" and then asks for `open_app` is exactly what somebody is approving
// once and unattended, and it is invisible if only the sentence is shown.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.Plan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidApprovalTest {

    @Test
    fun theModelsOwnSentenceComesFirst() {
        // Its words rather than a rendering of them: a plan paraphrased is a
        // plan somebody approved and the agent did not make.
        val put = putting(Plan("I will check when you are free", listOf("read_calendar")))

        assertTrue(put, put.startsWith("I will check when you are free"))
    }

    @Test
    fun theStepsAreNamedEvenWhenTheSentenceCoversThem() {
        val put = putting(Plan("I will check the calendar", listOf("open_app", "read_screen")))

        assertTrue(put, put.contains("open_app"))
        assertTrue(put, put.contains("read_screen"))
    }

    @Test
    fun aModelThatSaidNothingStillHasAPlan() {
        // Straight to tools, which is common and is the case where the steps
        // are the whole of what there is to read.
        val put = putting(Plan("", listOf("tap")))

        assertTrue(put, put.contains("tap"))
        assertFalse(put, put.startsWith("\n"))
    }

    @Test
    fun whitespaceIsNotASentence() {
        // A model that emitted only a newline has said nothing, and rendering
        // it would put a blank line above the steps for no reason.
        val put = putting(Plan("  \n ", listOf("tap")))

        assertTrue(put, put.startsWith("It wants to run:"))
    }

    @Test
    fun itEndsByAskingSomething() {
        // The whole of the wording, as ModesTest asserts of the per-action
        // question: a dialog with two buttons and no question is two buttons.
        listOf(
            Plan("I will do a thing", listOf("tap")),
            Plan("", listOf("tap", "type_text")),
        ).forEach { plan ->
            val put = putting(plan)
            assertTrue(put, put.trim().endsWith("?"))
        }
    }

    @Test
    fun eachStepIsOnItsOwnLine() {
        // A run-together list is one somebody skims. One per line is what makes
        // three steps read as three things rather than as a sentence.
        val put = putting(Plan("", listOf("open_app", "tap", "type_text")))

        val named = put.lines().count { it.trim() in setOf("open_app", "tap", "type_text") }
        assertTrue(put, named == 3)
    }
}
