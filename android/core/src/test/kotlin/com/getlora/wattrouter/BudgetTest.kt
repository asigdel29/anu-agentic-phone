// BudgetTest.kt — what a turn may actually do, and what it may do freely.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. The case worth having is the one Agent's round cap does not
// cover: many actions inside one round.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Counting : Phone {
    var acts = 0
    var reads = 0

    override suspend fun barredNow(): String? = null

    override suspend fun read(): Reading? = null.also { reads++ }

    override suspend fun apps(): List<Launchable> = emptyList<Launchable>().also { reads++ }

    override suspend fun tap(at: Handle, from: Generation) = Done.Did(null).also { acts++ }

    override suspend fun type(at: Handle, from: Generation, text: String) =
        Done.Did(null).also { acts++ }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward) =
        Done.Did(null).also { acts++ }

    override suspend fun navigate(way: Way) = Done.Did(null).also { acts++ }

    override suspend fun open(packageName: String) = Done.Did(null).also { acts++ }
}

class BudgetTest {
    private val handle = Handle("send", "button", "Send", null, 0)
    private val generation = Generation("k3f9", 4)

    @Test
    fun manyActionsInOneRoundAreStillBounded() = runTest {
        // The case the eight-round cap does not cover: one round carries as
        // many tool calls as the model wrote.
        val phone = Counting()
        val budgeted = Budgeted(phone, Budget(most = 3))

        repeat(10) { budgeted.tap(handle, generation) }

        assertEquals(3, phone.acts)
    }

    @Test
    fun theRefusalSaysWhatToDoRatherThanOnlyNo() = runTest {
        val budgeted = Budgeted(Counting(), Budget(most = 1))
        budgeted.tap(handle, generation)

        val done = budgeted.tap(handle, generation)

        assertTrue("$done", done is Done.Refused)
        assertTrue("$done", (done as Done.Refused).why.contains("let the person decide"))
    }

    @Test
    fun readingIsFree() = runTest {
        // A model that reads twice before acting is being careful, and
        // charging it for that teaches the opposite.
        val phone = Counting()
        val budgeted = Budgeted(phone, Budget(most = 1))

        repeat(20) { budgeted.read() }
        budgeted.apps()
        budgeted.barredNow()
        budgeted.tap(handle, generation)

        assertEquals(21, phone.reads)
        assertEquals(1, phone.acts)
    }

    @Test
    fun everyWayOfChangingTheScreenSpends() = runTest {
        // A tool added later is counted whether or not its author knew, which
        // is the reason this wraps rather than being checked in each tool.
        val phone = Counting()
        val budgeted = Budgeted(phone, Budget(most = 5))

        budgeted.tap(handle, generation)
        budgeted.type(handle, generation, "x")
        budgeted.scroll(handle, generation, Onward.FORWARD)
        budgeted.navigate(Way.BACK)
        budgeted.open("com.example.notes")

        assertEquals(5, phone.acts)
        assertTrue("$budgeted", budgeted.tap(handle, generation) is Done.Refused)
    }

    @Test
    fun aTurnStartsWithItsAllowanceBack() {
        val budget = Budget(most = 2)

        assertTrue(budget.spend())
        assertTrue(budget.spend())
        assertEquals(false, budget.spend())

        budget.beginTurn()

        assertEquals(2, budget.left)
        assertTrue(budget.spend())
    }

    @Test
    fun aResumedTurnIsATurn() = runTest {
        // What an interrupt produces, and the moment somebody has just said
        // carry on — inheriting a spent allowance would refuse them.
        val budget = Budget(most = 1)
        Budgeted(Counting(), budget).tap(handle, generation)
        assertEquals(0, budget.left)

        agentWith(budget).resume().collect { }

        assertEquals(1, budget.left)
    }

    @Test
    fun aTurnThatSaysSomethingResetsItToo() = runTest {
        val budget = Budget(most = 1)
        Budgeted(Counting(), budget).tap(handle, generation)

        agentWith(budget).send("carry on").collect { }

        assertEquals(1, budget.left)
    }

    @Test
    fun anAgentWithNoBudgetRunsUnchanged() = runTest {
        // Null is not a budget of zero: an agent with no phone tools cannot
        // act, and a number kept in step for nothing is a number that drifts.
        var answered = false

        agentWith(null).send("hello").collect { answered = true }

        assertTrue(answered)
    }

    /** One that answers once and calls nothing. */
    private fun agentWith(budget: Budget?) = Agent(
        router = { _, _ -> Decision("mid", "scored", 0.4f, listOf(Step("m", Backend.REMOTE))) },
        walk = ChainWalk(ScriptedInference(listOf(StreamEvent.Text("done")))),
        tools = ToolBox(emptyList()),
        budget = budget,
    )
}
