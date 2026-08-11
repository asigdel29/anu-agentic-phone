// SensitiveScreenDeviceTest.kt: two capabilities driving.xml does not declare.
//
// History
//   2026-08-10  A. Sigdel  Created, run before either flag, and left asserting
//                          what the run measured rather than what was expected.
//
// #472 settled the FLAG_SECURE question by putting a real activity in front of
// the service and reading it, after a record had claimed the opposite for a
// milestone. Two more attributes are absent from driving.xml, and the argument
// for adding either would have been the same kind of argument that was wrong
// then, so they were measured the same way.
//
// Both answers came back no, and neither attribute was added. What each test
// asserts is therefore the refusal, which makes both of them tripwires: adding
// the attribute turns the test red, and the red is a request to come back and
// correct docs/decisions/what-android-allows.md rather than a bug.
//
// **accessibilityDataSensitive is withheld.** A view marked that way is invisible
// to a service that has not declared android:isAccessibilityTool. It is not
// declared and should not be: this is an automation agent rather than an
// accessibility tool, and the attribute is the framework asking a question it
// would be answering falsely. So an application does have a working way to hide
// from this agent, which what-android-allows.md used to imply it did not.
//
// **takeScreenshot was refused** with "Services don't have the capability of
// taking the screenshot", thrown from the binder rather than delivered to the
// callback. #439 said it "needs no extra capability" and that was measured
// false. The attribute was not added then, because nothing called
// takeScreenshot and a capability declared ahead of its caller is one nobody
// can weigh. Unit 4 added it beside DrivingService.capture, so the two cases
// here now assert the success and the whole path behind it.

package com.getlora.wattrouter.app

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.getlora.wattrouter.Sighting
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class SensitiveScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name get() = "${context.packageName}/${DrivingService::class.java.name}"

    /** As SecureScreenDeviceTest, and for the reason its header gives. */
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
    fun aSensitiveNodeIsWithheldAndAnOrdinaryOneIsNot() {
        // Below 34 the fixture produces two ordinary buttons, so the assertion
        // would answer a question that was never asked.
        assumeTrue(
            "accessibilityDataSensitive is API 34, this device is ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )

        val service = waitForConnection()
        assertNotNull("the service was enabled and never connected", service)

        context.startActivity(
            Intent()
                .setClassName(
                    instrumentation.context.packageName,
                    SensitiveActivity::class.java.name,
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        // Polled for the reason SecureScreenDeviceTest states: `read` answers
        // null while no window has focus, and that is indistinguishable here
        // from a node being withheld. The loop waits for the *ordinary* button,
        // which is the control, and only then reads the sensitive one.
        var ordinary = 0
        var sensitive = 0
        var seen = 0
        repeat(WAITS) {
            val reading = service!!.read()
            if (reading != null) {
                seen = reading.seen.size
                ordinary = reading.seen.count { it.says(SensitiveActivity.ORDINARY) }
                sensitive = reading.seen.count { it.says(SensitiveActivity.SENSITIVE) }
                if (ordinary >= 1) return@repeat
            }
            Thread.sleep(PAUSE)
        }

        // The control first. Without it a withheld node and a window that never
        // arrived are the same observation, and only one of them is an answer.
        assertTrue(
            "the fixture's ordinary button never appeared in ${WAITS * PAUSE}ms, so " +
                "nothing here was measured. Last read saw $seen node(s)",
            ordinary >= 1,
        )

        assertEquals(
            "an accessibilityDataSensitive node was readable. Either driving.xml " +
                "now declares android:isAccessibilityTool, or the platform " +
                "stopped withholding it. Both change what-android-allows.md and " +
                "SECURITY.md, and #603 says how. Last read saw $seen node(s)",
            0,
            sensitive,
        )
    }

    @Test
    fun takingAScreenshotWorksNowThatTheServiceMaySay() {
        val service = waitForConnection()
        assertNotNull("the service was enabled and never connected", service)

        // The inverse of what this asserted until #439's capture layer. It
        // asserted the refusal and said so: adding the attribute turns it red,
        // and the red is a request to come back and correct the records. This
        // is that correction, in the same pull request as the attribute.
        val done = CountDownLatch(1)
        var refusal: String? = null
        var taken = false

        try {
            service!!.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        // Closed here rather than left to a finaliser: it is a
                        // hardware allocation and this suite takes several.
                        screenshot.hardwareBuffer.close()
                        taken = true
                        done.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        refusal = "callback error $errorCode"
                        done.countDown()
                    }
                },
            )
            done.await(WAITS * PAUSE, TimeUnit.MILLISECONDS)
        } catch (denied: SecurityException) {
            // The failure this used to assert. Kept as its own message, because
            // a manifest that lost the attribute reads very differently from a
            // frame the platform would not grab.
            refusal = "the binder refused: ${denied.message}"
        }

        assertTrue(
            "takeScreenshot did not succeed ($refusal). driving.xml declares " +
                "android:canTakeScreenshot since #439, and DrivingService.capture " +
                "is the caller it arrived with",
            taken,
        )
    }

    @Test
    fun theServiceEncodesWhatItCaptured() = runBlocking {
        // The whole path rather than the framework call alone. A frame that
        // grabs and will not wrap, compress or encode is a tool answering
        // nothing, and all three of those steps are in capture.
        val service = waitForConnection()
        assertNotNull("the service was enabled and never connected", service)

        val image = service!!.capture()

        assertNotNull("capture answered nothing", image)
        assertTrue(image!!.url, image.url.startsWith("data:image/png;base64,"))
        // Long enough to be a picture rather than an empty encode.
        assertTrue(image.url.length.toString(), image.url.length > 1000)
    }

    private companion object {
        const val WAITS = 40
        const val PAUSE = 250L
    }
}

/**
 * Whether a sighting carries a label, either way the renderer can hold one.
 *
 * # Arguments
 * * `text`: WHERE nothing else on any screen produces it.
 *
 * # Returns
 * True IF the label or the handle's text contains it. Both are read because
 * which one a node lands in depends on how the application set it, and the
 * fixture sets both.
 */
private fun Sighting.says(text: String) =
    label?.contains(text) == true || handle.text?.contains(text) == true
