// ConnectionsScreenTest.kt: what a row says a server is doing.
//
// History
//   2026-08-11  A. Sigdel  Created with #596.
//
// On the JVM against `standing` alone: the screen is Compose and belongs on a
// device, and the sentence under a server's name is a decision.
//
// The case that matters is the last two together. A server offering nothing and
// one that could not be asked look identical without `Reached.why`, and only
// one of them is something somebody can go and fix.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.Connection
import com.getlora.wattrouter.Reached
import com.getlora.wattrouter.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsScreenTest {
    private val desk = Connection("desk", "https://desk.example.com/mcp")

    private fun tool(named: String) = object : Tool {
        override val name = named
        override val purpose = "does $named"
        override val schema = """{"type":"object"}"""
        override suspend fun run(arguments: String) = "ran"
    }

    @Test
    fun oneToolIsNotPluralised() {
        // A row reading "1 tools" is the kind of thing somebody notices instead
        // of reading the list.
        assertEquals("1 tool", standing(Reached(desk, listOf(tool("a")))))
    }

    @Test
    fun severalAreCounted() {
        assertEquals("3 tools", standing(Reached(desk, listOf(tool("a"), tool("b"), tool("c")))))
    }

    @Test
    fun aServerThatAnsweredWithNothingSaysItConnected() {
        val said = standing(Reached(desk, emptyList()))

        assertTrue(said, said.contains("connected"))
    }

    @Test
    fun aServerThatCouldNotBeAskedSaysWhy() {
        val said = standing(Reached(desk, emptyList(), why = "connection refused"))

        assertTrue(said, said.contains("could not be reached"))
        assertTrue(said, said.contains("connection refused"))
    }

    @Test
    fun thoseTwoAreNotTheSameSentence() {
        // The distinction the whole of `Reached.why` exists for.
        assertNotEquals(
            standing(Reached(desk, emptyList())),
            standing(Reached(desk, emptyList(), why = "connection refused")),
        )
    }
}
