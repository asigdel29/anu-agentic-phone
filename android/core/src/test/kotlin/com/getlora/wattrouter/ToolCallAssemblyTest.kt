// ToolCallAssemblyTest.kt — fragments in, whole calls out.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On the JVM, and against fragments written by hand rather than parsed: what is
// under test is the reassembly, and ServerSentEventTest already covers turning
// a line into a fragment.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAssemblyTest {
    private fun piece(index: Int, id: String? = null, name: String? = null, args: String = "") =
        ToolCallFragment(index = index, id = id, name = name, arguments = args)

    @Test
    fun argumentsArriveACharacterAtATimeAndComeBackWhole() {
        // The whole job. A provider sends the JSON in whatever sized pieces it
        // likes, and none of them is valid on its own.
        val assembly = ToolCallAssembly()
        assembly.add(piece(0, id = "call_1", name = "recall", args = ""))
        listOf("{\"q", "uery\":", "\"bins\"}").forEach { assembly.add(piece(0, args = it)) }

        assertEquals(
            listOf(ToolCall("call_1", "recall", """{"query":"bins"}""")),
            assembly.take(),
        )
    }

    @Test
    fun aLaterFragmentDoesNotBlankTheName() {
        // The bug this guards. Continuations carry neither id nor name, and
        // writing them through would leave a call that cannot be dispatched.
        val assembly = ToolCallAssembly()
        assembly.add(piece(0, id = "call_1", name = "remember"))
        assembly.add(piece(0, args = "{}"))

        assertEquals("remember", assembly.take().single().name)
    }

    @Test
    fun callsComeBackInTheProvidersOrderNotTheOrderTheyArrived() {
        // The Agent runs tools in the order it is handed them, so that order has
        // to be the provider's numbering rather than whichever fragment landed
        // first — parallel calls interleave.
        val assembly = ToolCallAssembly()
        assembly.add(piece(1, id = "b", name = "second"))
        assembly.add(piece(0, id = "a", name = "first"))
        assembly.add(piece(1, args = "{}"))
        assembly.add(piece(0, args = "{}"))

        assertEquals(listOf("first", "second"), assembly.take().map { it.name })
    }

    @Test
    fun anIndexThatDoesNotStartAtZeroIsStillOneCall() {
        val assembly = ToolCallAssembly()
        assembly.add(piece(7, id = "call_7", name = "recall", args = "{}"))

        assertEquals(listOf("call_7"), assembly.take().map { it.id })
    }

    @Test
    fun aCallWithNoNameIsDropped() {
        // A truncated stream leaves a fragment that was never named. Passing it
        // on turns that into a tool-not-found the model then apologises for.
        val assembly = ToolCallAssembly()
        assembly.add(piece(0, args = "{\"partial\""))

        assertEquals(emptyList<ToolCall>(), assembly.take())
    }

    @Test
    fun takingTwiceGivesNothingTheSecondTime() {
        // A caller flushes on the finish reason and again if the body ends
        // without one. Both firing must not run every tool twice.
        val assembly = ToolCallAssembly()
        assembly.add(piece(0, id = "call_1", name = "recall", args = "{}"))

        assertEquals(1, assembly.take().size)
        assertEquals(emptyList<ToolCall>(), assembly.take())
    }

    @Test
    fun emptinessIsHowACallerAvoidsAskingTwice() {
        val assembly = ToolCallAssembly()
        assertTrue(assembly.isEmpty)

        assembly.add(piece(0, id = "call_1", name = "recall"))
        assertFalse(assembly.isEmpty)

        assembly.take()
        assertTrue(assembly.isEmpty)
    }
}
