// RecollectionTest.kt — reading what the store answered.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Hand-written envelopes on the JVM. MemoryTest runs a real store on a device;
// what is checked here is what happens to its answer afterwards, including the
// shapes a healthy store does not produce.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecollectionTest {
    private fun piece(text: String, role: String) =
        """{"text":"$text","speaker":"user","ts":1786000000,"score":0.5,"role":"$role"}"""

    private fun envelope(vararg pieces: String) =
        """{"ok":{"route":"Relational","evidence":[${pieces.joinToString(",")}]}}"""

    @Test
    fun evidenceArrivesWithItsRouteAndItsRoles() {
        val read = requireNotNull(
            Recollection.from(
                envelope(piece("the spare key is with Dave", "Main"), piece("Dave moved", "GraphBridge")),
            ),
        )

        assertEquals("Relational", read.route)
        assertEquals(
            listOf(Remembered.Role.MAIN, Remembered.Role.GRAPH_BRIDGE),
            read.evidence.map { it.role },
        )
        assertEquals(1786000000L, read.evidence.first().ts)
    }

    @Test
    fun aRoleThisBuildHasNotBeenTaughtIsContextRatherThanEvidence() {
        // Guessing towards evidence makes the model state something nobody
        // said; towards context it hedges about something true. Only one is a
        // lie, so the unknown case picks the other.
        val read = requireNotNull(Recollection.from(envelope(piece("x", "Sideways"))))

        assertEquals(Remembered.Role.GRAPH_BRIDGE, read.evidence.single().role)
    }

    @Test
    fun anEmptyRecollectionIsNotAFailedOne() {
        // "Nothing about that" and "the store could not be searched" are
        // different answers, and flattening one into the other lies to the
        // model about its own memory.
        assertTrue(requireNotNull(Recollection.from(envelope())).isEmpty)
        assertNull(Recollection.from("""{"error":"store is not open"}"""))
        assertNull(Recollection.from(null))
        assertNull(Recollection.from("not json"))
    }

    @Test
    fun aPieceWithNoTextIsDroppedRatherThanShownBlank() {
        val read = Recollection.from(
            """{"ok":{"route":"Local","evidence":[{"speaker":"user"},${piece("real", "Main")}]}}""",
        )

        assertEquals(listOf("real"), read?.evidence?.map { it.text })
    }

    @Test
    fun aMissingScoreOrTimestampDoesNotLoseTheTurn() {
        // The text is the answer; the rest is about trusting it. Dropping a
        // turn because a number was absent loses the part that mattered.
        val read = Recollection.from("""{"ok":{"route":"Local","evidence":[{"text":"kept"}]}}""")

        assertEquals("kept", read?.evidence?.single()?.text)
        assertEquals(0L, read?.evidence?.single()?.ts)
    }
}
