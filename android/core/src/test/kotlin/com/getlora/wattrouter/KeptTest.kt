// KeptTest.kt: which commands reach the record, and what is kept about them.
//
// History
//   2026-09-17  A. Sigdel  Created with #713.
//
// On the JVM, in SignedTest's shape: Running is ShownTest's fake, nothing here
// starts a process, and every decision under test is about a record and a
// string.
//
// The case to read first is the refused one, for RecordedTest's reason: a
// scrollback showing a command the consent gate stopped would show a command
// that did not run. What makes that true is where Kept sits rather than a rule
// inside it, so that is what the test builds: the gate outside, and nothing
// left in the record.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptTest {

    @Test
    fun aGateRefusedCommandIsNeverKept() = runTest {
        // Recorded's ordering rule, tested at this seam: the refusal is
        // Shown's answer and Shown alone, because Kept is inside it.
        val shell = Running()
        val scrollback = Scrollback()

        val answered = Shown(Kept(shell, scrollback), { Autonomy.ASK }, Decided(answer = false))
            .run("rm -rf .")

        assertTrue("$answered", answered is Ran.Refused)
        assertEquals(emptyList<String>(), shell.ran)
        assertTrue(scrollback.commands.isEmpty())
    }

    @Test
    fun anApprovedCommandIsKeptWithWhatCameBack() = runTest {
        val shell = Running()
        val scrollback = Scrollback()

        Shown(Kept(shell, scrollback), { Autonomy.ASK }, Decided(answer = true)).run("git status")

        assertEquals(listOf("git status"), shell.ran)
        assertEquals("git status", scrollback.commands.single().command)
    }

    @Test
    fun whatIsKeptIsExactlyWhatTheTerminalAnswered() = runTest {
        // The Ran is recorded as it came, never rebuilt or summarised, so the
        // output in the record cannot exceed the output the model itself was
        // shown. All three of Ran's shapes ride the same path.
        listOf(
            Ran.Finished(0, "did it", 0),
            Ran.TimedOut("got this far", 2),
            Ran.Refused("the command could not be started"),
        ).forEach { answer ->
            val scrollback = Scrollback()

            val answered = Kept(Running(answer), scrollback).run("git status")

            assertEquals(answer, answered)
            assertEquals(answer, scrollback.commands.single().ran)
        }
    }

    @Test
    fun everyCommandReachingTheSeamIsKept() = runTest {
        // Why this is at the seam: a second thing that runs a command reaches
        // the shell through one object and is kept without its author knowing
        // this exists.
        val scrollback = Scrollback()
        val kept = Kept(Running(), scrollback)

        kept.run("git status")
        kept.run("git diff")
        kept.run("git log")

        assertEquals(
            listOf("git status", "git diff", "git log"),
            scrollback.commands.map { it.command },
        )
    }

    @Test
    fun theOldestGoesWhenTheBoundIsReached() = runTest {
        // Replay.add's rule: the end of the history is what somebody looks
        // for, so the beginning is what can go.
        val scrollback = Scrollback(most = 3)
        val kept = Kept(Running(), scrollback)

        repeat(5) { kept.run("step $it") }

        assertEquals(3, scrollback.commands.size)
        assertEquals(listOf("step 2", "step 3", "step 4"), scrollback.commands.map { it.command })
    }

    @Test
    fun theDefaultBoundCoversATurnRatherThanAFewCommands() {
        // The mirror of ReplayTest's bound test, in the other direction: text
        // is cheap and history is the point, so the bound sits above a turn's
        // budget of commands rather than under it.
        assertTrue("${Scrollback.MOST}", Scrollback.MOST >= Budget.DEFAULT)
    }
}
