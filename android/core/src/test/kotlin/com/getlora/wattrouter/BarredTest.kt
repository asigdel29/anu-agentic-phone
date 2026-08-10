// BarredTest.kt: the screens the agent is not allowed to touch.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. Each case is a way the agent could widen what it is allowed to
// do, and the last two are the ones that would look like ordinary automation
// in a transcript.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarredTest {
    private val own = "com.getlora.wattrouter"

    private fun on(
        packageName: String? = "com.example.notes",
        activity: String? = "com.example.notes.MainActivity",
        locked: Boolean = false,
    ) = barred(packageName, activity, own, locked)

    @Test
    fun anOrdinaryAppIsFine() {
        assertNull(on())
    }

    @Test
    fun aLockedPhoneIsSomebodyWhoIsNotThere() {
        // And it outranks everything: whatever was asked for was asked before
        // they left.
        assertEquals(Barred.LOCKED, on(locked = true))
        assertEquals(Barred.LOCKED, on(packageName = own, locked = true))
    }

    @Test
    fun itDoesNotDriveItself() {
        // An agent reading its own conversation and acting on it is a loop
        // whose first step looks reasonable.
        assertEquals(Barred.ITSELF, on(packageName = own, activity = "$own.app.MainActivity"))
    }

    @Test
    fun thePermissionScreenIsBarredHoweverItIsSpelled() {
        // An agent that can tap Allow can grant itself the calendar, contacts
        // and location it was refused, and Permission's refusal already puts
        // the words for it into the transcript.
        assertEquals(Barred.PERMISSIONS, on(packageName = "com.android.permissioncontroller"))
        assertEquals(
            Barred.PERMISSIONS,
            on(packageName = "com.google.android.permissioncontroller"),
        )
    }

    @Test
    fun theAccessibilityScreenIsBarredAndTheRestOfSettingsIsNot() {
        // The rule is on the activity because Settings is otherwise a
        // legitimate place to act: turning on dark mode means Settings.
        assertEquals(
            Barred.CONTROLS,
            on("com.android.settings", "com.android.settings.AccessibilitySettingsActivity"),
        )
        assertNull(on("com.android.settings", "com.android.settings.DisplaySettingsActivity"))
    }

    @Test
    fun soIsTheOneThatHandsOverAPolicyController() {
        assertEquals(
            Barred.CONTROLS,
            on("com.android.settings", "com.android.settings.DeviceAdminAdd"),
        )
    }

    @Test
    fun theMatchIsLooseBecauseTheSettingsAppRenamesThings() {
        // A list of exact class names is a list that silently stops matching
        // after a platform release, which here means silently stops refusing.
        listOf(
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            "com.android.settings.Settings\$AccessibilityDetailsSettingsActivity",
            "com.samsung.accessibility.SomethingElse",
        ).forEach { assertEquals(it, Barred.CONTROLS, on("com.android.settings", it)) }
    }

    @Test
    fun anUnknownActivityIsNotTreatedAsSafe() {
        // Null is the state after a restart, before the first window change.
        // It bars nothing on its own (the package rules still apply) and the
        // service is where that gap is closed.
        assertNull(on(activity = null))
        assertEquals(Barred.ITSELF, on(packageName = own, activity = null))
        assertEquals(Barred.PERMISSIONS, on(packageName = "com.android.permissioncontroller", activity = null))
    }

    @Test
    fun everyReasonSaysWhatThePersonCanDoInstead() {
        // A refusal a model can only apologise for teaches it to stop asking.
        Barred.entries.forEach {
            assertEquals(it.name, true, it.why.contains("Ask the person") || it.why.contains("does not"))
        }
    }
}
