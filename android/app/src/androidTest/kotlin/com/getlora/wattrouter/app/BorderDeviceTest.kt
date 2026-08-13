// BorderDeviceTest.kt: the frame that says which screen is being driven.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// On a device because the question is whether the compositor took the window,
// which no JVM test can ask. ChatHeadDeviceTest establishes the shape and the
// reason: an overlay this size carries no text the node tree would match on, so
// the window manager's own account is what there is to read.
//
// It counts overlays rather than matching one. The bubble is wrap-by-wrap and
// the banner is match-by-wrap, so a size match would be reading a dumpsys
// format that is not a contract; the number of accessibility overlays the
// service has up is a fact about the service.
//
// The case to read first is the last. A frame left on the display after a turn
// is worse than no frame at all: it says the agent is driving a phone nobody is
// driving, and nothing else in the application would report it.

package com.getlora.wattrouter.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BorderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name get() = "${context.packageName}/${DrivingService::class.java.name}"

    private val automation
        get() = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).use { it.readBytes().decodeToString() }

    private fun waitForConnection(): DrivingService? {
        repeat(TRIES) {
            DrivingService.connected?.let { return it }
            Thread.sleep(WAIT)
        }
        return null
    }

    /** How many accessibility overlays are on the display right now. */
    private fun overlays(): Int =
        shell("dumpsys window windows").lineSequence().count { it.contains(OVERLAY) }

    /** Polled, because a window arrives a frame or two after it is added. */
    private fun overlaysSettleAt(want: Int): Boolean {
        repeat(TRIES) {
            if (overlays() == want) return true
            Thread.sleep(WAIT)
        }
        return false
    }

    @Before
    fun allow() {
        shell("settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} $name")
        shell("settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1")
    }

    @After
    fun revoke() {
        DrivingService.connected?.let { service ->
            instrumentation.runOnMainSync { service.showing(null) }
        }
        shell("settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} ''")
        shell("settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 0")
    }

    @Test
    fun aTurnThatDrivesPutsTwoThingsUp() {
        // The banner and the frame. Before a turn there is the bubble alone;
        // during one the bubble goes away and these two arrive, so the count is
        // the same either side and this asserts what is up rather than how many.
        val service = waitForConnection()
        assertNotNull("the service never connected", service)
        assertTrue("the bubble never reached the display", overlaysSettleAt(1))

        instrumentation.runOnMainSync { service?.showing("finding the timer") }

        assertTrue(
            "a driving turn should have a banner and a frame up, saw ${overlays()}",
            overlaysSettleAt(2),
        )
    }

    @Test
    fun theFrameGoesUpOnlyOnce() {
        // showing() is called on every step of a turn, not once at the start.
        // A frame added per call would be twenty windows deep by the end of one.
        val service = waitForConnection()
        assertNotNull("the service never connected", service)

        instrumentation.runOnMainSync {
            service?.showing("opening the clock")
            service?.showing("reading the screen")
            service?.showing("tapping start")
        }

        assertTrue("three steps should leave two windows, saw ${overlays()}", overlaysSettleAt(2))
    }

    @Test
    fun theEndOfATurnTakesItAway() {
        // The case that matters most. A frame left up says the agent is driving
        // a phone nobody is driving, and nothing else here would report it.
        val service = waitForConnection()
        assertNotNull("the service never connected", service)

        instrumentation.runOnMainSync { service?.showing("finding the timer") }
        assertTrue(overlaysSettleAt(2))

        instrumentation.runOnMainSync { service?.showing(null) }

        // One again: the bubble, which comes back when the task is over.
        assertTrue("the frame outlived its turn, saw ${overlays()}", overlaysSettleAt(1))
    }

    @Test
    fun theFrameTakesNoTouches() {
        // Belt and braces with FLAG_NOT_TOUCHABLE. An overlay covering the
        // display that swallowed a tap would make every action the agent takes
        // fail as though the app refused it, which Banner.kt records as the
        // hardest kind of failure to attribute.
        assertEquals(false, Border(context).view.isClickable)
        assertEquals(false, Border(context).view.isFocusable)
    }

    private companion object {
        const val OVERLAY = "ty=ACCESSIBILITY_OVERLAY"
        const val WAIT = 250L
        const val TRIES = 24
    }
}
