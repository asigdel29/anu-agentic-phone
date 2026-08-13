// TerminalDeviceTest.kt: the seam runs a command, where it was told to.
//
// History
//   2026-08-12  A. Sigdel  Created with #669.
//
// Instrumented because /system/bin/sh is not on the host, so no JVM test reaches
// SystemShell at all. ExecDeviceTest measured that ProcessBuilder can do this;
// under test here is that the seam over it answers with the status and output.

package com.getlora.wattrouter.app

import androidx.test.platform.app.InstrumentationRegistry
import com.getlora.wattrouter.Ran
import com.getlora.wattrouter.SystemShell
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalDeviceTest {

    @Test
    fun aCommandRunsInTheWorkspaceAndSaysHowItWent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.filesDir, "work").apply { mkdirs() }
        File(work, "here.txt").writeText("yes")

        val ran = SystemShell(work.absolutePath).run("ls")

        assertTrue("$ran", ran is Ran.Finished)
        val finished = ran as Ran.Finished
        assertEquals(finished.output, 0, finished.status)
        assertTrue(finished.output, finished.output.lineSequence().any { it == "here.txt" })
    }

    @Test
    fun aCommandThatFailsIsAnAnswerRatherThanAThrow() = runBlocking {
        // Tool.kt's contract one layer down: a model acts on "no such file",
        // and a thrown exception is a dead turn instead.
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val ran = SystemShell(context.filesDir.absolutePath).run("cat nothing-is-here")

        assertTrue("$ran", ran is Ran.Finished)
        assertTrue("$ran", (ran as Ran.Finished).status != 0)
        // stderr, which is where the reason is and where it would be lost.
        assertTrue(ran.output, ran.output.isNotEmpty())
    }
}
