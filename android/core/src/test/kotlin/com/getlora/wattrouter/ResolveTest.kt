// ResolveTest.kt: what is found, what is refused, and the difference.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against Seen, the eleven-line fake #400 exists for. Every case
// here is one of the two wrong readings of "match a handle": too strict, and a
// relabelled button is lost; too loose, and something else gets tapped.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveTest {
    private val send = Seen(viewId = "app:id/send", role = "button", text = "Send")
    private val cancel = Seen(viewId = "app:id/cancel", role = "button", text = "Cancel")

    private fun screen(vararg children: Node) = Seen(role = "window", children = children.toList())

    private fun found(of: Resolution): Node {
        assertTrue("$of", of is Resolution.Found)
        return (of as Resolution.Found).node
    }

    @Test
    fun anIdFindsItsNode() {
        val node = found(resolve(screen(cancel, send), Handle(viewId = "send", role = "button")))

        assertEquals("Send", node.text)
    }

    @Test
    fun aRelabelledButtonIsStillThatButton() {
        // The false refusal the strict reading produces. Requiring every field
        // would lose this, and it is the ordinary case: labels change.
        val renamed = send.copy(text = "Send now")

        val node = found(
            resolve(screen(cancel, renamed), Handle(viewId = "send", text = "Send", role = "button")),
        )

        assertEquals("Send now", node.text)
    }

    @Test
    fun aNodeThatIsGoneIsMissingRatherThanSomethingElse() {
        // The most durable field is a hard requirement, so nothing matching it
        // ends the search rather than falling back to a weaker one.
        assertEquals(
            Resolution.Missing,
            resolve(screen(cancel), Handle(viewId = "send", text = "Cancel", role = "button")),
        )
    }

    @Test
    fun twoIdenticalRowsAreSeparatedByWhereTheySat() {
        // What a list is. Without the sibling index this is ambiguous, and a
        // refusal on every list row would make the whole design unusable.
        val rows = List(3) { Seen(role = "text", text = "Unread") }

        val node = found(resolve(screen(*rows.toTypedArray()), Handle(text = "Unread", role = "text", siblingIndex = 1)))

        assertEquals("Unread", node.text)
    }

    @Test
    fun twoThingsThatCannotBeToldApartAreARefusal() {
        // Never a choice. The model recovers from being told it was ambiguous
        // and cannot recover from a tap on the wrong row.
        //
        // Both first rows of their own section, so even the sibling index
        // agrees, which is what it takes, and is why a flat list of lookalikes
        // is not this case: there the index separates them.
        val row = Seen(viewId = "app:id/row", role = "text", text = "Unread")
        val sections = List(2) { Seen(role = "list", children = listOf(row)) }

        val resolution = resolve(
            screen(*sections.toTypedArray()),
            Handle(viewId = "row", role = "text", text = "Unread"),
        )

        assertEquals(Resolution.Ambiguous(2), resolution)
    }

    @Test
    fun aFlatListOfLookalikesIsNotAmbiguous() {
        // The other half of the case above, and the one that would make this
        // design unusable if it refused: every row of a list looks like every
        // other, and the index is what tells them apart.
        val rows = List(4) { Seen(viewId = "app:id/row", role = "text", text = "Unread") }

        val resolution = resolve(
            screen(*rows.toTypedArray()),
            Handle(viewId = "row", role = "text", text = "Unread", siblingIndex = 2),
        )

        assertTrue("$resolution", resolution is Resolution.Found)
    }

    @Test
    fun oneCandidateIsNotSecondGuessedByAnythingWeaker() {
        // What forgives a relabelled button, and a moved one: with a single
        // candidate the narrowing stops before it is consulted at all.
        val node = found(
            resolve(
                screen(cancel, send),
                Handle(viewId = "send", role = "button", text = "Send", siblingIndex = 7),
            ),
        )

        assertEquals("Send", node.text)
    }

    @Test
    fun aRecycledListDoesNotResolveToWhateverScrolledIn() {
        // RecyclerView reuses view objects: the ids and the shape survive a
        // scroll and the text does not. Ignoring text because it matches none
        // of the rows, then falling through to the sibling index, taps a
        // message the model never saw. #405.
        val scrolled = List(4) { at ->
            Seen(viewId = "app:id/row", role = "text", text = "message ${at + 90}")
        }

        val resolution = resolve(
            screen(*scrolled.toTypedArray()),
            Handle(viewId = "row", role = "text", text = "Rent is due", siblingIndex = 2),
        )

        assertEquals(Resolution.Missing, resolution)
    }

    @Test
    fun theRowThatIsStillThereIsStillFound() {
        // The other side of it: a list that has not moved resolves as before,
        // so the refusal above is not a refusal of lists.
        val rows = List(4) { at -> Seen(viewId = "app:id/row", role = "text", text = "message $at") }

        val node = found(
            resolve(
                screen(*rows.toTypedArray()),
                Handle(viewId = "row", role = "text", text = "message 2", siblingIndex = 2),
            ),
        )

        assertEquals("message 2", node.text)
    }

    @Test
    fun somethingLaidOutAndNotOnScreenIsNotACandidate() {
        // Acting on one silently does nothing, which is the failure furthest
        // from a refusal.
        val hidden = send.copy(isVisible = false)

        assertEquals(
            Resolution.Missing,
            resolve(screen(cancel, hidden), Handle(viewId = "send", role = "button")),
        )
    }

    @Test
    fun aHandleThatNamesNothingIsItsOwnAnswer() {
        // It did not fail to find something; it never described one.
        assertEquals(
            Resolution.Unusable,
            resolve(screen(send), Handle(role = "button", siblingIndex = 3)),
        )
    }

    @Test
    fun aNodeIsFoundHoweverDeepItIs() {
        val deep = screen(Seen(role = "list", children = listOf(Seen(role = "row", children = listOf(send)))))

        assertEquals("Send", found(resolve(deep, Handle(viewId = "send", role = "button"))).text)
    }

    @Test
    fun aDescriptionIsEnoughWhenThereIsNoIdAndNoText() {
        val icon = Seen(role = "button", description = "Attach a file", isClickable = true)

        val node = found(resolve(screen(cancel, icon), Handle(description = "Attach a file", role = "button")))

        assertEquals("Attach a file", node.description)
    }
}
