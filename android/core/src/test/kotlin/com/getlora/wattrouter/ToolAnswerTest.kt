// ToolAnswerTest.kt: the channel a tool answers a picture through.
//
// History
//   2026-08-11  A. Sigdel  Created with #439.
//
// #439's second layer: Tool.run answers a String, and that is the right
// contract for everything written so far. A screenshot is not prose, and
// encoding one as text would be handing a model base64 to read.
//
// The case to read first is the default. Twenty-two tools answer prose and none
// of them was edited, because `answer` wraps `run` unless a tool says
// otherwise. A change that made all twenty-two say `Answer("…")` would be a
// change to every tool in the application for the sake of one.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAnswerTest {
    private val shot = Image("data:image/png;base64,aGVsbG8=")

    /** The ordinary kind: prose, and nothing to look at. */
    private class Says(private val said: String) : Tool {
        override val name = "says"
        override val purpose = "says something"
        override val schema = """{"type":"object"}"""
        var ran = 0
        override suspend fun run(arguments: String): String {
            ran++
            return said
        }
    }

    /** The other kind, which is what this layer exists for. */
    private inner class Shows : Tool {
        override val name = "shows"
        override val purpose = "shows something"
        override val schema = """{"type":"object"}"""
        var answered = 0
        override suspend fun answer(arguments: String): Answer {
            answered++
            return Answer("the screen, below", listOf(shot))
        }

        // Narrowed rather than left unimplemented, so a caller that only wants
        // text does not have to know which kind of tool it is holding.
        override suspend fun run(arguments: String) = answer(arguments).text
    }

    private fun call(named: String) = ToolCall("c1", named, "{}")

    @Test
    fun aToolThatOnlyAnswersProseNeedsNoChanges() {
        // The default, which is the whole reason twenty-two files are untouched.
        runTest {
            val says = Says("done")

            assertEquals(Answer("done"), says.answer("{}"))
            assertTrue(says.answer("{}").images.isEmpty())
        }
    }

    @Test
    fun theDefaultRunsTheToolOnce() {
        // A default that called run twice would double every side effect in the
        // application, which is the kind of thing that shows up as a tap
        // happening twice.
        runTest {
            val says = Says("done")

            says.answer("{}")

            assertEquals(1, says.ran)
        }
    }

    @Test
    fun aToolThatShowsSomethingAnswersWithIt() = runTest {
        val answered = Shows().answer("{}")

        assertEquals("the screen, below", answered.text)
        assertEquals(listOf(shot), answered.images)
    }

    @Test
    fun itsRunIsStillTheProseHalf() = runTest {
        // Both members stay true of it. A caller wanting text does not have to
        // know which kind of tool it holds.
        assertEquals("the screen, below", Shows().run("{}"))
    }

    @Test
    fun aToolBoxCarriesTheImagesThrough() = runTest {
        val result = ToolBox(listOf(Shows())).run(call("shows"))

        assertEquals("the screen, below", result.content)
        assertEquals(listOf(shot), result.images)
    }

    @Test
    fun andCarriesNoneForEveryOtherTool() = runTest {
        val result = ToolBox(listOf(Says("done"))).run(call("says"))

        assertEquals("done", result.content)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun theToolBoxCallsAnswerRatherThanBoth() = runTest {
        // Calling both would do the work twice, which for a tool that captures
        // a screen is two screenshots and, for one that taps, two taps.
        val shows = Shows()

        ToolBox(listOf(shows)).run(call("shows"))

        assertEquals(1, shows.answered)
    }

    @Test
    fun aFailureStillCarriesNothingToLookAt() = runTest {
        // The error paths build a ToolResult by hand, and one that claimed an
        // image would be a message referring to a picture that is not there.
        val exploding = object : Tool {
            override val name = "boom"
            override val purpose = "fails"
            override val schema = """{"type":"object"}"""
            override suspend fun run(arguments: String): String = throw IllegalStateException("no")
        }

        val box = ToolBox(listOf(exploding))

        assertTrue(box.run(call("boom")).images.isEmpty())
        assertTrue(box.run(call("nothing at all")).images.isEmpty())
    }
}
