// MemoryToolsTest.kt: what a turn is told it remembers.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Against a rendering function rather than a store: MemoryTest runs the real
// thing on a device and RecollectionTest covers the decoding. What is left is
// how a recollection reads, and the case that matters is context not reading
// as evidence.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsTest {
    private fun piece(text: String, role: String) =
        """{"text":"$text","speaker":"user","ts":1786000000,"score":0.5,"role":"$role"}"""

    private fun found(vararg pieces: String) =
        requireNotNull(
            Recollection.from("""{"ok":{"route":"Local","evidence":[${pieces.joinToString(",")}]}}"""),
        )

    @Test
    fun contextIsMarkedSoItIsNotStatedAsFact() {
        // The one that matters. A turn dragged in across the entity graph,
        // shown like one that matched, becomes a fact the model asserts.
        val said = RecallTool.describe(
            found(
                piece("the spare key is with Dave", "Main"),
                piece("Dave moved away in June", "GraphBridge"),
            ),
        )

        val lines = said.lines()
        assertFalse(lines[0], lines[0].contains("context"))
        assertTrue(lines[1], lines[1].contains("(context)"))
    }

    @Test
    fun anEmptyStoreSaysSoRatherThanAnsweringWithNothing() {
        // Distinguishable from a failure, and from a store never written to,
        // which a model would otherwise keep asking.
        assertTrue(RecallTool.describe(found()).contains("nothing remembered"))
    }

    @Test
    fun whenSomethingWasSaidIsShown() {
        // A fact from a year ago and one from this morning are different facts,
        // and nothing else in the line says which this is.
        val said = RecallTool.describe(found(piece("the bins go out Tuesday", "Main")))

        assertTrue(said, said.contains("2026-08-"))
        assertTrue(said, said.contains("bins go out Tuesday"))
    }

    @Test
    fun theListIsCapped() {
        val many = (1..RecallTool.LIMIT + 4).map { piece("fact $it", "Main") }.toTypedArray()

        assertEquals(RecallTool.LIMIT, RecallTool.describe(found(*many)).lines().size)
    }

    @Test
    fun aMalformedCallIsAnsweredInWordsRatherThanThrown() {
        // ToolBox would turn a throw into a readable result anyway, but these
        // two have something better to say than "failed".
        assertEquals("", Tools.field("not json", "text"))
        assertEquals("", Tools.field("""{"other":1}""", "text"))
    }
}
