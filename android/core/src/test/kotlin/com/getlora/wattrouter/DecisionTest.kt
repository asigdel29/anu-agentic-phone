// DecisionTest.kt — reading the envelope the core answers with.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Hand-written envelopes on the JVM. CoreTest already proves the real core
// produces this shape on a device; what is under test here is what happens to
// it afterwards, including the shapes a real core does not produce and a
// corrupted one might.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecisionTest {

    @Test
    fun aDecisionArrivesWithItsChainInOrder() {
        val read = Decision.from(
            """
            {"ok":{"tier":"code","reason":"heuristic","score":0.72,"chain":[
              {"model":"kimi-k2.7-code","backend":"remote"},
              {"model":"qwen3.6-35b-fast","backend":"remote"}]}}
            """.trimIndent(),
        )

        assertEquals("code", read?.tier)
        assertEquals("heuristic", read?.reason)
        assertEquals(0.72f, read?.score)
        assertEquals(
            listOf("kimi-k2.7-code", "qwen3.6-35b-fast"),
            read?.chain?.map { it.model },
        )
    }

    @Test
    fun anAbsentScoreIsAbsentRatherThanZero() {
        // The unscored path — a heuristic decision never reaches the head. #314
        // made the core omit the key rather than send null, and zero here would
        // be a number somebody compares against a threshold.
        val read = Decision.from(
            """{"ok":{"tier":"cheap","reason":"heuristic","chain":[]}}""",
        )

        assertNull(read?.score)
        assertEquals("cheap", read?.tier)
    }

    @Test
    fun aLocalStepIsToldFromARemoteOne() {
        // Nothing runs locally yet — #188 is the checklist for when one does —
        // but a walk has to skip local steps rather than dial them.
        val read = Decision.from(
            """
            {"ok":{"tier":"cheap","reason":"sticky","chain":[
              {"model":"on-device","backend":"local"},
              {"model":"deepseek-v4-flash","backend":"remote"}]}}
            """.trimIndent(),
        )

        assertEquals(listOf(Backend.LOCAL, Backend.REMOTE), read?.chain?.map { it.backend })
    }

    @Test
    fun aBackendThisBuildHasNotBeenTaughtIsNotFatal() {
        // A newer core than this Kotlin. Throwing away the whole decision over
        // one step is worse than carrying a step a walk will skip.
        val read = Decision.from(
            """{"ok":{"tier":"mid","reason":"scored","chain":[{"model":"x","backend":"npu"}]}}""",
        )

        assertEquals(Backend.UNKNOWN, read?.chain?.single()?.backend)
    }

    @Test
    fun anErrorEnvelopeIsNoDecision() {
        assertNull(Decision.from("""{"error":"body was not a chat completion request"}"""))
    }

    @Test
    fun soIsNothingAtAll() {
        // decide returns null only when the runtime could not allocate a
        // string, which is an out-of-memory condition rather than an answer.
        assertNull(Decision.from(null))
    }

    @Test
    fun soIsSomethingThatIsNotAnEnvelope() {
        assertNull(Decision.from("not json"))
        assertNull(Decision.from("""{"ok":{"reason":"scored"}}"""))
    }

    @Test
    fun aStepWithoutAModelIsDroppedRatherThanCarried() {
        // The rest of the decision is usable, and a nameless step is one a walk
        // would try to dial.
        val read = Decision.from(
            """
            {"ok":{"tier":"mid","reason":"scored","chain":[
              {"backend":"remote"},{"model":"real","backend":"remote"}]}}
            """.trimIndent(),
        )

        assertEquals(listOf("real"), read?.chain?.map { it.model })
    }
}
