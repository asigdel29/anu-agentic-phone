// InstalledDeviceTest.kt — the package manager admits apps exist.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// The claim the JVM cannot make: every decision in Installed.kt is about what
// the framework actually hands back.
//
// What this does NOT prove is the <queries> element, and that is worth writing
// down rather than assuming. Removing it and running this suite leaves every
// test here green — an app under instrumentation is not filtered the way the
// installed one is, and neither APK carries QUERY_ALL_PACKAGES, so the run was
// genuinely without the element and still saw every app.
//
// So the element stays because it is right for the shipped app, and its absence
// is a failure only a real launch would show. Believing this suite covered it
// would be worse than knowing it does not.

package com.getlora.wattrouter.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRealImageHasAppsOnIt() {
        val apps = installed(context)

        assertTrue("the package manager offered nothing at all", apps.isNotEmpty())
        assertTrue(apps.toString(), apps.all { it.label.isNotBlank() })
        assertTrue(apps.toString(), apps.all { it.packageName.isNotBlank() })
    }

    @Test
    fun theAgentIsNotOnItsOwnList() {
        // Restarting the app it is running inside is a loop with a plausible
        // first step.
        val apps = installed(context)

        assertFalse(apps.toString(), apps.any { it.packageName == context.packageName })
    }

    @Test
    fun anAppWithTwoLauncherEntriesIsStillOneApp() {
        // Two entries under one package would read to the model as two apps to
        // choose between, and it would be told to name one of them exactly.
        val apps = installed(context)

        assertEquals(apps.size, apps.map { it.packageName }.toSet().size)
    }

    @Test
    fun theLabelsAreTheOnesSomebodyWouldSay() {
        // What the launcher shows under an icon, not a package name. Checked
        // against Settings because every image has one — and by label rather
        // than through OpenAppTool, which would open it and leave the suite on
        // a different screen than it found.
        val apps = installed(context)

        assertTrue(
            apps.toString(),
            apps.any { it.label.equals("Settings", ignoreCase = true) },
        )
        assertTrue(apps.toString(), apps.none { it.label.startsWith("com.") })
    }
}
