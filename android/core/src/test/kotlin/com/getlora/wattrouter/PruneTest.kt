// PruneTest.kt: what survives a real tree, and what a model never sees.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against Seen. The shapes below are the ones a toolkit actually
// produces (a page inside four containers, a button wrapping its own label)
// rather than trees invented to suit the rules.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PruneTest {
    private fun frame(vararg children: Node) =
        Seen(role = "layout", children = children.toList())

    @Test
    fun containersGoAndWhatTheyHoldStays() {
        // Four frames deep is an ordinary page. Dropping a container without
        // flattening would take the page with it.
        val page = frame(frame(frame(frame(Seen(role = "text", text = "Inbox")))))

        val seen = prune(page)

        assertEquals(1, seen.size)
        assertEquals("Inbox", seen.first().label)
    }

    @Test
    fun depthCountsWhatSurvivedRatherThanWhatWasThere() {
        // A node six containers down and one meaningful parent down is one
        // level in. Indenting it six would describe the layout, not the page.
        val page = frame(
            Seen(
                role = "list",
                description = "Messages",
                children = listOf(frame(frame(Seen(role = "text", text = "Unread")))),
            ),
        )

        assertEquals(listOf(0, 1), prune(page).map { it.depth })
    }

    @Test
    fun aButtonWrappingItsLabelIsOneThing() {
        // The common shape in every toolkit. Shown both, the model taps the
        // label, nothing happens, and nothing says why.
        val button = Seen(
            viewId = "app:id/send",
            role = "button",
            text = "Send",
            isClickable = true,
            children = listOf(Seen(role = "text", text = "Send")),
        )

        val seen = prune(frame(button))

        assertEquals(1, seen.size)
        assertTrue("${seen.first()}", seen.first().isClickable)
        assertEquals("send", seen.first().handle.viewId)
    }

    @Test
    fun theInnerOneWinsWhenItIsTheOneThatCanBeTapped() {
        // Where the wrapper carries the text and the child carries the action,
        // the child is the control. The test is on the action, not the depth.
        val wrapper = Seen(
            role = "layout",
            description = "Attach",
            children = listOf(Seen(viewId = "app:id/clip", role = "button", isClickable = true)),
        )

        val seen = prune(frame(wrapper))

        assertEquals(2, seen.size)
        assertEquals("clip", seen.last().handle.viewId)
        assertTrue("${seen.last()}", seen.last().isClickable)
    }

    @Test
    fun aLabelThatSaysSomethingElseIsKept() {
        // Only an echo is dropped. A row whose title differs from its container
        // is two facts, and losing one loses the page.
        val row = Seen(
            role = "button",
            text = "Open",
            isClickable = true,
            children = listOf(Seen(role = "text", text = "Rent is due")),
        )

        assertEquals(listOf("Open", "Rent is due"), prune(frame(row)).map { it.label })
    }

    @Test
    fun aPasswordFieldIsReportedAndItsValueIsNot() {
        // A model that cannot see there is a password field cannot ask the
        // person to fill it in. What it holds is a different matter, and it
        // does not leave prune at all.
        val field = Seen(
            viewId = "app:id/password",
            role = "field",
            text = "hunter2",
            isEditable = true,
            isPassword = true,
        )

        val seen = prune(frame(field)).single()

        assertNull(seen.label)
        assertTrue("$seen", seen.isPassword && seen.isEditable)
        assertEquals("password", seen.handle.viewId)
    }

    @Test
    fun somethingNotOnScreenTakesItsSubtreeWithIt() {
        val drawer = Seen(
            role = "layout",
            isVisible = false,
            children = listOf(Seen(role = "text", text = "Settings")),
        )

        assertEquals(
            listOf("Inbox"),
            prune(frame(drawer, Seen(role = "text", text = "Inbox"))).map { it.label },
        )
    }

    @Test
    fun theOrderIsTheOrderSomebodyWouldReadThem() {
        val page = frame(
            Seen(role = "text", text = "first"),
            frame(Seen(role = "text", text = "second"), Seen(role = "text", text = "third")),
            Seen(role = "text", text = "fourth"),
        )

        assertEquals(
            listOf("first", "second", "third", "fourth"),
            prune(page).map { it.label },
        )
    }

    @Test
    fun anIconWithNothingToSayIsStillSomethingToTap() {
        val icon = Seen(viewId = "app:id/more", role = "button", isClickable = true)

        val seen = prune(frame(icon)).single()

        assertNull(seen.label)
        assertEquals("more", seen.handle.viewId)
    }
}

class ScrollablePruneTest {
    @Test
    fun aListThatSaysNothingIsStillWorthNaming() {
        // A RecyclerView holding a hundred rows has no text, no description
        // and no click. Dropped as a container, its rows survive and there is
        // no handle for the list itself, so nothing can be scrolled.
        val list = Seen(
            viewId = "app:id/messages",
            role = "list",
            isScrollable = true,
            children = listOf(Seen(role = "text", text = "Rent is due")),
        )

        val seen = prune(Seen(role = "window", children = listOf(list)))

        assertEquals(2, seen.size)
        assertEquals("messages", seen.first().handle.viewId)
        assertTrue("${seen.first()}", seen.first().isScrollable)
    }

    @Test
    fun aScrollableThatIsAlsoATextIsNotDuplicated() {
        // saysSomething gained a reason, not a second entry.
        val scrolling = Seen(role = "scroll", text = "Terms", isScrollable = true)

        assertEquals(1, prune(Seen(role = "window", children = listOf(scrolling))).size)
    }

    @Test
    fun theRowsInsideItAreStillFlattenedUp() {
        val list = Seen(
            viewId = "app:id/messages",
            role = "list",
            isScrollable = true,
            children = List(3) { at -> Seen(role = "text", text = "message $at") },
        )

        val seen = prune(Seen(role = "window", children = listOf(list)))

        assertEquals(listOf(null, "message 0", "message 1", "message 2"), seen.map { it.label })
        assertEquals(listOf(0, 1, 1, 1), seen.map { it.depth })
    }
}
