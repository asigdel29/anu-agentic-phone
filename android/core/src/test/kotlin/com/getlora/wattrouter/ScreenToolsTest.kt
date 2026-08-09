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

    override suspend fun tap(at: Handle, from: Generation): Done? = null

    override suspend fun type(at: Handle, from: Generation, text: String): Done? = null

    override suspend fun navigate(way: Way): Done? = null
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

private class Tapping(private val done: Done?, private val reading: Reading? = null) : Phone {
    var asked: Pair<Handle, Generation>? = null
    var typed: String? = null
    var pressed: Way? = null

    override suspend fun read() = reading

    override suspend fun tap(at: Handle, from: Generation) = done.also { asked = at to from }

    override suspend fun type(at: Handle, from: Generation, text: String) =
        done.also { asked = at to from; typed = text }

    override suspend fun navigate(way: Way) = done.also { pressed = way }
}

class TapToolTest {
    private val generation = Generation("k3f9", 4)
    private val send = Handle("send", "button", "Send", null, 0)

    private fun call(handle: String = encode(send), screen: String = "k3f9.4") =
        """{"handle":"$handle","screen":"$screen"}"""

    @Test
    fun aHandleAndAScreenIdReachThePhoneUntouched() = runTest {
        // What read_screen printed is what the phone is asked for. Anything
        // rewritten in between is a tap on something else.
        val phone = Tapping(Done.Did(null))

        TapTool(phone).run(call())

        assertEquals(send to generation, phone.asked)
    }

    @Test
    fun aHandleThisBuildDidNotWriteIsRefusedBeforeAnythingHappens() = runTest {
        // Assembled with defaults it would resolve, and resolving is acting.
        val phone = Tapping(Done.Did(null))

        val said = TapTool(phone).run(call(handle = "the Send button"))

        assertTrue(said, said.startsWith("that is not a handle"))
        assertNull("nothing should have been tapped", phone.asked)
    }

    @Test
    fun aScreenIdThisBuildDidNotWriteIsRefusedTheSameWay() = runTest {
        val phone = Tapping(Done.Did(null))

        val said = TapTool(phone).run(call(screen = "the last one"))

        assertTrue(said, said.startsWith("that is not a screen id"))
        assertNull(phone.asked)
    }

    @Test
    fun aTapAnswersWithTheScreenAndSaysItMaySettle() {
        val after = Reading(Generation("k3f9", 5), listOf(Sighting(send, "button", "Send", isClickable = true)))

        val said = TapTool.say(Done.Did(after))

        assertTrue(said, said.startsWith("tapped."))
        assertTrue(said, said.contains("still be settling"))
        assertTrue(said, said.contains("screen k3f9.5"))
    }

    @Test
    fun aScreenThatMovedIsAnInstructionRatherThanAnError() {
        // Not a failure of the tap. Told "failed", a model retries — which is
        // right for one of the four outcomes and wrong for this one.
        val now = Reading(Generation("k3f9", 5), listOf(Sighting(send, "button", "Send", isClickable = true)))

        val said = TapTool.say(Done.Moved(now))

        assertTrue(said, said.contains("nothing was tapped"))
        assertTrue(said, said.contains("screen k3f9.5"))
    }

    @Test
    fun eachWayOfMissingGetsItsOwnInstruction() {
        assertTrue(TapTool.say(Done.Lost(Resolution.Ambiguous(3))).contains("matches 3 things"))
        assertTrue(TapTool.say(Done.Lost(Resolution.Unusable)).contains("does not describe anything"))
        assertTrue(TapTool.say(Done.Lost(Resolution.Missing)).contains("not on it any more"))
    }

    @Test
    fun theOneThatIsAboutTheTapSaysSo() {
        val said = TapTool.say(Done.Refused("it is disabled"))

        assertTrue(said, said.contains("could not be tapped: it is disabled"))
    }

    @Test
    fun anUnreadableScreenSaysWhatReadScreenSays() = runTest {
        // One sentence for one condition, rather than two tools disagreeing
        // about how to describe the service being off.
        val said = TapTool(Tapping(null)).run(call())

        assertEquals(ReadScreenTool.describe(null), said)
    }
}

class TypeTextToolTest {
    private val generation = Generation("k3f9", 4)
    private val field = Handle("message", "field", "Message", null, 1)

    private fun call(text: String = "hello", handle: String = encode(field), screen: String = "k3f9.4") =
        """{"handle":"$handle","screen":"$screen","text":"$text"}"""

    @Test
    fun theTextReachesThePhoneAsWritten() = runTest {
        val phone = Tapping(Done.Did(null))

        TypeTextTool(phone).run(call(text = "meet at six"))

        assertEquals(field to generation, phone.asked)
        assertEquals("meet at six", phone.typed)
    }

    @Test
    fun thePurposeSaysItReplacesRatherThanAppends() {
        // The only documentation a model gets. Without it, a correction is
        // typed onto the end and the field reads "LondonLondon".
        val said = TypeTextTool(Tapping(null)).purpose

        assertTrue(said, said.contains("REPLACES"))
    }

    @Test
    fun anEmptyStringClearsAFieldAndAMissingOneDoesNot() = runTest {
        // Absent is a call that forgot half of itself, and answering it by
        // emptying the field would be doing something nobody asked for.
        val clearing = Tapping(Done.Did(null))
        TypeTextTool(clearing).run(call(text = ""))
        assertEquals("", clearing.typed)

        val forgot = Tapping(Done.Did(null))
        val said = TypeTextTool(forgot).run("""{"handle":"${encode(field)}","screen":"k3f9.4"}""")
        assertTrue(said, said.startsWith("no text was given"))
        assertNull(forgot.typed)
    }

    @Test
    fun aBadHandleOrScreenIsRefusedBeforeAnythingIsTyped() = runTest {
        val phone = Tapping(Done.Did(null))

        assertTrue(TypeTextTool(phone).run(call(handle = "the field")).startsWith("that is not a handle"))
        assertTrue(TypeTextTool(phone).run(call(screen = "last")).startsWith("that is not a screen id"))
        assertNull(phone.typed)
    }

    @Test
    fun everyOutcomeIsWordedTheWayATapIs() {
        // One vocabulary for both. Two tools describing a moved screen
        // differently is two things for a model to learn about one event.
        assertEquals(TapTool.say(Done.Lost(Resolution.Missing)), TapTool.say(Done.Lost(Resolution.Missing)))
        assertTrue(TapTool.say(Done.Refused("that is a password field")).contains("password field"))
    }
}

class NavigateToolTest {
    @Test
    fun eachWordReachesItsButton() = runTest {
        Way.entries.forEach { way ->
            val phone = Tapping(Done.Did(null))

            NavigateTool(phone).run("""{"where":"${way.word}"}""")

            assertEquals(way, phone.pressed)
        }
    }

    @Test
    fun aWordThatIsNotOneListsTheOnesThatAre() = runTest {
        // The posture ToolBox takes on an unknown tool name. Guessing at a
        // near miss is the mistake resolve refuses to make.
        val phone = Tapping(Done.Did(null))

        val said = NavigateTool(phone).run("""{"where":"go_back"}""")

        assertTrue(said, said.contains("back, home, recents, notifications"))
        assertNull("nothing should have been pressed", phone.pressed)
    }

    @Test
    fun aCallWithNoButtonIsTheSameRefusal() = runTest {
        val phone = Tapping(Done.Did(null))

        assertTrue(NavigateTool(phone).run("{}").contains("Try one of"))
        assertTrue(NavigateTool(phone).run("not json").contains("Try one of"))
        assertNull(phone.pressed)
    }

    @Test
    fun aWordWithSpaceAroundItStillWorks() = runTest {
        // The same forgiveness decode gives a token. Models pad things.
        val phone = Tapping(Done.Did(null))

        NavigateTool(phone).run("""{"where":" home "}""")

        assertEquals(Way.HOME, phone.pressed)
    }

    @Test
    fun thePurposeSaysBackDependsOnWhereItIsPressed() {
        // From an app's first screen it leaves the app; over a keyboard it
        // closes the keyboard. The tool cannot know which, so it says so.
        val said = NavigateTool(Tapping(null)).purpose

        assertTrue(said, said.contains("leaves the app"))
        assertTrue(said, said.contains("keyboard"))
    }
}

class ScrollColumnTest {
    @Test
    fun aScrollableLineIsMarkedScroll() {
        // The action column is the only way a model knows what a line accepts,
        // and every refusal so far points at it. A scroll tool with nothing
        // marked scroll would cite a column that never says the word.
        val list = Sighting(
            handle = Handle("messages", "list", null, null, 0),
            role = "list",
            label = null,
            isScrollable = true,
        )

        val said = ReadScreenTool.describe(Reading(Generation("k3f9", 4), listOf(list)))

        assertTrue(said, said.contains("scroll    h:messages|list|||0"))
    }

    @Test
    fun somethingBothTappableAndScrollableReadsAsTappable() {
        // Tapping is the one that does something irreversible, so it is the
        // one worth naming.
        val both = Sighting(
            handle = Handle("card", "view", null, null, 0),
            role = "view",
            label = null,
            isClickable = true,
            isScrollable = true,
        )

        val said = ReadScreenTool.describe(Reading(Generation("k3f9", 4), listOf(both)))

        assertTrue(said, said.contains("tap"))
        assertTrue(said, !said.contains("scroll"))
    }
}
