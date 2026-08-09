// GenerationTest.kt — what counts as a new screen, and what does not.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against Seen. Both halves matter and pull opposite ways: a
// generation that moves too easily refuses everything, and one that moves too
// rarely lets a handle act on a screen that has gone.

package com.getlora.wattrouter

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTest {
    private fun page(vararg children: Node) =
        Seen(role = "window", children = children.toList())

    private val clock = Seen(viewId = "app:id/clock", role = "text", text = "09:41")
    private val send = Seen(viewId = "app:id/send", role = "button", text = "Send", isClickable = true)

    @Test
    fun theSameScreenReadTwiceIsOneReading() {
        // A model that reads the screen again without anything having changed
        // keeps the handles it already holds.
        val generations = Generations("life")

        val first = generations.reading(page(clock, send))
        val second = generations.reading(page(clock, send))

        assertEquals(first, second)
        assertEquals(Generation("life", 1), first)
    }

    @Test
    fun aTickingClockIsNotANewScreen() {
        // The half that would make this unusable. Content changes several times
        // a second, and a generation moving with it makes every handle stale
        // before the model can use one.
        val generations = Generations("life")

        val first = generations.reading(page(clock, send))
        val later = generations.reading(page(clock.copy(text = "09:42"), send))

        assertEquals(first, later)
    }

    @Test
    fun somethingAppearingIsANewScreen() {
        val generations = Generations("life")

        val before = generations.reading(page(clock))
        val after = generations.reading(page(clock, send))

        assertNotEquals(before, after)
        assertEquals(2, after.counter)
    }

    @Test
    fun aRowInsertedAtTheTopIsANewScreen() {
        // No node's own description changes and every sibling index below
        // shifts, which is exactly when the handles pointing there should stop
        // being current. The child count is in the shape for this case.
        val row = Seen(viewId = "app:id/row", role = "text", text = "a message")
        val list = { count: Int -> page(Seen(role = "list", children = List(count) { row })) }

        assertNotEquals(shapeOf(list(3)), shapeOf(list(4)))
    }

    @Test
    fun aDrawerOpeningIsANewScreen() {
        val shut = Seen(role = "layout", isVisible = false, children = listOf(send))

        assertNotEquals(shapeOf(page(clock, shut)), shapeOf(page(clock, shut.copy(isVisible = true))))
    }

    @Test
    fun whatIsInsideAShutDrawerIsNotPartOfTheScreen() {
        // The subtree contributes nothing at all, so a drawer rearranging
        // itself out of sight does not make every handle on the page stale.
        val shut = Seen(role = "layout", isVisible = false, children = listOf(send))
        val rearranged = shut.copy(children = listOf(send, clock, clock))

        assertEquals(shapeOf(page(clock, shut)), shapeOf(page(clock, rearranged)))
    }

    @Test
    fun anInvisibleSiblingStillCountsWhereItSits() {
        // It contributes nothing itself and it is still one of its parent's
        // children, which is what sibling indexes are counted over — in prune
        // and in resolve both. Adding one shifts every index after it, so the
        // shape has to move even though nothing appeared on screen.
        val shut = Seen(role = "layout", isVisible = false, children = listOf(send))

        assertNotEquals(shapeOf(page(clock)), shapeOf(page(clock, shut)))
    }

    @Test
    fun becomingClickableIsANewScreen() {
        // A button that was disabled and now is not is a different page to act
        // on, even though it reads the same.
        assertNotEquals(
            shapeOf(page(send.copy(isClickable = false))),
            shapeOf(page(send)),
        )
    }

    @Test
    fun aHandleFromAnotherLifeOfTheServiceIsNotCurrent() {
        // The reason there are two fields. Both counters are 1, and a counter
        // alone would have called this a match.
        val before = Generations("first").also { it.reading(page(send)) }
        val after = Generations("second").also { it.reading(page(send)) }

        assertEquals(1, before.current.counter)
        assertEquals(1, after.current.counter)
        assertFalse(after.isCurrent(before.current))
    }

    @Test
    fun nothingIsCurrentBeforeTheScreenHasBeenRead() {
        // Counter zero is before any reading, which no handle can carry.
        val generations = Generations("life")

        assertEquals(0, generations.current.counter)
        assertFalse(generations.isCurrent(Generation("life", 1)))
    }

    @Test
    fun aReadingIsCurrentUntilTheScreenChanges() {
        val generations = Generations("life")
        val held = generations.reading(page(clock, send))

        generations.reading(page(clock.copy(text = "09:42"), send))
        assertTrue(generations.isCurrent(held))

        generations.reading(page(clock))
        assertFalse(generations.isCurrent(held))
    }

    @Test
    fun twoLivesDoNotShareAnEpoch() {
        // Random rather than a clock, which moves backwards on a time-zone
        // change and on an NTP correction.
        val epochs = List(50) { Generations.fresh(Random(it)).current.epoch }

        assertEquals(50, epochs.toSet().size)
        assertTrue(epochs.toString(), epochs.none { it.isBlank() })
    }
}
