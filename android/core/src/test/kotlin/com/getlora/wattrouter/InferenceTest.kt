// InferenceTest.kt: the seam a model's answer arrives through.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On the JVM. There is no model here and no network: what these check is the
// shape a chain walk will read: which failures are worth another model, and
// that a cold flow asks nothing until it is collected.

package com.getlora.wattrouter

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceTest {

    @Test
    fun aServerFailureIsWorthTheNextModel() {
        assertTrue(InferenceError.Unavailable("kimi-k3", "connection reset").isWorthAnotherModel)
    }

    @Test
    fun aRejectionIsNot() {
        // The distinction the whole type exists for. Every model behind the same
        // API would reject the same body identically, so retrying it three times
        // turns one bad request into three and reports the last one.
        assertFalse(InferenceError.Rejected("kimi-k3", 400, "bad tool schema").isWorthAnotherModel)
    }

    @Test
    fun andNeitherIsHavingRunOut() {
        // Exhausted is produced by the walk, so treating it as retryable would
        // be a walk retrying its own summary of having failed.
        assertFalse(InferenceError.Exhausted(3, "connection reset").isWorthAnotherModel)
    }

    @Test
    fun whatWentWrongIsInTheMessage() {
        // These reach a person through the transcript's failure row, so the
        // model and the reason both have to survive into the string.
        val said = InferenceError.Rejected("qwen3.6-35b-fast", 401, "invalid key").message ?: ""

        assertTrue(said, said.contains("qwen3.6-35b-fast"))
        assertTrue(said, said.contains("401"))
        assertTrue(said, said.contains("invalid key"))
    }

    @Test
    fun nothingIsAskedUntilSomethingCollects() = runTest {
        // The property that makes cancelling a turn work: a flow built and
        // dropped must not have sent anything.
        val inference = ScriptedInference("hello")
        inference.complete(Conversation(), model = "cheap")

        assertEquals(emptyList<String>(), inference.asked)
    }

    @Test
    fun collectingAsksTheModelItWasGiven() = runTest {
        val inference = ScriptedInference("hello")
        inference.complete(Conversation(), model = "deepseek-v4-flash").toList()

        assertEquals(listOf("deepseek-v4-flash"), inference.asked)
    }

    @Test
    fun theAnswerArrivesInThePiecesItWasScriptedAs() = runTest {
        val pieces = listOf(StreamEvent.Text("the bins "), StreamEvent.Text("go out Tuesday"))
        val got = ScriptedInference(pieces).complete(Conversation(), model = "cheap").toList()

        assertEquals(pieces, got)
    }

    @Test
    fun aFailureArrivesWhereItIsRead() = runTest {
        // Not at construction, and not as a second return value. A caller that
        // forgets to check a status code is a caller that carries on; one that
        // forgets to catch is one that stops.
        val inference = ScriptedInference(
            events = emptyList(),
            failWith = InferenceError.Unavailable("kimi-k3", "timeout"),
        )

        try {
            inference.complete(Conversation(), model = "kimi-k3").toList()
            throw AssertionError("collected without failing")
        } catch (e: InferenceError) {
            assertTrue(e.isWorthAnotherModel)
        }
    }
}
