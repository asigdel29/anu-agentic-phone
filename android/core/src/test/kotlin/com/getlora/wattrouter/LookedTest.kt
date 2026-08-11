// LookedTest.kt: where a captured picture lands in a conversation.
//
// History
//   2026-08-11  A. Sigdel  Created with #439.
//
// #439's third layer, and the one whose answer is a constraint rather than a
// choice. A tool message takes a string; the content array that carries an
// image is accepted on a user message. So a screenshot cannot ride back on the
// tool result that produced it, and putting it there would be a request the
// provider refuses on every turn that captured anything.
//
// The case to read first is the ordering. A tool message names a call id, and a
// provider that has not seen the message announcing that id rejects the whole
// request. The picture goes after its own tool message rather than instead of
// it, so the pairing the loop keeps is untouched.

package com.getlora.wattrouter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookedTest {
    private val shot = Image("data:image/png;base64,aGVsbG8=")
    private val chain = listOf(Step("mid", Backend.REMOTE))

    private class Rounds(private val rounds: List<List<StreamEvent>>) : Inference {
        override fun complete(
            conversation: Conversation,
            model: String,
            tools: String?,
            maxTokens: Int?,
        ): Flow<StreamEvent> = flow {
            rounds.getOrElse(asked++) { emptyList() }.forEach { emit(it) }
        }

        var asked = 0
    }

    private inner class Capturing : Tool {
        override val name = "look"
        override val purpose = "look at the screen"
        override val schema = """{"type":"object"}"""
        override suspend fun answer(arguments: String) =
            Answer("the screen, below", listOf(shot))

        override suspend fun run(arguments: String) = answer(arguments).text
    }

    private class Says : Tool {
        override val name = "says"
        override val purpose = "says"
        override val schema = """{"type":"object"}"""
        override suspend fun run(arguments: String) = "done"
    }

    private fun agent(tool: Tool, rounds: Rounds) = Agent(
        Routing { _, _ -> Decision("mid", "scored", 0.4f, chain) },
        ChainWalk(rounds),
        ToolBox(listOf(tool)),
    )

    private fun call(named: String) =
        StreamEvent.Call(ToolCall("c1", named, "{}"))

    @Test
    fun aPictureIsNotOnTheToolMessage() = runTest {
        // The constraint the whole layer is shaped by. A tool message takes a
        // string, so an image on one is a request the provider refuses.
        val agent = agent(Capturing(), Rounds(listOf(listOf(call("look")))))

        agent.send("look").toList()

        val toolMessage = agent.conversation.messages.single { it.role == Role.TOOL }
        assertTrue(toolMessage.images.isEmpty())
        assertEquals(JsonPrimitive("the screen, below"), toolMessage.asJson()["content"])
    }

    @Test
    fun itComesAfterTheToolMessageThatProducedIt() = runTest {
        // A tool message names a call id, and a provider that has not seen the
        // message announcing that id rejects the whole request. The picture
        // goes after its own answer rather than instead of it.
        val agent = agent(Capturing(), Rounds(listOf(listOf(call("look")))))

        agent.send("look").toList()

        // The first four. A fifth follows, because the scripted second round
        // answers nothing and the loop commits that as an empty assistant
        // message, which is ordinary and not what this case is about.
        val roles = agent.conversation.messages.map { it.role }
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), roles.take(4))
        assertEquals(listOf(shot), agent.conversation.messages[3].images)
    }

    @Test
    fun itSaysWhichToolProducedIt() = runTest {
        // A user message the person did not send is a lie about who said what.
        // One naming the tool is a caption.
        val agent = agent(Capturing(), Rounds(listOf(listOf(call("look")))))

        agent.send("look").toList()

        val carrying = agent.conversation.messages.single { it.images.isNotEmpty() }
        assertTrue(carrying.content, carrying.content.contains("look"))
    }

    @Test
    fun aToolThatCapturedNothingAddsNoMessage() = runTest {
        // Which is every tool but one, and a turn that gained an empty user
        // message would be a turn saying something nobody said.
        val agent = agent(Says(), Rounds(listOf(listOf(call("says")))))

        agent.send("do it").toList()

        // One user message, which is the one the person sent.
        assertEquals(1, agent.conversation.messages.count { it.role == Role.USER })
        assertTrue(agent.conversation.messages.none { it.images.isNotEmpty() })
    }

    @Test
    fun theRoundIsStillCommittedWholeOrNotAtAll() = runTest {
        // The picture is appended inside the round rather than after it, so it
        // arrives with everything else or with nothing.
        val agent = agent(Capturing(), Rounds(listOf(listOf(call("look")))))

        agent.send("look").toList()

        val calls = agent.conversation.messages.sumOf { it.toolCalls.size }
        val answers = agent.conversation.messages.count { it.role == Role.TOOL }
        assertEquals(calls, answers)
    }
}
