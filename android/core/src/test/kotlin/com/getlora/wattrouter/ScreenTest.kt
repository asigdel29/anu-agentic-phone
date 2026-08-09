// ScreenTest.kt — what a handle records, and what it refuses to.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM, which is the seam's entire purpose: AccessibilityNodeInfo is
// final and comes from a service, so every rule below would otherwise need an
// emulator to check. The fake here is eleven lines.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A node, as a test writes one. */
internal data class Seen(
    override val viewId: String? = null,
    override val role: String = "text",
    override val text: String? = null,
    override val description: String? = null,
    override val isClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isPassword: Boolean = false,
    override val isVisible: Boolean = true,
    override val children: List<Node> = emptyList(),
) : Node

class ScreenTest {
    @Test
    fun anIdKeepsItsNameAndLosesItsPackage() {
        // The same node carries a different prefix inside another app's flow,
        // so a handle keeping the whole thing fails to match what it describes.
        assertEquals("send_button", nameOf("com.example.app:id/send_button"))
        assertEquals("send_button", nameOf("send_button"))
    }

    @Test
    fun anEmptyIdIsNoIdRatherThanAnEmptyOne() {
        // Some nodes carry one, and an empty string matching every other empty
        // string is worse than having no field.
        assertNull(nameOf(null))
        assertNull(nameOf(""))
        assertNull(nameOf("com.example.app:id/"))
        assertNull(nameOf("   "))
    }

    @Test
    fun aHandleRecordsWhatWasSeen() {
        val handle = handleFor(
            Seen(
                viewId = "com.example.app:id/send_button",
                role = "button",
                text = "Send",
                description = "Send the message",
                isClickable = true,
            ),
            siblingIndex = 2,
        )

        assertEquals(
            Handle("send_button", "button", "Send", "Send the message", 2),
            handle,
        )
    }

    @Test
    fun textIsTakenAsItReads() {
        // A handle describes what was seen, and a model that read "Send " with
        // its capital and its spacing should be able to write it back.
        assertEquals("  Send  ", handleFor(Seen(text = "  Send  ")).text)
        assertNull("blank is nothing rather than empty", handleFor(Seen(text = "   ")).text)
    }

    @Test
    fun aSiblingIndexIsRecordedWhetherOrNotItIsNeeded() {
        // It is the only field separating two identical rows in a list, which
        // is what a list is — so this is the common case, not the corner one.
        val rows = List(3) { Seen(role = "text", text = "Unread") }

        val handles = rows.mapIndexed { at, row -> handleFor(row, at) }

        assertEquals(3, handles.toSet().size)
        assertEquals(listOf(0, 1, 2), handles.map { it.siblingIndex })
    }

    @Test
    fun aHandleOfNothingButAPositionIsNotFindable() {
        // Which is the thing this design exists to not hand the model.
        assertFalse(handleFor(Seen(role = "text"), siblingIndex = 4).isFindable)
        assertFalse(handleFor(Seen(role = "text", text = "  ")).isFindable)
    }

    @Test
    fun anyOneOfTheThreeIsEnoughToLookFor() {
        assertTrue(handleFor(Seen(viewId = "x:id/a")).isFindable)
        assertTrue(handleFor(Seen(text = "Send")).isFindable)
        assertTrue(handleFor(Seen(description = "Send the message")).isFindable)
    }

    @Test
    fun aRoleAloneIsNotSomethingToSearchOn() {
        // Every button on a screen shares one, so a handle carrying only a role
        // is ambiguous by construction rather than by accident.
        assertFalse(handleFor(Seen(role = "button", isClickable = true)).isFindable)
    }
}
