// AssistDeclarationTest.kt: the assist gesture can reach this app.
//
// History
//   2026-08-09  A. Sigdel  Created with #527.
//
// On a device, for PermissionDeclarationTest's reason: a manifest is only a
// manifest once it has been merged into an installed package, and an intent
// filter that was dropped in a merge looks like nothing at all. Nobody notices
// an app that is missing from a picker; they conclude the feature was never
// built, which is the failure with no error attached.
//
// Asked of the package manager rather than read out of the file. Reading the
// source would pass over an app whose merged manifest lost the filter, which is
// the only way this ever goes wrong.

package com.getlora.wattrouter.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistDeclarationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theAssistGestureResolvesToThisApp() {
        // ACTION_ASSIST is what long-press home sends, and what Settings offers
        // in its digital assistant picker. Being resolvable is what puts the app
        // in that list; it does not take the slot, which #527 argues is the
        // person's choice rather than this app's.
        val resolved = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_ASSIST).setPackage(context.packageName),
            0,
        )

        assertTrue(
            "nothing in this package answers ACTION_ASSIST, so it can never be " +
                "chosen as the assistant",
            resolved.isNotEmpty(),
        )
    }

    @Test
    fun theShareFilterStillResolvesBesideIt() {
        // Added because the two filters sit next to each other and a merge that
        // loses one usually loses both. A share is the older path and the one
        // with a test elsewhere only for its parsing, not its declaration.
        val resolved = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName),
            0,
        )

        assertTrue("this package no longer appears in a text share sheet", resolved.isNotEmpty())
    }
}
