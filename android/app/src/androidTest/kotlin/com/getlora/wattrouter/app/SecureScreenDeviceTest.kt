// SecureScreenDeviceTest.kt — what a secure window actually exposes.
//
// History
//   2026-08-09  A. Sigdel  Created, to measure a claim two decision records
//                          made and neither had tested.
//
// Both records said FLAG_SECURE windows expose nothing through accessibility,
// and one called that a feature. This asked the platform, and the answer was
// that the window reads normally: FLAG_SECURE stops screen *capture* and leaves
// the node tree alone, because a screen reader has to work in a banking app.
//
// So this is not a regression test for a bug. It is the measurement, kept, and
// it is the only thing that would notice if a future Android made the claim
// true — at which point the records go back to what they used to say and the
// tools need the third answer #472 argued they did not.
//
// Reading the accessibility service directly rather than through a tool: the
// question is what the framework hands over, and a tool would add a rendering
// step between the answer and the assertion.

package com.getlora.wattrouter.app

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecureScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name get() = "${context.packageName}/${DrivingService::class.java.name}"

    /** As DrivingServiceDeviceTest, and for the reason its header gives. */
    private val automation
        get() = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).use { it.readBytes() }
    }

    private fun waitForConnection(): DrivingService? {
        repeat(WAITS) {
            DrivingService.connected?.let { return it }
            Thread.sleep(PAUSE)
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
        shell("settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} ''")
        shell("settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 0")
    }

    @Test
    fun aSecureWindowIsReadAndNotBlank() {
        val service = waitForConnection()
        assertNotNull("the service was enabled and never connected", service)

        // Declared in the test APK's manifest, so it lives under that package
        // rather than the app's, and has to be exported to be started from it.
        context.startActivity(
            Intent()
                .setClassName(
                    instrumentation.context.packageName,
                    SecureActivity::class.java.name,
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        // Polled rather than slept for. A fixed pause passed this test alone
        // and failed it inside the suite, where the device arrives in whatever
        // state the previous test left — and `read` answers null while no
        // window is focused, which is indistinguishable here from the claim
        // being true. Waiting for the window is what makes the assertion mean
        // what it says.
        var seen = 0
        var found = 0
        repeat(WAITS) {
            val reading = service!!.read()
            if (reading != null) {
                seen = reading.seen.size
                found = reading.seen.count {
                    it.label?.contains(SecureActivity.SECRET) == true ||
                        it.handle.text?.contains(SecureActivity.SECRET) == true
                }
                if (found >= 1) return@repeat
            }
            Thread.sleep(PAUSE)
        }

        assertTrue(
            "a secure window exposed no label in ${WAITS * PAUSE}ms — the records " +
                "may have become true, and #472 says what follows if so. " +
                "Last read saw $seen node(s)",
            found >= 1,
        )
    }

    private companion object {
        const val WAITS = 40
        const val PAUSE = 250L
    }
}
