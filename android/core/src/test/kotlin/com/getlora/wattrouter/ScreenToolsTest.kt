// ScreenToolsTest.kt — what a model is shown, and what it is told instead.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against a scripted Phone. The rendering is the whole of what a
// model has to work from, so the assertions are on the text rather than on the
// values behind it.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Showing(private val reading: Reading?) : Phone {
    override suspend fun read() = reading

    override suspend fun aim(at: Handle, from: Generation): Aim? = null
}

class ScreenToolsTest {
    private val generation = Generation("k3f9", 4)

    private fun sighting(
        id: String? = null,
        role: String = "text",
        label: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        depth: Int = 0,
        index: Int = 0,
    ) = Sighting(
        handle = Handle(id, role, label, null, index),
        role = role,
        label = label,
        isClickable = clickable,
        isEditable = editable,
        isPassword = password,
        depth = depth,
    )

    @Test
    fun aGenerationGoesOutAndComesBack() {
        assertEquals("k3f9.4", encodeSeen(generation))
        assertEquals(generation, decodeSeen("k3f9.4"))
        assertEquals(generation, decodeSeen("  k3f9.4 "))
    }

    @Test
    fun aGenerationThisBuildDidNotWriteIsRefused() {
        // Assembled with a default counter, it would compare equal to a real
        // reading and let a stale handle through.
        assertNull(decodeSeen(null))
        assertNull(decodeSeen("k3f9"))
        assertNull(decodeSeen("k3f9."))
        assertNull(decodeSeen(".4"))
        assertNull(decodeSeen("k3f9.later"))
        assertNull(decodeSeen("k3f9.-1"))
    }

    @Test
    fun aScreenIsItsIdAndOneLinePerThing() = runTest {
        val said = ReadScreenTool(
            Showing(
                Reading(
                    generation,
                    listOf(
                        sighting(id = "send", role = "button", label = "Send", clickable = true),
                        sighting(id = "message", role = "field", label = "Message", editable = true, depth = 1, index = 1),
                    ),
                ),
            ),
        ).run("{}")

        assertEquals(
            "screen k3f9.4\n" +
                "tap       h:send|button|Send||0\n" +
                "  type      h:message|field|Message||1",
            said,
        )
    }

    @Test
    fun aPasswordIsNamedAndNotOfferedAsSomewhereToType() {
        // Its value never left prune, so telling a model to fill it in is
        // telling it to invent one.
        val said = ReadScreenTool.describe(
            Reading(generation, listOf(sighting(id = "pin", role = "field", editable = true, password = true))),
        )

        assertTrue(said, said.contains("password  h:pin|field|||0"))
        assertTrue(said, !said.contains("type"))
    }

    @Test
    fun aScreenThatCannotBeReadSaysBothWaysToFixIt() {
        // The second is the failure how-the-agent-drives.md calls the one with
        // no error attached: on a sideloaded build the toggle is greyed until
        // restricted settings are cleared, and nothing says so.
        val said = ReadScreenTool.describe(null)

        assertTrue(said, said.contains("Settings > Accessibility > WattRouter"))
        assertTrue(said, said.contains("restricted settings"))
    }

    @Test
    fun anEmptyScreenIsNotAnUnreadableOne() = runTest {
        val said = ReadScreenTool(Showing(Reading(generation, emptyList()))).run("{}")

        assertEquals("the screen is readable and has nothing on it", said)
    }

    @Test
    fun aLongScreenSaysHowMuchItLeftOut() {
        val many = List(ReadScreenTool.LIMIT + 7) { sighting(label = "row $it", index = it) }

        val said = ReadScreenTool.describe(Reading(generation, many))

        assertTrue(said, said.endsWith("and 7 more not shown"))
        // The heading, the lines, and the tail.
        assertEquals(ReadScreenTool.LIMIT + 2, said.lines().size)
    }

    @Test
    fun aHandleOffTheScreenIsOneThatResolves() {
        // The round trip that matters: what a line prints is what comes back.
        val seen = sighting(id = "send", role = "button", label = "Send", clickable = true)
        val printed = ReadScreenTool.describe(Reading(generation, listOf(seen)))

        val token = printed.lines().last().trim().substringAfter("  ").trim()

        assertEquals(seen.handle, decode(token))
    }
}
