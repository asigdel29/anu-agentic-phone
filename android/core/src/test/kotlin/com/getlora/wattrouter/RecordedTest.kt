// RecordedTest.kt: which calls count as doing something.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// On the JVM against a Phone answering a scripted Done and a scripted picture.
// The capture itself is three framework calls and #629 asked those on a device;
// what is decided here is which calls are worth a step at all.
//
// The case to read first is what did not happen. Budgeted and Confirmed both
// answer Refused without touching the phone, and a replay recording one would
// show a step that never happened over a screen nothing changed.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedTest {
    private val handle = Handle(viewId = "send", role = "button", siblingIndex = 0)
    private val generation = Generation("k3f9", 1)
    private val shot = Image("data:image/png;base64,aGk=")

    private class Acting(
        private val done: Done? = Done.Did(null),
        private val screen: Image? = Image("data:image/png;base64,aGk="),
    ) : Phone {
        var captured = 0
        override suspend fun barredNow(): String? = null
        override suspend fun attached(): Boolean = true
        override suspend fun read(): Reading? = null
        override suspend fun capture(): Image? {
            captured++
            return screen
        }
        override suspend fun apps(): List<Launchable>? = null
        override suspend fun tap(at: Handle, from: Generation): Done? = done
        override suspend fun type(at: Handle, from: Generation, text: String): Done? = done
        override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? = done
        override suspend fun navigate(way: Way): Done? = done
        override suspend fun open(packageName: String): Done? = done
    }

    @Test
    fun anActionIsKeptWithTheScreenItLeftBehind() = runTest {
        val replay = Replay()

        Recorded(Acting(), replay).tap(handle, generation)

        assertEquals("tapped send", replay.steps.single().did)
        assertEquals(shot, replay.steps.single().screen)
    }

    @Test
    fun whatDidNotHappenIsNotAStep() = runTest {
        // Refused is the phone saying it would not; null is it saying it could
        // not. Neither is something to show somebody a picture of.
        listOf(Done.Refused("this turn has done as much as it may"), null).forEach { answer ->
            val phone = Acting(done = answer)
            val replay = Replay()

            Recorded(phone, replay).tap(handle, generation)

            assertTrue("$answer", replay.steps.isEmpty())
            assertEquals("nothing to capture either", 0, phone.captured)
        }
    }

    @Test
    fun readingIsNeverAStep() = runTest {
        // A replay of what a turn did should not fill with the times it looked,
        // which is the line Budget.kt and Autonomy.kt both draw already.
        val phone = Acting()
        val recorded = Recorded(phone, Replay())

        recorded.read()
        recorded.attached()
        recorded.barredNow()
        recorded.apps()
        recorded.capture()

        assertEquals("only the caller's own capture", 1, phone.captured)
    }

    @Test
    fun everyActingCallIsRecorded() = runTest {
        // Why this is at the seam: a tenth tool is recorded without its author
        // knowing this exists.
        val replay = Replay()
        val recorded = Recorded(Acting(), replay)

        recorded.tap(handle, generation)
        recorded.type(handle, generation, "hello")
        recorded.scroll(handle, generation, Onward.FORWARD)
        recorded.navigate(Way.BACK)
        recorded.open("com.example.app")

        assertEquals(
            listOf(
                "tapped send",
                "typed into send",
                "scrolled forward in send",
                "pressed back",
                "opened com.example.app",
            ),
            replay.steps.map { it.did },
        )
    }

    @Test
    fun whatWasTypedIsNotInTheStep() = runTest {
        // Its reason for being absent from a confirmation prompt: it can be a
        // paragraph, and it can be a password somebody pasted.
        val replay = Replay()

        Recorded(Acting(), replay).type(handle, generation, "hunter2")

        assertTrue(replay.steps.single().did, !replay.steps.single().did.contains("hunter2"))
    }

    @Test
    fun theWordsAreTheOnesSomebodyWasAskedWith() = runTest {
        // Confirmed asks "tap send" and this records "tapped send", from the
        // same `asked` helper. A person who approved one and later reads the
        // other is reading one thing twice.
        val replay = Replay()

        Recorded(Acting(), replay).tap(handle, generation)

        assertTrue(replay.steps.single().did, replay.steps.single().did.endsWith(asked(handle)))
    }
}
