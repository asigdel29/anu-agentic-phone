// ChatHeadDeviceTest.kt: the bubble reaches the display, and leaves when told.
//
// History
//   2026-08-09  A. Sigdel  Created with #522.
//
// The claim no JVM test can make. DrivingService.attachHead swallows a failure
// into `head == null`, exactly as attach does for the banner, so a window type
// the platform rejects is indistinguishable from success everywhere except here.
// The code review filed that gap against the banner and nothing closed it; this
// closes it for the bubble.
//
// Asserted through the window manager's own dump rather than through the
// service's field or through the accessibility tree, and the second exclusion is
// the interesting one. An accessibility overlay is deliberately absent from the
// node tree: `uiautomator dump` does not contain the bubble and neither does
// UiAutomation.windows, while `dumpsys window windows` reports it as
// ty=ACCESSIBILITY_OVERLAY owned by this package.
//
// That is worth having found. It means read_screen cannot see the agent's own
// bubble, which #522 raised as an open question and assumed the wrong way round:
// the overlay cannot pollute a reading even if it is up while one is taken. The
// panel in a later change will be focusable and *will* be in the tree, so the
// rule about collapsing before a turn still holds; it just does not apply to
// the bubble.

package com.getlora.wattrouter.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatHeadDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name get() = "${context.packageName}/${DrivingService::class.java.name}"

    /** As DrivingServiceDeviceTest: the default connection switches it off. */
    private val automation
        get() = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )

    /**
     * Run a shell command and answer what it printed.
     *
     * Reading to the end is also what waits: executeShellCommand hands back a
     * pipe and closing it without draining kills the command, so a `settings
     * put` written otherwise is applied only sometimes.
     */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).use { it.readBytes().decodeToString() }

    private fun waitForConnection(): DrivingService? {
        repeat(40) {
            DrivingService.connected?.let { return it }
            Thread.sleep(WAIT)
        }
        return null
    }

    /**
     * Whether the bubble is on the display, by its description.
     *
     * Polled rather than read once. Adding a window is a request to another
     * process and the answer arrives when it arrives; a single look is a test
     * that passes on a fast machine and fails on a loaded one.
     *
     * @param want what to wait for. Waiting for absence needs the same patience
     *   as waiting for presence, and a test that only polls one way reports the
     *   slow case as the wrong case.
     */
    private fun bubbleShown(want: Boolean): Boolean {
        repeat(TRIES) {
            // The window manager's own account. An overlay this size carries no
            // text the tree would match on anyway, and the question is whether
            // the compositor took the window rather than what is drawn in it.
            val here = shell("dumpsys window windows")
                .lineSequence()
                .any { it.contains(OVERLAY) && it.contains(BOUNDS) }
            if (here == want) return true
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
        shell("settings delete secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}")
        shell("settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 0")
        // Waited for, not assumed. Disabling is a settings write and the
        // unbind follows it whenever the system gets to it; the next test's
        // @Before would otherwise enable a service that is still going away,
        // and see the outgoing one never come back. That failure reads as "the
        // service never connected", which is true and says nothing useful.
        repeat(TRIES) {
            if (DrivingService.connected == null) return
            Thread.sleep(WAIT)
        }
    }

    @Test
    fun connectingPutsTheBubbleOnTheDisplay() {
        // TYPE_ACCESSIBILITY_OVERLAY needs no permission, which is the finding
        // #446 paid for and the reason this feature is cheap. If that ever
        // stops being true, this is the test that says so rather than a phone
        // with no bubble on it and nothing in the log.
        assertNotNull("the service never connected", waitForConnection())

        assertTrue("the bubble never reached the display", bubbleShown(want = true))
    }

    @Test
    fun aTurnThatDrivesTakesTheBubbleAway() {
        // The rule from the design review, recorded on the summon callback: an
        // expanded surface is the foreground app, so the bubble is for before
        // and after a task rather than during one. The banner is the surface
        // for during, and it carries the stop.
        val service = waitForConnection()
        assertNotNull("the service never connected", service)
        assertTrue("the bubble never reached the display", bubbleShown(want = true))

        instrumentation.runOnMainSync { service?.showing("finding the timer") }

        assertTrue("the bubble stayed up while a turn was driving", bubbleShown(want = false))
    }

    @Test
    fun theEndOfATurnBringsItBack() {
        // The half that is easy to forget. A bubble that goes away and does not
        // return is one somebody stops relying on, and nothing else in the app
        // would report its absence.
        val service = waitForConnection()
        assertNotNull("the service never connected", service)

        instrumentation.runOnMainSync { service?.showing("finding the timer") }
        assertTrue("the bubble stayed up while a turn was driving", bubbleShown(want = false))

        instrumentation.runOnMainSync { service?.showing(null) }

        assertTrue(
            "the bubble did not come back when the turn ended",
            bubbleShown(want = true),
        )
    }

    private companion object {
        /** How the window manager spells this window type in its dump. */
        const val OVERLAY = "ty=ACCESSIBILITY_OVERLAY"

        /**
         * What tells the bubble from the banner in that dump.
         *
         * The banner is MATCH_PARENT across the top; the bubble wraps its
         * content. Matching on the type alone would call the banner a bubble
         * and pass the test that says they are never up together.
         */
        const val BOUNDS = "wrapxwrap"

        const val WAIT = 250L
        const val TRIES = 24
    }
}
