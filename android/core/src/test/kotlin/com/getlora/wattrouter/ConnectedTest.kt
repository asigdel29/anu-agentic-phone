// ConnectedTest.kt: what a server being down does to the others.
//
// History
//   2026-08-11  A. Sigdel  Created with #596.
//
// On the JVM against a scripted Rpc, which is the split that seam exists for.
// HttpRpcTest asks what the transport does over a socket; this asks what
// happens when one of several servers will not answer, and a network is the
// slowest way to arrange that.
//
// The case to read first is the second. One server behind a laptop that is shut
// is the ordinary case, not the exception, and a phone that could not run a
// turn because of it is a phone somebody disconnects the server from and never
// reconnects.

package com.getlora.wattrouter

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedTest {

    /** Answers `tools/list` with the named tools, or throws. */
    private fun serving(vararg named: String) = Rpc { body ->
        if (body.contains("tools/list")) {
            val tools = named.joinToString(",") {
                """{"name":"$it","description":"does $it","inputSchema":{"type":"object"}}"""
            }
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[$tools]}}"""
        } else {
            """{"jsonrpc":"2.0","id":1,"result":{}}"""
        }
    }

    private fun down(why: String = "connection refused") = Rpc { throw IOException(why) }

    private fun at(label: String) = Connection(label, "https://$label.example.com/mcp")

    @Test
    fun aServerIsAskedAndItsToolsComeBackPrefixed() = runTest {
        val reached = connect(listOf(at("desk"))) { serving("lookup", "write") }

        assertEquals(1, reached.size)
        assertNull(reached.single().why)
        assertEquals(
            listOf("mcp_desk_lookup", "mcp_desk_write"),
            reached.single().tools.map { it.name },
        )
    }

    @Test
    fun oneServerBeingDownLeavesTheOthersAlone() = runTest {
        // The case this file is for. A laptop that is shut is ordinary.
        val reached = connect(
            listOf(at("laptop"), at("desk")),
        ) { if (it.label == "laptop") down() else serving("lookup") }

        assertEquals(2, reached.size)
        assertNotNull("the one that failed says so", reached.first().why)
        assertTrue(reached.first().tools.isEmpty())
        assertEquals(listOf("mcp_desk_lookup"), reached.last().tools.map { it.name })
    }

    @Test
    fun aServerWithNothingIsToldApartFromOneThatCouldNotBeAsked() {
        // Without `why` a screen can only say a server has no tools, which
        // reads as a server with nothing on it rather than one that is down.
        runTest {
            val empty = connect(listOf(at("desk"))) { serving() }.single()
            val broken = connect(listOf(at("desk"))) { down() }.single()

            assertTrue(empty.tools.isEmpty())
            assertNull("nothing to say about a server that answered", empty.why)

            assertTrue(broken.tools.isEmpty())
            assertNotNull("something to say about one that did not", broken.why)
        }
    }

    @Test
    fun theReasonCarriesWhatWentWrong() = runTest {
        val reached = connect(listOf(at("desk"))) { down("no route to host") }

        assertTrue(reached.single().why!!, reached.single().why!!.contains("no route to host"))
    }

    @Test
    fun theOrderTheyWereSavedInIsTheOrderTheirToolsArrive() = runTest {
        // ToolBox keeps the first of a duplicate name, so this order decides
        // which server wins when two offer a tool of the same name. It is the
        // order somebody put them in rather than whichever answered first,
        // which is why they are asked in sequence.
        val reached = connect(listOf(at("one"), at("two"), at("three"))) { serving("go") }

        assertEquals(
            listOf("mcp_one_go", "mcp_two_go", "mcp_three_go"),
            reached.tools().map { it.name },
        )
    }

    @Test
    fun nothingSavedIsNothingAskedAndNoTools() = runTest {
        var asked = 0
        val reached = connect(emptyList()) {
            asked++
            serving("lookup")
        }

        assertEquals(0, asked)
        assertTrue(reached.tools().isEmpty())
    }

    @Test
    fun whatComesBackIsATheModelCannotTellFromACompiledTool() = runTest {
        // Nothing downstream can tell one of these from a real tool, which is
        // McpTool's whole point: the moment something can, there are two code
        // paths where one would do.
        val tools = connect(listOf(at("desk"))) { serving("lookup") }.tools()
        val box = ToolBox(tools)

        assertNotNull(box["mcp_desk_lookup"])
        assertEquals("does lookup", box["mcp_desk_lookup"]!!.purpose)
        assertTrue(box.definitions().contains("mcp_desk_lookup"))
    }

    @Test
    fun aBuiltInKeepsItsNameAgainstAServerThatWantsIt() = runTest {
        // The security decision Mcp.kt states, held end to end rather than at
        // the prefix alone: a server offering `tap` is offered as `mcp_x_tap`,
        // so the compiled tool is never the one displaced.
        val compiled = object : Tool {
            override val name = "tap"
            override val purpose = "the real one"
            override val schema = """{"type":"object"}"""
            override suspend fun run(arguments: String) = "tapped"
        }

        val box = ToolBox(listOf(compiled) + connect(listOf(at("x"))) { serving("tap") }.tools())

        assertEquals("the real one", box["tap"]!!.purpose)
        assertNotNull(box["mcp_x_tap"])
    }
}
