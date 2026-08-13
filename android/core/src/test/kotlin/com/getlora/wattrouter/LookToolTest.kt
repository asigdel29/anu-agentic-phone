// LookToolTest.kt: the tool #439 was opened for.
//
// History
//   2026-08-11  A. Sigdel  Created with #439.
//
// On the JVM against a Phone that answers a scripted picture, which is the
// split the seam exists for: the capture itself is three framework calls and
// SensitiveScreenDeviceTest asks about those on a device.
//
// The case to read first is the barred one. A picture of the permissions page
// is a picture of exactly the button this application must not press, and
// unlike a reading it carries every pixel of it whether or not the tree had a
// node to describe it.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookToolTest {
    private val shot = Image("data:image/png;base64,aGVsbG8=")

    private class Showing(
        private val image: Image? = null,
        private val why: String? = null,
        private val bound: Boolean = true,
    ) : Phone {
        var captured = 0
        override suspend fun barredNow(): String? = why
        override suspend fun attached(): Boolean = bound
        override suspend fun read(): Reading? = null
        override suspend fun capture(): Image? {
            captured++
            return image
        }
        override suspend fun apps(): List<Launchable>? = null
        override suspend fun tap(at: Handle, from: Generation): Done? = null
        override suspend fun type(at: Handle, from: Generation, text: String): Done? = null
        override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? = null
        override suspend fun navigate(way: Way): Done? = null
        override suspend fun open(packageName: String): Done? = null
    }

    @Test
    fun aPictureComesBackAsSomethingToLookAt() = runTest {
        val answered = LookTool(Showing(shot)).answer("{}")

        assertEquals(listOf(shot), answered.images)
        assertTrue(answered.text, answered.text.contains("follows this message"))
    }

    @Test
    fun theTextDoesNotDescribeThePicture() = runTest {
        // The tool message cannot carry the image, so it says one follows. A
        // tool result claiming to *be* a screenshot, in a message that provably
        // is not one, is the kind of thing a model reconciles by inventing what
        // it saw.
        val answered = LookTool(Showing(shot)).answer("{}")

        assertTrue(answered.text, answered.text.length < 80)
    }

    @Test
    fun aBarredScreenIsRefusedBeforeAnythingIsCaptured() = runTest {
        // The strongest case for barring, and stronger than read_screen's: a
        // picture carries every pixel of a page whether or not the tree had a
        // node for it.
        val phone = Showing(shot, why = "that screen is the agent's own")

        val answered = LookTool(phone).answer("{}")

        assertEquals(0, phone.captured)
        assertEquals("that screen is the agent's own", answered.text)
        assertTrue(answered.images.isEmpty())
    }

    @Test
    fun aServiceThatIsOffSaysSoInReadScreensWords() = runTest {
        // One set of sentences for two tools. A second set is a second thing to
        // keep true of a phone whose switch somebody turned off.
        val answered = LookTool(Showing(image = null, bound = false)).answer("{}")

        assertEquals(ReadScreenTool.unreadable(attached = false), answered.text)
        assertTrue(answered.images.isEmpty())
    }

    @Test
    fun aWindowThatHasNotArrivedIsToldApartFromThat() = runTest {
        val answered = LookTool(Showing(image = null, bound = true)).answer("{}")

        assertEquals(ReadScreenTool.unreadable(attached = true), answered.text)
    }

    @Test
    fun itsRunIsStillTheProseHalf() = runTest {
        // Both members stay true of it, which is what keeps a caller wanting
        // text from having to know which kind of tool it holds.
        assertTrue(LookTool(Showing(shot)).run("{}").contains("follows"))
    }

    @Test
    fun theModelIsToldToPreferReadingTheScreen() = runTest {
        // A picture has no handles in it, so a model reaching for look when it
        // wanted to act would take a photograph and then have nothing to tap.
        val purpose = LookTool(Showing(shot)).purpose

        assertTrue(purpose, purpose.contains("read_screen"))
        assertTrue(purpose, purpose.contains("no handles"))
    }

    @Test
    fun aToolBoxCarriesItThrough() = runTest {
        val result = ToolBox(listOf(LookTool(Showing(shot)))).run(ToolCall("c1", "look", "{}"))

        assertEquals(listOf(shot), result.images)
    }
}
