// ToolBoxTest.kt: dispatch, and what a failure looks like to the model.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The cases that matter are the failures: a tool that works is dispatch, and a
// tool that does not is where a model either recovers or repeats itself.

package com.getlora.wattrouter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Fake(
    override val name: String,
    private val answer: String = "done",
    private val thrown: Exception? = null,
    override val schema: String = """{"type":"object","properties":{}}""",
) : Tool {
    override val purpose = "a $name"
    var sawArguments: String? = null

    override suspend fun run(arguments: String): String {
        sawArguments = arguments
        thrown?.let { throw it }
        return answer
    }
}

class ToolBoxTest {

    @Test
    fun aCallReachesTheToolItNames() = runTest {
        val recall = Fake("recall", answer = "the bins go out Tuesday")
        val box = ToolBox(listOf(Fake("remember"), recall))

        val result = box.run(ToolCall("c1", "recall", """{"query":"bins"}"""))

        assertEquals("the bins go out Tuesday", result.content)
        assertEquals("c1", result.id)
        assertFalse(result.isError)
        assertEquals("""{"query":"bins"}""", recall.sawArguments)
    }

    @Test
    fun anUnknownToolIsToldWhatThereIsInstead() = runTest {
        // The alternatives, not just the mistake: a model that misremembers a
        // name usually recognises the right one.
        val box = ToolBox(listOf(Fake("recall"), Fake("remember")))

        val result = box.run(ToolCall("c1", "recal", "{}"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("no tool called recal"))
        assertTrue(result.content, result.content.contains("recall"))
        assertTrue(result.content, result.content.contains("remember"))
    }

    @Test
    fun aToolThatThrowsBecomesSomethingTheModelCanRead() = runTest {
        // Not a dead turn: the model reads what went wrong and tries again.
        val box = ToolBox(listOf(Fake("recall", thrown = IllegalStateException("no store open"))))

        val result = box.run(ToolCall("c1", "recall", "{}"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("no store open"))
        assertTrue("the schema should be reprinted", result.content.contains("properties"))
    }

    @Test
    fun aThrowWithNoMessageStillSaysSomething() = runTest {
        // "recall failed: null" reads like the tool answered null.
        val box = ToolBox(listOf(Fake("recall", thrown = NullPointerException())))

        val result = box.run(ToolCall("c1", "recall", "{}"))

        assertTrue(result.content, result.content.contains("NullPointerException"))
    }

    @Test
    fun cancellationIsTheOneThingThatPropagates() = runTest {
        // Reported as a result, "cancelled" is answered by trying again.
        val box = ToolBox(listOf(Fake("recall", thrown = CancellationException("interrupted"))))

        try {
            box.run(ToolCall("c1", "recall", "{}"))
            throw AssertionError("swallowed the cancellation")
        } catch (e: CancellationException) {
            assertEquals("interrupted", e.message)
        }
    }

    @Test
    fun twoToolsWithOneNameKeepTheFirst() = runTest {
        // Picking the later one would make behaviour depend on assembly order.
        val box = ToolBox(listOf(Fake("recall", answer = "first"), Fake("recall", answer = "second")))

        assertEquals(1, box.tools.size)
        assertEquals("first", box.run(ToolCall("c1", "recall", "{}")).content)
    }

    @Test
    fun theModelIsShownEveryToolInTheOrderGiven() {
        // #319: on iOS this is reached only from tests, so every registered
        // tool is invisible. Order is preserved because reordering between
        // builds costs every prompt cache hit.
        val definitions = ToolBox(listOf(Fake("recall"), Fake("remember"))).definitions()

        assertTrue(definitions, definitions.indexOf("recall") < definitions.indexOf("remember"))
        assertTrue(definitions, definitions.contains(""""type":"function""""))
        assertTrue(definitions, definitions.contains(""""description":"a recall""""))
        assertTrue(definitions, definitions.contains(""""parameters":{"type":"object""""))
    }

    @Test
    fun aSchemaThatIsNotAnObjectFailsLoudlyAtAssembly() {
        // Rather than dropping the tool: one quietly missing from the list is
        // one the model never calls and nobody notices.
        val box = ToolBox(listOf(Fake("recall", schema = "\"not an object\"")))

        try {
            box.definitions()
            throw AssertionError("built it anyway")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("recall's schema"))
        }
    }
}
