// TerminalToolsTest.kt: what the model reads after a command, for each of the
// three things one can do.
//
// History
//   2026-08-12  A. Sigdel  Created with #677.
//
// On the JVM against a Terminal that answers whatever a test hands it, in
// ShownTest's shape: nothing here starts a process, and every decision under
// test is about wording.
//
// The one to read first is that a status of zero is still said. A command that
// worked and printed nothing and one that failed and printed nothing are the
// same text without it.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Running is ShownTest's, deliberately shared rather than copied: it is the
// fake for Terminal, and this file needs the same recording of what reached a
// shell plus each of Ran's three shapes coming back out of it.

class TerminalToolsTest {

    @Test
    fun aCommandThatWorkedStillSaysItsStatus() = runTest {
        val tool = RunCommandTool(Running(Ran.Finished(0, "src\nbuild\n", 0)))

        val said = tool.run("""{"command":"ls"}""")

        assertTrue(said, said.startsWith("exit 0\n"))
        assertTrue(said, said.contains("src"))
    }

    @Test
    fun aCommandThatFailedIsAnAnswerRatherThanAThrow() = runTest {
        val tool = RunCommandTool(Running(Ran.Finished(128, "not a git repository", 0)))

        val said = tool.run("""{"command":"git status"}""")

        assertTrue(said, said.startsWith("exit 128\n"))
        assertTrue(said, said.contains("not a git repository"))
    }

    @Test
    fun theCommandReachesTheShellUntouched() = runTest {
        val shell = Running(Ran.Finished(0, "", 0))

        RunCommandTool(shell).run("""{"command":"echo one; echo two"}""")

        assertEquals(listOf("echo one; echo two"), shell.ran)
    }

    @Test
    fun outputThatWasCutSaysHowMuchIsMissing() = runTest {
        val tool = RunCommandTool(Running(Ran.Finished(0, "the front of it", 9134)))

        val said = tool.run("""{"command":"cat big"}""")

        assertTrue(said, said.contains("9134 more characters not shown"))
    }

    @Test
    fun aCommandThatPrintedNothingSaysSoRatherThanNothing() = runTest {
        val tool = RunCommandTool(Running(Ran.Finished(0, "", 0)))

        val said = tool.run("""{"command":"true"}""")

        assertEquals("exit 0\nit printed nothing", said)
    }

    @Test
    fun aKilledCommandIsToldApartFromAFailedOne() = runTest {
        val tool = RunCommandTool(Running(Ran.TimedOut("compiling", 0)))

        val said = tool.run("""{"command":"gradle build"}""")

        assertTrue(said, said.contains("still running after $PATIENCE seconds"))
        assertTrue(said, said.contains("compiling"))
        assertTrue(said, !said.contains("exit "))
    }

    @Test
    fun aShellThatNeverStartedAnswersItsWordsAndNoStatus() = runTest {
        val why = "the command could not be started: permission denied"
        val tool = RunCommandTool(Running(Ran.Refused(why)))

        assertEquals(why, tool.run("""{"command":"./build.sh"}"""))
    }

    @Test
    fun anEmptyCommandIsNotRunAtAll() = runTest {
        val shell = Running(Ran.Finished(0, "", 0))

        val said = RunCommandTool(shell).run("""{"command":"   "}""")

        assertEquals(emptyList<String>(), shell.ran)
        assertTrue(said, said.contains("needs something to run"))
    }

    @Test
    fun argumentsThatAreNotAnObjectAreAnAnswerRatherThanAThrow() = runTest {
        val shell = Running(Ran.Finished(0, "", 0))

        val said = RunCommandTool(shell).run("not json at all")

        assertEquals(emptyList<String>(), shell.ran)
        assertTrue(said, said.contains("needs something to run"))
    }
}
