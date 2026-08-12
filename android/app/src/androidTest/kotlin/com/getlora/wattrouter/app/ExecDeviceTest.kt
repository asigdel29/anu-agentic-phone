// ExecDeviceTest.kt: whether this app may run a program at all.
//
// History
//   2026-08-12  A. Sigdel  Created.
//
// A measurement rather than a guard, in the shape #472 and #644 established:
// build the thing that could fail, read what it says, and correct the record to
// whatever it said.
//
// #602 argues that W^X since API 29 decides how a terminal is built, and picks
// shipping executables under jniLibs because that directory is executable where
// the app's data directory is not. The premise is narrower than the conclusion
// drawn from it. what-android-allows.md says an app cannot exec a file *it
// wrote into its own data directory*, and /system/bin/sh is neither written by
// this app nor in its data directory.
//
// So this asks the only question that settles it, and asks it where it has to
// be asked: in the app's own process, on a device, at this targetSdk. Running
// the same command through `adb shell` would answer about the shell user in a
// different SELinux domain, which is a question nobody asked.
//
// It reports rather than asserts. A test that merely fails says the exec was
// refused and nothing about how; the exit status, the output and the exception
// together say which of several things happened, and that is what the record
// gets corrected to.

package com.getlora.wattrouter.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecDeviceTest {

    /** What one attempt at running something came back with. */
    private data class Ran(val status: Int?, val output: String, val threw: String?)

    /**
     * Run a command from this process and report everything about it.
     *
     * The argv array is the point rather than a detail. A command is passed as
     * one element and never concatenated into a line, which is the difference
     * between running what was asked for and running whatever was pasted into
     * it. Every shell injection in a tool of this kind is a string that was
     * built rather than an array that was passed.
     */
    private fun run(vararg argv: String, within: File? = null): Ran = try {
        val process = ProcessBuilder(*argv)
            .directory(within)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        Ran(if (finished) process.exitValue() else null, output.trim(), null)
    } catch (e: Exception) {
        // Exception rather than IOException: the interesting failures here are
        // the ones nobody predicted, and a narrower catch would let the one
        // that matters escape as a stack trace instead of a finding.
        Ran(null, "", "${e.javaClass.simpleName}: ${e.message}")
    }

    @Test
    fun theSystemShellCanBeReached() {
        val ran = run("/system/bin/sh", "-c", "echo reached")

        assertEquals("threw ${ran.threw}, said ${ran.output}", null, ran.threw)
        assertEquals("exit status, having said ${ran.output}", 0, ran.status)
        assertEquals("reached", ran.output)
    }

    @Test
    fun aCommandRunsInTheDirectoryItIsGiven() {
        // The workspace question. A terminal has to run where the git tools
        // work, and #602 requires it to use that directory rather than invent a
        // second one, so being able to name it is part of the measurement.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.filesDir, "work").apply { mkdirs() }
        File(work, "here.txt").writeText("yes")

        val ran = run("/system/bin/sh", "-c", "ls", within = work)

        assertEquals("threw ${ran.threw}", null, ran.threw)
        assertTrue("said ${ran.output}", ran.output.lineSequence().any { it == "here.txt" })
    }

    @Test
    fun anArgumentIsNotAnotherCommand() {
        // The property the whole argv shape exists for, and the failure this
        // repository has an external counterexample of: a tool that built
        // `am start -d $url` from a model's string ran both halves of
        // `https://x.com; pm uninstall com.foo`. Passed as one element, the
        // semicolon is text.
        val ran = run("/system/bin/echo", "one; echo two")

        assertEquals("threw ${ran.threw}", null, ran.threw)
        assertEquals("one; echo two", ran.output)
    }

    @Test
    fun whatTheSystemShellCanReachIsWorthKnowing() {
        // Not a pass or fail. The tools a terminal would have on day one are
        // whatever this prints, and the answer decides whether shipping a
        // cross-built toybox under jniLibs buys anything or duplicates what is
        // already there. Recorded in what-android-allows.md.
        val ran = run("/system/bin/sh", "-c", "toybox 2>/dev/null | head -c 2000")

        println("ExecDeviceTest: toybox applets: ${ran.output}")
        println("ExecDeviceTest: status ${ran.status}, threw ${ran.threw}")
    }

    @Test
    fun aFileTheAppWroteItselfCannotBeRun() {
        // The other half of the measurement, and the one that confirms W^X is
        // real rather than folklore. This must fail, and the whole point of the
        // change is that the two results differ: the system shell runs and a
        // file this app wrote does not.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mine = File(context.filesDir, "mine.sh")
        mine.writeText("#!/system/bin/sh\necho mine\n")
        mine.setExecutable(true)

        val ran = run(mine.absolutePath)

        println("ExecDeviceTest: own file: status ${ran.status}, threw ${ran.threw}")
        assertTrue(
            "a file the app wrote into its own data directory ran, which W^X should forbid",
            ran.threw != null || ran.status != 0,
        )
    }
}
