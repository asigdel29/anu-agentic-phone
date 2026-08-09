// ChainWalkTest.kt — which failures are worth the next model, and when.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On the JVM against scripted inferences. The two rules under test are the
// whole file: retry a server error, and never retry once anything has been
// delivered.

package com.getlora.wattrouter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainWalkTest {
    private fun remote(vararg models: String) = models.map { Step(it, Backend.REMOTE) }

    /** Fails for the models named, answers for anything else. */
    private class Flaky(
        private val failing: Set<String>,
        private val error: (String) -> InferenceError = {
            InferenceError.Unavailable(it, "connection reset")
        },
        private val says: String = "an answer",
    ) : Inference {
        val asked = mutableListOf<String>()

        override fun complete(
            conversation: Conversation,
            model: String,
            tools: String?,
            maxTokens: Int?,
        ): Flow<StreamEvent> = flow {
            asked += model
            if (model in failing) throw error(model)
            emit(StreamEvent.Text(says))
        }
    }

    @Test
    fun theFirstModelThatAnswersIsTheOneUsed() = runTest {
        val asking = Flaky(failing = setOf("kimi-k3"))
        val got = ChainWalk(asking)
            .complete(Conversation(), remote("kimi-k3", "glm-5.2")).toList()

        assertEquals(listOf("kimi-k3", "glm-5.2"), asking.asked)
        assertEquals(TurnEvent.Answering("glm-5.2", Backend.REMOTE), got.first())
        assertEquals(TurnEvent.Text("an answer"), got.last())
    }

    @Test
    fun aRejectionStopsTheWalkWhereItStands() = runTest {
        // Every model behind the same API would reject the same body, so trying
        // five more proves nothing and reports the fifth one's version of it.
        val asking = Flaky(
            failing = setOf("kimi-k3"),
            error = { InferenceError.Rejected(it, 400, "bad tool schema") },
        )

        val thrown = runCatching {
            ChainWalk(asking).complete(Conversation(), remote("kimi-k3", "glm-5.2")).toList()
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is InferenceError.Rejected)
        assertEquals(listOf("kimi-k3"), asking.asked)
    }

    @Test
    fun aTurnThatHasSaidSomethingIsNotRestarted() = runTest {
        // The rule that matters. Nothing can un-emit an event, so retrying after
        // one has reached the transcript produces a turn whose first half came
        // from one model and second half from another.
        val asking = object : Inference {
            val asked = mutableListOf<String>()
            override fun complete(
                conversation: Conversation,
                model: String,
                tools: String?,
                maxTokens: Int?,
            ): Flow<StreamEvent> = flow {
                asked += model
                emit(StreamEvent.Text("half an "))
                throw InferenceError.Unavailable(model, "connection reset mid-answer")
            }
        }

        val got = mutableListOf<TurnEvent>()
        val thrown = runCatching {
            ChainWalk(asking).complete(Conversation(), remote("kimi-k3", "glm-5.2"))
                .collect { got += it }
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is InferenceError.Unavailable)
        assertEquals("only the first model should have been asked", 1, asking.asked.size)
        assertEquals(TurnEvent.Text("half an "), got.last())
    }

    @Test
    fun aToolCallCountsAsHavingSaidSomething() = runTest {
        // A tool-call fragment is as delivered as text: the Agent may already
        // have run it by the time the stream fails.
        val asking = object : Inference {
            val asked = mutableListOf<String>()
            override fun complete(
                conversation: Conversation,
                model: String,
                tools: String?,
                maxTokens: Int?,
            ): Flow<StreamEvent> = flow {
                asked += model
                emit(StreamEvent.Call(ToolCall("c1", "recall", "{}")))
                throw InferenceError.Unavailable(model, "reset")
            }
        }

        runCatching {
            ChainWalk(asking).complete(Conversation(), remote("a", "b")).toList()
        }

        assertEquals(1, asking.asked.size)
    }

    @Test
    fun aLocalStepIsCountedAndSkipped() = runTest {
        // Nothing runs a model in this process. Counting rather than ignoring
        // keeps the exhausted message honest about how many were considered.
        val asking = Flaky(failing = emptySet())
        val got = ChainWalk(asking)
            .complete(Conversation(), listOf(Step("on-device", Backend.LOCAL)) + remote("glm-5.2"))
            .toList()

        assertEquals(listOf("glm-5.2"), asking.asked)
        assertEquals(TurnEvent.Answering("glm-5.2", Backend.REMOTE), got.first())
    }

    @Test
    fun everyModelFailingIsExhausted() = runTest {
        val asking = Flaky(failing = setOf("a", "b"))

        val thrown = runCatching {
            ChainWalk(asking).complete(Conversation(), remote("a", "b")).toList()
        }.exceptionOrNull() as InferenceError

        assertTrue("$thrown", thrown is InferenceError.Exhausted)
        assertEquals(2, (thrown as InferenceError.Exhausted).tried)
        assertTrue(thrown.message.orEmpty(), thrown.message.orEmpty().contains("connection reset"))
    }

    @Test
    fun aTierWithNoModelsIsExhaustedRatherThanSilent() = runTest {
        // A misconfigured tier. Answering nothing would look like a model that
        // said nothing, which is a different bug to chase.
        val thrown = runCatching {
            ChainWalk(Flaky(emptySet())).complete(Conversation(), emptyList()).toList()
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is InferenceError.Exhausted)
    }

    @Test
    fun answeringIsSaidOnceRatherThanPerFragment() = runTest {
        val asking = object : Inference {
            override fun complete(
                conversation: Conversation,
                model: String,
                tools: String?,
                maxTokens: Int?,
            ): Flow<StreamEvent> = flow {
                emit(StreamEvent.Text("one "))
                emit(StreamEvent.Text("two"))
            }
        }

        val got = ChainWalk(asking).complete(Conversation(), remote("cheap")).toList()

        assertEquals(1, got.count { it is TurnEvent.Answering })
        assertEquals(3, got.size)
    }
}
