// DrivingServiceDeviceTest.kt — the service binds, connects, and reads.
//
// History
//   2026-08-09  A. Sigdel  Created.
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
// turns every other off for the duration — so a test that enables a service and
// waits for it waits forever, and the failure reads exactly like a manifest that
// is wrong. Everything about the setting says it worked: `settings get` reads it
// back, dumpsys lists it under Enabled services. It is simply not bound.

package com.getlora.wattrouter.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.getlora.wattrouter.Handle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        repeat(40) {
            DrivingService.connected?.let { return it }
            Thread.sleep(250)
        }
        return null
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

        val reading = service!!.read()
        assertNotNull("connected and read nothing", reading)
        assertTrue("a screen with nothing on it", reading!!.seen.isNotEmpty())
        assertTrue("${reading.generation}", reading.generation.counter >= 1)
    }

    @Test
    fun aHandleFromThatReadingAimsAtSomething() {
        // The whole path, over a screen nobody wrote: framework tree, copy,
        // prune, generation, resolve.
        val service = waitForConnection()
        assertNotNull(service)
        val reading = service!!.read()!!

        val handle = reading.seen.first { it.handle.isFindable }.handle
        val aim = service.aim(handle, reading.generation)

        assertNotNull(aim)
        // Moved is a legitimate answer — a live screen may have changed between
        // the two reads — so the assertion is that it is not Lost, which would
        // mean the handle did not describe the node it was made from.
        assertTrue("$aim", aim !is com.getlora.wattrouter.Aim.Lost)
    }

    @Test
    fun aHandleFromNowhereIsRefusedRatherThanGuessedAt() {
        val service = waitForConnection()
        assertNotNull(service)
        val reading = service!!.read()!!

        val aim = service.aim(Handle(role = "button", siblingIndex = 99), reading.generation)

        assertEquals(
            com.getlora.wattrouter.Aim.Lost(com.getlora.wattrouter.Resolution.Unusable),
            aim,
        )
    }
}
