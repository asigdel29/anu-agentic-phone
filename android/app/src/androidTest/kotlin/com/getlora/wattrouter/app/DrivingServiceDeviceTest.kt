// DrivingServiceDeviceTest.kt: the service binds, connects, and reads.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  Taps as well, which is the only path that retains
//                          framework nodes and has to give them back.
//   2026-08-09  A. Sigdel  Checks the service asked for the summon button.
//   2026-08-12  A. Sigdel  Waits for a screen rather than reading whatever is
//                          there and dereferencing it, #680.
//
// Two settings here fail silently and neither logs anything useful: a service
// without BIND_ACCESSIBILITY_SERVICE is never bound, and one whose config omits
// canRetrieveWindowContent binds, appears enabled, and answers null from
// rootInActiveWindow for the life of the install.
//
// So this enables the real service rather than asserting that the XML says the
// right words. The shell can write secure settings and the test cannot, which is
// what executeShellCommand is for; the instrumented process is the app's own, so
// the connected instance is the same object a tool would reach.
//
// FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES is the whole reason this file works.
// UiAutomation is itself an accessibility service, and by default connecting one
// turns every other off for the duration, so a test that enables a service and
// waits for it waits forever, and the failure reads exactly like a manifest that
// is wrong. Everything about the setting says it worked: `settings get` reads it
// back, dumpsys lists it under Enabled services. It is simply not bound.

package com.getlora.wattrouter.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.getlora.wattrouter.Done
import com.getlora.wattrouter.Generation
import com.getlora.wattrouter.Handle
import com.getlora.wattrouter.Reading
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DrivingServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name get() = "${context.packageName}/${DrivingService::class.java.name}"

    /** See the header: the default connection would switch the service off. */
    private val automation
        get() = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )

    /**
     * Run a shell command and wait for it.
     *
     * Reading to the end is what waits: executeShellCommand hands back a pipe
     * and closing it without draining kills the command, so a `settings put`
     * written this way is applied only sometimes. That cost an afternoon, and
     * it looks exactly like an accessibility service refusing to bind.
     */
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).use { it.readBytes() }
    }

    /** Wait for the system to bind it. Enabling is a settings write, not a call. */
    private fun waitForConnection(): DrivingService? {
        repeat(WAITS) {
            DrivingService.connected?.let { return it }
            Thread.sleep(PAUSE)
        }
        return null
    }

    /**
     * Wait for a screen with something on it. Null if none arrived.
     *
     * `read` answers null while no window has focus, and every test below runs
     * against whatever the previous one left rather than a fixture of its own.
     * Read once, that null is a `NullPointerException` at a line number, which
     * is #680: it says neither that the window was late nor that the service is
     * not reading, and those are the two things this class is here to tell
     * apart.
     *
     * The numbers are `waitForConnection`'s, and the two screen tripwires use
     * the same pair.
     */
    private fun readWhenReady(service: DrivingService): Reading? {
        repeat(WAITS) {
            val reading = service.read()
            if (reading != null && reading.seen.isNotEmpty()) return reading
            Thread.sleep(PAUSE)
        }
        return null
    }

    /** As [readWhenReady], and the sentence every caller that needs one shares. */
    private fun readOrFail(service: DrivingService): Reading {
        val reading = readWhenReady(service)
        assertNotNull(
            "no screen with anything on it arrived in ${WAITS * PAUSE}ms, so nothing " +
                "here was measured",
            reading,
        )
        return reading!!
    }

    @Before
    fun allow() {
        shell("settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} $name")
        shell("settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1")
    }

    @After
    fun revoke() {
        // Left on, it drives every later test in the suite through a service
        // reading their screens.
        shell("settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} ''")
        shell("settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 0")
    }

    @Test
    fun theSystemBindsItAndItConnects() {
        // Fails if BIND_ACCESSIBILITY_SERVICE is missing from the manifest, or
        // the intent filter is, or the meta-data points at nothing.
        assertNotNull("the service was enabled and never connected", waitForConnection())
    }

    @Test
    fun aConnectedServiceReadsTheScreen() {
        // Fails if canRetrieveWindowContent is absent: rootInActiveWindow is
        // null forever and nothing else says so.
        val service = waitForConnection()
        assertNotNull(service)

        // Its own wait rather than readWhenReady's. That one waits for a screen
        // with something on it, and the two states this test tells apart are
        // "read nothing at all", which is the missing capability, and "read a
        // screen with nothing on it", which is not. A helper that waited
        // through the second would make it unreportable.
        var reading: Reading? = null
        for (attempt in 0 until WAITS) {
            reading = service!!.read()
            if (reading != null) break
            Thread.sleep(PAUSE)
        }

        assertNotNull("connected and read nothing in ${WAITS * PAUSE}ms", reading)
        assertTrue("a screen with nothing on it", reading!!.seen.isNotEmpty())
        assertTrue("${reading.generation}", reading.generation.counter >= 1)
    }

    @Test
    fun aHandleFromThatReadingAimsAtSomething() {
        // The whole path, over a screen nobody wrote: framework tree, copy,
        // prune, generation, resolve.
        val service = waitForConnection()
        assertNotNull(service)
        val reading = readOrFail(service!!)

        val handle = reading.seen.first { it.handle.isFindable }.handle
        val aim = service.aim(handle, reading.generation)

        assertNotNull(aim)
        // Moved is a legitimate answer (a live screen may have changed between
        // the two reads) so the assertion is that it is not Lost, which would
        // mean the handle did not describe the node it was made from.
        assertTrue("$aim", aim !is com.getlora.wattrouter.Aim.Lost)
    }

    @Test
    fun tappingSomethingThatTakesNoTapIsRefusedRatherThanEscalated() {
        // The whole acting path over a real tree: retain, resolve, decline,
        // release. Deliberately a line read_screen does not mark as tappable,
        // so nothing on the device is actually pressed by the suite.
        val service = waitForConnection()
        assertNotNull(service)
        val reading = readOrFail(service!!)
        val quiet = reading.seen.firstOrNull { !it.isClickable && !it.isEditable && it.handle.isFindable }

        assumeTrue("no read-only line on this screen to try", quiet != null)
        val done = service.tap(quiet!!.handle, reading.generation)

        // Moved is legitimate on a live screen; what must not happen is Did,
        // which would mean something was pressed that nothing offered.
        assertTrue("$done", done is Done.Refused || done is Done.Moved)
    }

    @Test
    fun aTapAgainstAnOlderReadingIsRefused() {
        val service = waitForConnection()
        assertNotNull(service)
        val reading = readOrFail(service!!)

        val done = service.tap(
            reading.seen.first { it.handle.isFindable }.handle,
            Generation("some-other-life", reading.generation.counter),
        )

        assertTrue("$done", done is Done.Moved)
    }

    @Test
    fun theServiceAsksForTheSummonButton() {
        // What can be checked from here: that the flag in driving.xml survived
        // into the service the system bound. Whether pressing the button opens
        // the app needs a finger on a navigation bar, and that belongs with
        // the run on a phone.
        val service = waitForConnection()
        assertNotNull(service)

        val flags = service!!.serviceInfo.flags
        assertTrue(
            "the service did not request the accessibility button: $flags",
            flags and AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON != 0,
        )
    }

    @Test
    fun andTheControllerIsThereToAnswerIt() {
        // Available is about the phone rather than the app: a device with no
        // navigation bar and no floating button would answer false, so this
        // asserts the controller exists rather than what it says.
        val service = waitForConnection()
        assertNotNull(service)

        assertNotNull(service!!.accessibilityButtonController)
    }

    @Test
    fun aHandleFromNowhereIsRefusedRatherThanGuessedAt() {
        val service = waitForConnection()
        assertNotNull(service)
        val reading = readOrFail(service!!)

        val aim = service.aim(Handle(role = "button", siblingIndex = 99), reading.generation)

        assertEquals(
            com.getlora.wattrouter.Aim.Lost(com.getlora.wattrouter.Resolution.Unusable),
            aim,
        )
    }

    private companion object {
        /** Ten seconds, as SecureScreenDeviceTest and SensitiveScreenDeviceTest. */
        const val WAITS = 40
        const val PAUSE = 250L
    }
}
