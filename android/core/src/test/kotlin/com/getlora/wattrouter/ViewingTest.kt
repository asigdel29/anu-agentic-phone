// ViewingTest.kt — what stops an action, and what it is told instead.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against Seen. Three ways to miss and they are three answers: the
// screen moved, the screen is current and the thing is not on it, or the handle
// never named anything. A tool that cannot tell them apart cannot word one.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewingTest {
    private val send = Seen(viewId = "app:id/send", role = "button", text = "Send", isClickable = true)
    private val clock = Seen(viewId = "app:id/clock", role = "text", text = "09:41")

    private fun page(vararg children: Node) =
        Seen(role = "window", children = children.toList())

    private fun viewing() = Viewing(Generations("life"))

    @Test
    fun aHandleReadNowHitsNow() {
        val viewing = viewing()
        val screen = page(clock, send)

        val read = viewing.read(screen)
        val aim = viewing.aim(screen, read.seen.first { it.isClickable }.handle, read.generation)

        assertTrue("$aim", aim is Aim.At)
        assertEquals("Send", (aim as Aim.At).node.text)
    }

    @Test
    fun readingTheSameScreenAgainKeepsTheHandles() {
        // A model that looks twice without anything changing should not have
        // to throw away what it was holding.
        val viewing = viewing()
        val screen = page(clock, send)

        val first = viewing.read(screen)
        val second = viewing.read(screen)

        assertEquals(first.generation, second.generation)
        assertEquals(first.seen, second.seen)
    }

    @Test
    fun aScreenThatMovedIsRefusedAndSaysWhatIsThere() {
        // The refusal that costs one exchange rather than two. Told only "that
        // is stale", the model reads again — and on a page with anything
        // dynamic the structure can move again in between.
        val viewing = viewing()
        val before = viewing.read(page(clock, send))
        val held = before.seen.first { it.isClickable }.handle

        val aim = viewing.aim(page(clock), held, before.generation)

        assertTrue("$aim", aim is Aim.Moved)
        val now = (aim as Aim.Moved).now
        assertEquals(listOf("09:41"), now.seen.map { it.label })
        assertTrue(now.generation.counter > before.generation.counter)
    }

    @Test
    fun whatTheRefusalCarriesIsUsableStraightAway() {
        // The point of carrying it: the handles in it work, so the next call
        // is the action rather than another read.
        val viewing = viewing()
        val before = viewing.read(page(clock))
        val moved = viewing.aim(page(clock, send), Handle(viewId = "clock", role = "text"), before.generation)

        val now = (moved as Aim.Moved).now
        val again = viewing.aim(page(clock, send), now.seen.first { it.isClickable }.handle, now.generation)

        assertTrue("$again", again is Aim.At)
        assertEquals("Send", (again as Aim.At).node.text)
    }

    @Test
    fun aHandleFromAnotherLifeIsARefusalTooEvenAtTheSameCount() {
        // The epoch doing its job through this layer. Both counters are 1.
        val screen = page(clock, send)
        val before = Viewing(Generations("first")).read(screen)
        val after = Viewing(Generations("second"))
        after.read(screen)

        val aim = after.aim(screen, before.seen.first { it.isClickable }.handle, before.generation)

        assertEquals(1, before.generation.counter)
        assertTrue("$aim", aim is Aim.Moved)
    }

    @Test
    fun aRecycledRowIsLostRatherThanMoved() {
        // The only way Lost is reached: the shape is identical and the content
        // is not, which is a list that scrolled. A node that disappeared moves
        // the shape, and that answer is Moved.
        val rows = { from: Int ->
            page(Seen(role = "list", children = List(4) { at ->
                Seen(viewId = "app:id/row", role = "text", text = "message ${from + at}")
            }))
        }
        val viewing = viewing()
        val before = viewing.read(rows(0))
        val held = before.seen.first { it.label == "message 2" }.handle

        val aim = viewing.aim(rows(90), held, before.generation)

        assertEquals(Aim.Lost(Resolution.Missing), aim)
    }

    @Test
    fun aHandleTheModelInventedIsItsOwnAnswer() {
        val viewing = viewing()
        val screen = page(clock, send)
        val read = viewing.read(screen)

        val aim = viewing.aim(screen, Handle(role = "button", siblingIndex = 9), read.generation)

        assertEquals(Aim.Lost(Resolution.Unusable), aim)
    }

    @Test
    fun aPasswordFieldIsSeenAndItsValueIsNot() {
        // prune's rule, still holding one layer up: what leaves Viewing is what
        // may be shown.
        val viewing = viewing()
        val field = Seen(viewId = "app:id/pin", role = "field", text = "1234", isEditable = true, isPassword = true)

        val read = viewing.read(page(field))

        assertEquals(listOf(null), read.seen.map { it.label })
        assertTrue(read.seen.single().isPassword)
    }
}
