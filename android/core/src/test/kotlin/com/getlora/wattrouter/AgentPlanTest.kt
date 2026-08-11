// AgentPlanTest.kt: what the loop does with a plan, and with a refusal.
//
// History
//   2026-08-10  A. Sigdel  Created with #595.
//
// PlanTest covers the rule for when to ask. This covers what the turn loop
// does either way, which is a different question and the one with a way to go
// wrong quietly.
//
// The case to read first is the last. A declined plan refuses *before* the
// calls run, so every call it refused still needs an answering message. A turn
// that skipped them would produce a body the provider rejects on the next
// request, one turn after the cause, which is the failure Agent.kt opens by
// describing and the reason a round is committed whole.

package com.getlora.wattrouter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanTest {
    private val chain = listOf(Step("mid", Backend.REMOTE))
    private fun routing() = Routing { _, _ -> Decision("mid", "scored", 0.4f, chain) }

    /** Answers with the scripted rounds, one per call. */
    private class Rounds(private val rounds: List<List<StreamEvent>>) : Inference {
        var asked = 0

        override fun complete(
            conversation: Conversation,
            model: String,
            tools: String?,
            maxTokens: Int?,
        ): Flow<StreamEvent> = flow {
            rounds.getOrElse(asked++) { emptyList() }.forEach { emit(it) }
        }
    }

    private class Counting : Tool {
        override val name = "echo"
        override val purpose = "echo"
        override val schema = """{"type":"object"}"""
        var ran = 0
        override suspend fun run(arguments: String): String {
            ran++
            return "ran"
        }
    }

    private class Answers(private val answer: Boolean) : Approval {
        val seen = mutableListOf<Plan>()
        override suspend fun mayI(plan: Plan): Boolean {
            seen += plan
            return answer
        }
    }

    private fun call(id: String) = StreamEvent.Call(ToolCall(id, "echo", "{}"))

    private fun planning(rounds: Rounds, tool: Counting, approval: Approval) = Agent(
        routing(),
        ChainWalk(rounds),
        ToolBox(listOf(tool)),
        planned = Planned({ Autonomy.PLAN }, approval),
    )

    @Test
    fun thePlanIsTheRoundTheTurnIsAboutToRun() = runTest {
        val approval = Answers(answer = true)
        val rounds = Rounds(
            listOf(
                listOf(StreamEvent.Text("I will look at the clock"), call("c1"), call("c2")),
                listOf(StreamEvent.Text("done")),
            ),
        )

        planning(rounds, Counting(), approval).send("do it").toList()

        assertEquals(1, approval.seen.size)
        assertEquals("I will look at the clock", approval.seen.single().says)
        assertEquals(listOf("echo", "echo"), approval.seen.single().steps)
    }

    @Test
    fun approvingOnceCoversTheRestOfTheTurn() = runTest {
        // The point of the mode, and its honest limit in one assertion: the
        // second round runs without anybody being asked about it.
        val approval = Answers(answer = true)
        val tool = Counting()
        val rounds = Rounds(
            listOf(listOf(call("c1")), listOf(call("c2")), listOf(StreamEvent.Text("done"))),
        )

        planning(rounds, tool, approval).send("do it").toList()

        assertEquals("asked once, not once per round", 1, approval.seen.size)
        assertEquals(2, tool.ran)
    }

    @Test
    fun decliningRunsNothingAndEndsTheTurn() = runTest {
        val approval = Answers(answer = false)
        val tool = Counting()
        val rounds = Rounds(listOf(listOf(call("c1")), listOf(StreamEvent.Text("done"))))

        planning(rounds, tool, approval).send("do it").toList()

        assertEquals(0, tool.ran)
        assertEquals("the model is not asked again", 1, rounds.asked)
    }

    @Test
    fun aDeclinedRoundStillAnswersEveryCallItRefused() = runTest {
        val approval = Answers(answer = false)
        val rounds = Rounds(listOf(listOf(call("c1"), call("c2"))))
        val agent = planning(rounds, Counting(), approval)

        val events = agent.send("do it").toList()

        val calls = agent.conversation.messages.sumOf { it.toolCalls.size }
        val answers = agent.conversation.messages.count { it.role == Role.TOOL }
        assertEquals("every call must have its answer", calls, answers)

        val told = agent.conversation.messages.last().content
        assertTrue(told, told.contains("did not approve this plan"))

        // Reported to the transcript as well, so somebody who declines watches
        // it stop rather than watching a turn end with nothing said.
        val results = events.filterIsInstance<TurnEvent.Result>()
        assertEquals(2, results.size)
        assertTrue(results.all { it.result.isError })
    }

    @Test
    fun aResumedTurnIsAskedAgain() = runTest {
        // What it does next is not what was approved before the interrupt, and
        // this is the judgement beginTurn already makes about the budget one
        // line above.
        //
        // Four rounds, because both turns need a first one that wants a tool.
        val approval = Answers(answer = true)
        val rounds = Rounds(
            listOf(
                listOf(call("c1")),
                listOf(StreamEvent.Text("done")),
                listOf(call("c2")),
                listOf(StreamEvent.Text("done")),
            ),
        )
        val agent = planning(rounds, Counting(), approval)

        agent.send("do it").toList()
        agent.resume().toList()

        assertEquals(2, approval.seen.size)
    }

    @Test
    fun aTurnWithNobodyToAskRunsRatherThanHanging() = runTest {
        // Every caller but the application, which is the only one with a
        // surface to put a question on. Blocking on an answer that cannot
        // arrive is worse than behaving the way Auto does.
        val tool = Counting()
        val rounds = Rounds(listOf(listOf(call("c1")), listOf(StreamEvent.Text("done"))))

        Agent(routing(), ChainWalk(rounds), ToolBox(listOf(tool))).send("do it").toList()

        assertEquals(1, tool.ran)
    }

    @Test
    fun theQuestionComesBeforeAnythingTouchesThePhone() = runTest {
        // Asked after a tool had run, this would be asking about something that
        // already happened, which is Budgeted's reason for the same ordering.
        val tool = Counting()
        val rounds = Rounds(listOf(listOf(call("c1"))))
        val ranByThen = mutableListOf<Int>()
        val watching = Approval { _ ->
            ranByThen += tool.ran
            false
        }

        planning(rounds, tool, watching).send("do it").toList()

        assertEquals(listOf(0), ranByThen)
    }
}
