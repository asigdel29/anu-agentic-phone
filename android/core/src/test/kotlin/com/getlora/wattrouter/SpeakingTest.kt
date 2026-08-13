// SpeakingTest.kt: which line of a turn is the one to say.
//
// History
//   2026-08-13  A. Sigdel  Created with #709.
//
// On the JVM, and this is the whole of what a test can reach: the seam needs a
// phone and the control is Compose, so worthSaying is the one decision in this
// change that is neither. It is also the one #601 deferred the change on, which
// is why it is a function rather than a branch inside the composable.
//
// The test to read first is the one where a turn answers and then fails.
// Reading the answer aloud there reports a turn that worked.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakingTest {
    private var next = 0
    private fun answered(text: String) = Row.Answered(next++, "mid", text)
    private fun used(tool: String, result: String?) = Row.Used(next++, tool, result)
    private fun failed(reason: String) = Row.Failed(next++, reason)

    @Test
    fun theAnswerIsWhatIsSaid() {
        assertEquals("Tuesday", worthSaying(listOf(Row.Said(0, "when"), answered("Tuesday"))))
    }

    @Test
    fun fiftyLinesOfToolOutputAreNotSaid() {
        // #601 deferred this change on exactly this case. The transcript
        // already separated the two and nothing here had to invent a rule.
        val rows = listOf(
            Row.Said(0, "check the build"),
            used("run_command", (1..50).joinToString("\n") { "line $it" }),
            answered("It builds."),
        )

        assertEquals("It builds.", worthSaying(rows))
    }

    @Test
    fun aTurnThatFailedIsSaidRatherThanPassedOver() {
        // The pocket case, and the reason this is not answers only: somebody
        // who cannot see the screen most wants to be told the one that did not
        // work.
        assertEquals("the provider refused the key", worthSaying(listOf(failed("the provider refused the key"))))
    }

    @Test
    fun aTurnThatAnsweredAndThenFailedEndsFailed() {
        // The last of either rather than the last answer. Reading the answer
        // aloud here would report a turn that worked.
        val rows = listOf(answered("Here is the file."), failed("the second round could not start"))

        assertEquals("the second round could not start", worthSaying(rows))
    }

    @Test
    fun aTurnWithNothingToSayIsNotSpoken() {
        assertNull(worthSaying(emptyList()))
        assertNull(worthSaying(listOf(Row.Said(0, "hello"), used("read_screen", "a screen"))))
    }

    @Test
    fun anEmptyAnswerIsNothingToSayRatherThanSilenceToSpeak() {
        // A model that streamed no text leaves an Answered with an empty
        // string. Speaking it is a phone that clears its throat.
        assertNull(worthSaying(listOf(answered("   "))))
    }
}
