// AgentTest.kt — the loop, and the four ways it goes wrong quietly.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On the JVM against a Routing seam and a scripted Inference: the loop is the
// most decision-dense code here, and the emulator is the slowest place to
// learn one of those decisions is wrong.

package com.getlora.wattrouter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {
    private val chain = listOf(Step("mid", Backend.REMOTE))
    private fun routing() = Routing { _, _ -> Decision("mid", "scored", 0.4f, chain) }

    /** Answers with the scripted rounds, one per call. */
    private class Rounds(private val rounds: List<List<StreamEvent>>) : Inference {
        var asked = 0
        var lastTools: String? = null

        override fun complete(
            conversation: Conversation,
            model: String,
            tools: String?,
            maxTokens: Int?,
        ): Flow<StreamEvent> = flow {
            lastTools = tools
            rounds.getOrElse(asked++) { emptyList() }.forEach { emit(it) }
        }
    }

    private class Echo : Tool {
        override val name = "echo"
        override val purpose = "echo"
        override val schema = """{"type":"object"}"""
        val order = mutableListOf<String>()
        override suspend fun run(arguments: String): String {
            order += arguments
            return "ran $arguments"
        }
    }

    private fun call(id: String, args: String = "{}") = StreamEvent.Call(ToolCall(id, "echo", args))

    @Test
    fun aTurnWithNoToolsIsOneRound() = runTest {
        val asking = Rounds(listOf(listOf(StreamEvent.Text("Tuesday"))))
        val agent = Agent(routing(), ChainWalk(asking), ToolBox(listOf(Echo())))

        val got = agent.send("when do the bins go out").toList()

        assertEquals(1, asking.asked)
        assertTrue(got.any { it is TurnEvent.Text })
        assertEquals(2, agent.conversation.messages.size)
    }

    @Test
    fun aToolResultGoesBackAndTheModelIsAskedAgain() = runTest {
        val asking = Rounds(listOf(listOf(call("c1")), listOf(StreamEvent.Text("done"))))
        val echo = Echo()
        val agent = Agent(routing(), ChainWalk(asking), ToolBox(listOf(echo)))

        agent.send("do it").toList()

        assertEquals(2, asking.asked)
        assertEquals(1, echo.order.size)
        // user, assistant(call), tool(result), assistant(text)
        assertEquals(4, agent.conversation.messages.size)
        assertEquals(Role.TOOL, agent.conversation.messages[2].role)
    }

    @Test
    fun aRoundIsCommittedWholeOrNotAtAll() = runTest {
        // The failure this prevents appears one turn *after* its cause: a
        // call with no answering tool message is a body the provider refuses
        // on the next request.
        val asking = Rounds(listOf(listOf(call("c1"))))
        val exploding = object : Tool {
            override val name = "echo"
            override val purpose = "echo"
            override val schema = """{"type":"object"}"""
            override suspend fun run(arguments: String): String = throw AssertionError("boom")
        }
        val agent = Agent(routing(), ChainWalk(asking), ToolBox(listOf(exploding)))

        runCatching { agent.send("do it").toList() }

        val calls = agent.conversation.messages.count { it.toolCalls.isNotEmpty() }
        val answers = agent.conversation.messages.count { it.role == Role.TOOL }
        assertEquals("every call must have its answer", calls, answers)
    }

    @Test
    fun toolsRunInTheOrderTheModelAskedForThem() = runTest {
        // A write then a read of one path: a sequence, or a race.
        val asking = Rounds(
            listOf(
                listOf(call("c1", """{"n":1}"""), call("c2", """{"n":2}"""), call("c3", """{"n":3}""")),
                listOf(StreamEvent.Text("done")),
            ),
        )
        val echo = Echo()
        Agent(routing(), ChainWalk(asking), ToolBox(listOf(echo))).send("go").toList()

        assertEquals(listOf("""{"n":1}""", """{"n":2}""", """{"n":3}"""), echo.order)
    }

    @Test
    fun aModelThatNeverAnswersIsGivenUpOn() = runTest {
        // Not a quiet return at the cap, which reads as an answer.
        val asking = Rounds(List(20) { listOf(call("c$it")) })
        val agent = Agent(routing(), ChainWalk(asking), ToolBox(listOf(Echo())), maxRounds = 3)

        val thrown = runCatching { agent.send("loop").toList() }.exceptionOrNull()

        assertTrue("$thrown", thrown is AgentError.TooManyRounds)
        assertEquals(3, asking.asked)
    }

    @Test
    fun theTierIsDecidedEveryRoundRatherThanOnce() = runTest {
        // A lookup that becomes a refactor should not stay on cheap.
        var decisions = 0
        val router = Routing { _, _ ->
            decisions++
            Decision("mid", "scored", null, chain)
        }
        val asking = Rounds(listOf(listOf(call("c1")), listOf(StreamEvent.Text("done"))))

        Agent(router, ChainWalk(asking), ToolBox(listOf(Echo()))).send("go").toList()

        assertEquals(2, decisions)
    }

    @Test
    fun aRouterThatCannotDecideStopsTheTurn() = runTest {
        val agent = Agent(
            Routing { _, _ -> null },
            ChainWalk(Rounds(emptyList())),
            ToolBox(emptyList()),
        )

        val thrown = runCatching { agent.send("go").toList() }.exceptionOrNull()

        assertTrue("$thrown", thrown is AgentError.CannotDecide)
    }

    @Test
    fun theModelIsToldWhatToolsItHas() = runTest {
        // #319 in one assertion: the definitions must reach the request.
        val asking = Rounds(listOf(listOf(StreamEvent.Text("hello"))))
        Agent(routing(), ChainWalk(asking), ToolBox(listOf(Echo()))).send("hi").toList()

        assertTrue(asking.lastTools.orEmpty(), asking.lastTools.orEmpty().contains(""""name":"echo""""))
    }
}
