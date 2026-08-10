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

private class Static(
    private val reading: Reading?,
    private val why: String? = null,
    private val attached: Boolean = true,
) : Phone {
    override suspend fun barredNow() = why

    override suspend fun attached() = attached

    override suspend fun read() = reading

    override suspend fun tap(at: Handle, from: Generation): Done? = null

    override suspend fun type(at: Handle, from: Generation, text: String): Done? = null

    override suspend fun navigate(way: Way): Done? = null

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? = null

    override suspend fun apps(): List<Launchable>? = null

    override suspend fun open(packageName: String): Done? = null
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
            Static(
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
    fun aScreenNothingMayReadSaysBothWaysToFixIt() = runTest {
        // The second is the failure how-the-agent-drives.md calls the one with
        // no error attached: on a sideloaded build the toggle is greyed until
        // restricted settings are cleared, and nothing says so.
        val said = ReadScreenTool(Static(null, attached = false)).run("{}")

        assertTrue(said, said.contains("Settings > Accessibility > WattRouter"))
        assertTrue(said, said.contains("restricted settings"))
    }

    @Test
    fun aScreenThatIsMerelyStillArrivingSaysNothingAboutSettings() = runTest {
        // #517. The service is on and the window has not landed, which is the
        // ordinary state of a phone for a moment after open_app — so this is
        // the answer the one sequence this application exists for produces.
        //
        // Against the code before the fix this said "Turn the assistant on",
        // sending somebody to a switch that was already on and telling a model
        // it lacked a capability it cannot grant itself.
        val said = ReadScreenTool(Static(null, attached = true)).run("{}")

        assertTrue(said, !said.contains("Settings"))
        assertTrue(said, !said.contains("restricted settings"))
        assertTrue(said, said.contains("Read it again"))
    }

    @Test
    fun theTwoUnreadableAnswersAreNotTheSameWords() {
        // Guards the distinction rather than either message: a later edit that
        // makes them agree again puts #517 back with both tests above green.
        assertTrue(ReadScreenTool.unreadable(attached = true) != ReadScreenTool.unreadable(attached = false))
    }

    @Test
    fun anEmptyScreenIsNotAnUnreadableOne() = runTest {
        val said = ReadScreenTool(Static(Reading(generation, emptyList()))).run("{}")

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
    override suspend fun attached() = true
    var asked: Pair<Handle, Generation>? = null
    var typed: String? = null
    var pressed: Way? = null
    var moved: Onward? = null
    var opened: String? = null
    var installed: List<Launchable>? = emptyList()
    var why: String? = null

    override suspend fun barredNow() = why

    override suspend fun read() = reading

    override suspend fun tap(at: Handle, from: Generation) = done.also { asked = at to from }

    override suspend fun type(at: Handle, from: Generation, text: String) =
        done.also { asked = at to from; typed = text }

    override suspend fun navigate(way: Way) = done.also { pressed = way }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward) =
        done.also { asked = at to from; moved = onward }

    override suspend fun apps() = installed

    override suspend fun open(packageName: String) = done.also { opened = packageName }
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
    fun aTapAnswersWithTheScreenAndSaysItMaySettle() = runTest {
        val after = Reading(Generation("k3f9", 5), listOf(Sighting(send, "button", "Send", isClickable = true)))

        val said = TapTool.say(Static(after), "tapped", Done.Did(after))

        assertTrue(said, said.startsWith("tapped."))
        assertTrue(said, said.contains("still be settling"))
        assertTrue(said, said.contains("screen k3f9.5"))
    }

    @Test
    fun aScreenThatMovedIsAnInstructionRatherThanAnError() = runTest {
        // Not a failure of the tap. Told "failed", a model retries — which is
        // right for one of the four outcomes and wrong for this one.
        val now = Reading(Generation("k3f9", 5), listOf(Sighting(send, "button", "Send", isClickable = true)))

        val said = TapTool.say(Static(now), "tapped", Done.Moved(now))

        assertTrue(said, said.contains("nothing was tapped"))
        assertTrue(said, said.contains("screen k3f9.5"))
    }

    @Test
    fun eachWayOfMissingGetsItsOwnInstruction() = runTest {
        val phone = Static(null)
        assertTrue(TapTool.say(phone, "tapped", Done.Lost(Resolution.Ambiguous(3))).contains("matches 3 things"))
        assertTrue(TapTool.say(phone, "tapped", Done.Lost(Resolution.Unusable)).contains("does not describe anything"))
        assertTrue(TapTool.say(phone, "tapped", Done.Lost(Resolution.Missing)).contains("not on it any more"))
    }

    @Test
    fun theOneThatIsAboutTheTapSaysSo() = runTest {
        val said = TapTool.say(Static(null), "tapped", Done.Refused("it is disabled"))

        assertTrue(said, said.contains("could not be tapped: it is disabled"))
    }

    @Test
    fun anUnreadableScreenSaysWhatReadScreenSays() = runTest {
        // One sentence for one condition, rather than two tools disagreeing
        // about how to describe the service being off.
        val said = TapTool(Tapping(null)).run(call())

        assertEquals(ReadScreenTool.unreadable(attached = true), said)
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
    fun typingSaysItTypedRatherThanTapped() = runTest {
        // #518. Every acting tool shares TapTool.say, and before the verb was a
        // parameter all five of them reported that they had tapped something.
        // The transcript is the model's account of what it did.
        val said = TypeTextTool(Tapping(Done.Did(null))).run(call())

        assertTrue(said, said.startsWith("typed."))
        assertTrue(said, !said.contains("tapped"))
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
    fun everyOutcomeIsWordedTheWayATapIs() = runTest {
        // One vocabulary for both. Two tools describing a moved screen
        // differently is two things for a model to learn about one event.
        val phone = Static(null)
        assertEquals(
            TapTool.say(phone, "tapped", Done.Lost(Resolution.Missing)),
            TapTool.say(phone, "tapped", Done.Lost(Resolution.Missing)),
        )
        assertTrue(TapTool.say(phone, "tapped", Done.Refused("that is a password field")).contains("password field"))
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
    fun pressingSaysItPressedRatherThanTapped() = runTest {
        // #518. A system button is not a node and was never tapped.
        val said = NavigateTool(Tapping(Done.Did(null))).run("""{"where":"back"}""")

        assertTrue(said, said.startsWith("pressed."))
        assertTrue(said, !said.contains("tapped"))
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

class ScrollToolTest {
    private val list = Handle("messages", "list", null, null, 0)

    private fun call(direction: String = "forward", handle: String = encode(list), screen: String = "k3f9.4") =
        """{"handle":"$handle","screen":"$screen","direction":"$direction"}"""

    @Test
    fun scrollingSaysItScrolledRatherThanTapped() = runTest {
        // #518. Completes the set: five acting tools, five verbs, one helper.
        val said = ScrollTool(Tapping(Done.Did(null))).run(call())

        assertTrue(said, said.startsWith("scrolled."))
        assertTrue(said, !said.contains("tapped"))
    }

    @Test
    fun bothDirectionsReachThePhone() = runTest {
        Onward.entries.forEach { onward ->
            val phone = Tapping(Done.Did(null))

            ScrollTool(phone).run(call(direction = onward.word))

            assertEquals(onward, phone.moved)
            assertEquals(list to Generation("k3f9", 4), phone.asked)
        }
    }

    @Test
    fun aDirectionThatIsNotOneListsTheOnesThatAre() = runTest {
        val phone = Tapping(Done.Did(null))

        val said = ScrollTool(phone).run(call(direction = "down"))

        assertTrue(said, said.contains("forward, back"))
        assertNull("nothing should have moved", phone.moved)
    }

    @Test
    fun thePurposeSaysForwardMeansOnwardRatherThanDown() {
        // A carousel scrolls sideways and a list scrolls down, and both are
        // the same action. "Down" would be right for most and wrong for some.
        val said = ScrollTool(Tapping(null)).purpose

        assertTrue(said, said.contains("onward through the content"))
        assertTrue(said, said.contains("carousel"))
    }

    @Test
    fun aBadHandleOrScreenIsRefusedBeforeAnythingMoves() = runTest {
        val phone = Tapping(Done.Did(null))

        assertTrue(ScrollTool(phone).run(call(handle = "the list")).startsWith("that is not a handle"))
        assertTrue(ScrollTool(phone).run(call(screen = "last")).startsWith("that is not a screen id"))
        assertNull(phone.moved)
    }

    @Test
    fun beingAtTheEndIsAnAnswerRatherThanAFailure() = runTest {
        // Told it failed, a model retries. Told the list is at its end, it
        // stops — which is the answer to "is there any more".
        val said = TapTool.say(Static(null), "tapped", Done.Refused("it is already at the end, so nothing moved"))

        assertTrue(said, said.contains("already at the end"))
    }
}

class OpenAppToolTest {
    private val gmail = Launchable("Gmail", "com.google.android.gm")
    private val maps = Launchable("Maps", "com.google.android.apps.maps")
    private val mapsGo = Launchable("Maps Go", "com.google.android.apps.mapslite")
    private val calendar = Launchable("Calendar", "com.android.calendar")
    private val workCalendar = Launchable("Calendar", "com.work.calendar")

    private fun phone(vararg apps: Launchable) =
        Tapping(Done.Did(null)).also { it.installed = apps.toList() }

    @Test
    fun openingSaysItOpenedRatherThanTapped() = runTest {
        // #518, and the run that found it: open_app answered "tapped." while
        // Clock genuinely came to the front.
        val said = OpenAppTool(phone(gmail)).run("""{"name":"Gmail"}""")

        assertTrue(said, said.startsWith("opened."))
        assertTrue(said, !said.contains("tapped"))
    }

    @Test
    fun anExactNameWinsOutright() {
        // Even though "Maps" is also a prefix of "Maps Go".
        assertEquals(listOf(maps), matching("Maps", listOf(mapsGo, maps)))
    }

    @Test
    fun caseAndSpacingAreNotWhatTellsAppsApart() {
        val whatsApp = Launchable("WhatsApp", "com.whatsapp")

        assertEquals(listOf(whatsApp), matching("whatsapp", listOf(whatsApp)))
        assertEquals(listOf(whatsApp), matching("Whats App", listOf(whatsApp)))
        assertEquals(listOf(whatsApp), matching("  WHATSAPP ", listOf(whatsApp)))
    }

    @Test
    fun aPrefixIsTriedBeforeASubstring() {
        // "mail" finding Gmail is a last resort rather than a first guess.
        val mailbox = Launchable("Mailbox", "com.mailbox")

        assertEquals(listOf(mailbox), matching("mail", listOf(gmail, mailbox)))
        assertEquals(listOf(gmail), matching("mail", listOf(gmail)))
    }

    @Test
    fun twoAppsWithOneNameAreNotChosenBetween() = runTest {
        // Ordinary on a phone with a work profile, and picking the first is
        // picking somebody's employer at random.
        val phone = phone(calendar, workCalendar)

        val said = OpenAppTool(phone).run("""{"name":"Calendar"}""")

        assertTrue(said, said.contains("more than one app matches"))
        assertNull("nothing should have opened", phone.opened)
    }

    @Test
    fun oneMatchIsOpenedByItsPackage() = runTest {
        val phone = phone(gmail, maps)

        OpenAppTool(phone).run("""{"name":"gmail"}""")

        assertEquals("com.google.android.gm", phone.opened)
    }

    @Test
    fun nothingMatchingDoesNotListThePhone() = runTest {
        // A hundred installed apps is not an error message.
        val phone = phone(gmail, maps, calendar)

        val said = OpenAppTool(phone).run("""{"name":"Ledger"}""")

        assertTrue(said, said.contains("no app called \"Ledger\""))
        assertTrue(said, !said.contains("Gmail"))
        assertNull(phone.opened)
    }

    @Test
    fun anEmptyNameIsNotEveryApp() = runTest {
        // A blank pattern matching everything is the call this tool exists to
        // not make, the same one Contacts.kt refuses.
        val phone = phone(gmail, maps)

        assertTrue(OpenAppTool(phone).run("""{"name":"  "}""").contains("no app was named"))
        assertEquals(emptyList<Launchable>(), matching("", listOf(gmail, maps)))
        assertNull(phone.opened)
    }

    @Test
    fun aListThatCannotBeReadIsNotAnEmptyPhone() = runTest {
        val phone = Tapping(Done.Did(null)).also { it.installed = null }

        assertTrue(OpenAppTool(phone).run("""{"name":"Gmail"}""").contains("could not be read"))
    }
}

private class Changing(private val readings: List<Reading?>, private val why: String? = null) : Phone {
    override suspend fun attached() = true
    var looks = 0

    override suspend fun barredNow() = why

    override suspend fun read(): Reading? =
        readings[minOf(looks++, readings.size - 1)]

    override suspend fun tap(at: Handle, from: Generation): Done? = null

    override suspend fun type(at: Handle, from: Generation, text: String): Done? = null

    override suspend fun navigate(way: Way): Done? = null

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? = null

    override suspend fun apps(): List<Launchable>? = null

    override suspend fun open(packageName: String): Done? = null
}

class WaitForChangeToolTest {
    private val before = Generation("k3f9", 4)
    private val row = Sighting(Handle("row", "text", "Rent is due", null, 0), "text", "Rent is due")

    private fun reading(counter: Long) = Reading(Generation("k3f9", counter), listOf(row))

    private fun tool(phone: Phone) = WaitForChangeTool(phone) { }

    @Test
    fun itStopsAtTheFirstChange() = runTest {
        val phone = Changing(listOf(reading(4), reading(4), reading(5), reading(6)))

        val said = tool(phone).run("""{"screen":"k3f9.4"}""")

        assertEquals(3, phone.looks)
        assertTrue(said, said.startsWith("the screen changed."))
        assertTrue(said, said.contains("screen k3f9.5"))
    }

    @Test
    fun aScreenAlreadyDifferentIsAnswerdOnTheFirstLook() = runTest {
        val phone = Changing(listOf(reading(9)))

        tool(phone).run("""{"screen":"k3f9.4"}""")

        assertEquals(1, phone.looks)
    }

    @Test
    fun notChangingIsAnAnswerRatherThanAnError() = runTest {
        // What somebody asks when they want to know whether a tap did
        // anything. Reported as a failure, a model retries the tap.
        val phone = Changing(listOf(reading(4)))

        val said = tool(phone).run("""{"screen":"k3f9.4","seconds":1}""")

        assertTrue(said, said.contains("has not changed in 1 seconds"))
        assertTrue(said, said.contains("screen k3f9.4"))
    }

    @Test
    fun theWaitIsBoundedByWhatItWasAsked() = runTest {
        val phone = Changing(listOf(reading(4)))

        tool(phone).run("""{"screen":"k3f9.4","seconds":2}""")

        assertEquals(2 * 1000 / WaitForChangeTool.INTERVAL, phone.looks)
    }

    @Test
    fun aWaitLongerThanSomebodyWouldSitThroughIsRefused() = runTest {
        // A turn is a person waiting, and a tool that can block one for a
        // minute will.
        val phone = Changing(listOf(reading(4)))

        val said = tool(phone).run("""{"screen":"k3f9.4","seconds":60}""")

        assertTrue(said, said.contains("between 1 and ${WaitForChangeTool.LONGEST}"))
        assertEquals(0, phone.looks)
    }

    @Test
    fun aScreenIdThisBuildDidNotWriteIsRefusedBeforeWaiting() = runTest {
        val phone = Changing(listOf(reading(4)))

        assertTrue(tool(phone).run("""{"screen":"last"}""").startsWith("that is not a screen id"))
        assertEquals(0, phone.looks)
    }

    @Test
    fun aScreenThatStopsBeingReadableSaysSo() = runTest {
        // Rather than waiting out the whole timeout against nothing.
        val phone = Changing(listOf(reading(4), null))

        val said = tool(phone).run("""{"screen":"k3f9.4"}""")

        assertEquals(ReadScreenTool.unreadable(attached = true), said)
        assertEquals(2, phone.looks)
    }
}

class FindOnScreenToolTest {
    private val generation = Generation("k3f9", 4)

    private fun line(label: String, at: Int) = Sighting(
        handle = Handle("row", "text", label, null, at),
        role = "text",
        label = label,
    )

    private fun screen(vararg labels: String) = Changing(
        listOf(Reading(generation, labels.mapIndexed { at, it -> line(it, at) })),
    )

    @Test
    fun itSearchesPastWhatReadScreenPrints() = runTest {
        // The point of the tool. The handles beyond the limit exist; they are
        // only not on the page.
        val many = List(ReadScreenTool.LIMIT + 20) { "row $it" } + "Notifications"

        val said = FindOnScreenTool(screen(*many.toTypedArray())).run("""{"text":"Notifications"}""")

        assertTrue(said, said.contains("Notifications"))
        assertEquals(2, said.lines().size)
    }

    @Test
    fun itMatchesWhatTheScreenSaysRatherThanTheHandle() = runTest {
        val said = FindOnScreenTool(screen("Wi-Fi & networks", "Bluetooth"))
            .run("""{"text":"wifi"}""")

        // "wifi" does not appear in "Wi-Fi & networks", so this is the honest
        // answer rather than a fuzzy match nobody asked for.
        assertTrue(said, said.contains("nothing on this screen says"))
    }

    @Test
    fun caseIsNotWhatTellsALineApart() = runTest {
        val said = FindOnScreenTool(screen("Bluetooth", "Display")).run("""{"text":"bluetooth"}""")

        assertTrue(said, said.contains("h:row|text|Bluetooth||0"))
    }

    @Test
    fun theAnswerCarriesTheScreenIdSoTheHandlesCanBeUsed() = runTest {
        val said = FindOnScreenTool(screen("Bluetooth")).run("""{"text":"blue"}""")

        assertTrue(said, said.startsWith("screen k3f9.4"))
    }

    @Test
    fun nothingMatchingIsNotAnUnreadableScreen() = runTest {
        val said = FindOnScreenTool(screen("Bluetooth", "Display")).run("""{"text":"Ledger"}""")

        assertTrue(said, said.contains("nothing on this screen says \"Ledger\""))
        assertTrue(said, said.contains("2 things on it"))
        assertTrue(said, !said.contains("Accessibility"))
    }

    @Test
    fun aBlankSearchIsNotEveryLine() = runTest {
        // read_screen with extra steps, and on a long screen read_screen
        // without the limit.
        val said = FindOnScreenTool(screen("Bluetooth")).run("""{"text":"  "}""")

        assertTrue(said, said.contains("no words were given"))
    }

    @Test
    fun anUnreadableScreenBorrowsTheSameSentence() = runTest {
        val said = FindOnScreenTool(Changing(listOf(null))).run("""{"text":"Bluetooth"}""")

        assertEquals(ReadScreenTool.unreadable(attached = true), said)
    }
}

class BarredScreenTest {
    private val generation = Generation("k3f9", 4)
    private val row = Sighting(Handle("row", "text", "Allow", null, 0), "text", "Allow", isClickable = true)
    private val why = Barred.PERMISSIONS.why

    @Test
    fun readingIsRefusedBeforeItHappens() = runTest {
        // The tempting exception. read_screen on the permissions page tells
        // the model exactly which button says Allow, and the refusal it would
        // then get from tap is one it can plan around.
        val said = ReadScreenTool(Static(Reading(generation, listOf(row)), why)).run("{}")

        assertEquals(why, said)
        assertTrue(said, !said.contains("Allow"))
    }

    @Test
    fun searchingIsRefusedTheSameWay() = runTest {
        // Otherwise find_on_screen is read_screen with a filter, and the one
        // that was barred is the one nobody would think to bar.
        val said = FindOnScreenTool(Changing(listOf(Reading(generation, listOf(row))), why))
            .run("""{"text":"Allow"}""")

        assertEquals(why, said)
    }

    @Test
    fun aRefusalIsNotTheSameAsAScreenThatCannotBeRead() = runTest {
        // Different problems with different fixes: one is the service being
        // off, the other is the agent declining to look.
        val barred = ReadScreenTool(Static(null, why)).run("{}")

        assertEquals(why, barred)
        assertTrue(barred, !barred.contains("Settings > Accessibility"))
    }

    @Test
    fun everyBarredScreenSaysWhoCanActInstead() {
        // A refusal a model can only apologise for teaches it to stop asking.
        assertTrue(Barred.PERMISSIONS.why, Barred.PERMISSIONS.why.contains("Ask the person"))
        assertTrue(Barred.CONTROLS.why, Barred.CONTROLS.why.contains("Ask the person"))
        assertTrue(Barred.LOCKED.why, Barred.LOCKED.why.contains("unlock"))
    }
}
