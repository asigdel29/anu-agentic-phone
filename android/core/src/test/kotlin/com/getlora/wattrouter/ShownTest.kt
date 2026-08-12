// ShownTest.kt: which commands reach a shell, and what is put to somebody first.
//
// History
//   2026-08-12  A. Sigdel  Created with #673.
//
// On the JVM against a recording Terminal, in SignedTest's shape: nothing here
// starts a process, and every decision under test is about a mode and a string.
//
// Two to read first. A refused command must not run, which is the whole of what
// a gate is, and the command shown must be the command that runs, whole, since a
// prompt showing a shortened command is one somebody approves without having
// seen what it ends with.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every command that reached a shell, in the order it arrived. */
private class Running : Terminal {
    val ran = mutableListOf<String>()

    override suspend fun run(command: String): Ran =
        Ran.Finished(0, "did it", 0).also { ran += command }
}

/** Somebody who answers the same way every time, and remembers being asked. */
private class Decided(private val answer: Boolean) : Consent {
    val asked = mutableListOf<Intent>()

    override suspend fun mayI(intent: Intent): Boolean {
        asked += intent
        return answer
    }
}

class ShownTest {

    @Test
    fun autoRunsWithoutAsking() = runTest {
        val shell = Running()
        val consent = Decided(answer = false)

        Shown(shell, { Autonomy.AUTO }, consent).run("git status")

        assertEquals(emptyList<Intent>(), consent.asked)
        assertEquals(listOf("git status"), shell.ran)
    }

    @Test
    fun askShowsTheCommandItselfRatherThanTheToolName() = runTest {
        // The whole issue. run_command is what plan mode approved, and it is
        // the same name for `git status` and for `rm -rf .`.
        val consent = Decided(answer = true)

        Shown(Running(), { Autonomy.ASK }, consent).run("rm -rf .")

        val intent = consent.asked.single()
        assertEquals("run", intent.verb)
        assertEquals("rm -rf .", intent.what)
    }

    @Test
    fun planAsksHereWhereItDoesNotAtThePhone() = runTest {
        // Confirmed treats Plan as Auto, because Planned put the round's tool
        // names to somebody already. The name it put was run_command.
        val shell = Running()
        val consent = Decided(answer = true)

        Shown(shell, { Autonomy.PLAN }, consent).run("cargo test")

        assertEquals(listOf("cargo test"), shell.ran)
        assertEquals("cargo test", consent.asked.single().what)
    }

    @Test
    fun aRefusedCommandDoesNotRun() = runTest {
        val shell = Running()

        val answered = Shown(shell, { Autonomy.ASK }, Decided(answer = false)).run("rm -rf .")

        assertEquals(emptyList<String>(), shell.ran)
        assertTrue("$answered", answered is Ran.Refused)
    }

    @Test
    fun theRefusalNamesAPersonRatherThanARule() = runTest {
        // A model told a rule refused it looks for another way through, and a
        // shell has many. One told a person did not allow it stops and says so.
        val answered = Shown(Running(), { Autonomy.ASK }, Decided(false)).run("curl example")

        val why = (answered as Ran.Refused).why
        assertTrue(why, why.contains("person using the phone did not allow"))
        assertTrue(why, why.contains("Do not try it another way or in pieces"))
        // Not the command itself. The model wrote it and already knows, and
        // repeating it is where a pasted secret gets copied into a transcript.
        assertTrue(why, !why.contains("curl example"))
    }

    @Test
    fun theWholeCommandIsShownRatherThanItsFront() = runTest {
        // An elision is where the second half of `ls; rm -rf .` hides.
        val long = "echo " + "a".repeat(400) + " && rm -rf ."
        val consent = Decided(answer = true)

        Shown(Running(), { Autonomy.ASK }, consent).run(long)

        assertEquals(long, consent.asked.single().what)
    }

    @Test
    fun theModeIsReadPerCommandRatherThanHeldForATurn() = runTest {
        // Somebody who turns this off has stopped wanting to see each command,
        // and should not go on being asked until the turn ends.
        var mode = Autonomy.ASK
        val shell = Running()
        val consent = Decided(answer = true)
        val shown = Shown(shell, { mode }, consent)

        shown.run("git status")
        mode = Autonomy.AUTO
        shown.run("git diff")

        assertEquals(1, consent.asked.size)
        assertEquals(listOf("git status", "git diff"), shell.ran)
    }

    @Test
    fun anApprovedCommandAnswersWhatTheShellAnswered() = runTest {
        // The gate decides whether there is a Ran, and never what is in one.
        val answered = Shown(Running(), { Autonomy.ASK }, Decided(true)).run("ls")

        assertEquals(Ran.Finished(0, "did it", 0), answered)
    }
}
