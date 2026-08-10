// PermissionDeclarationTest.kt: a capability the manifest forgot.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On a device, because the manifest is only a manifest once it has been merged
// into an installed package. This is the one disagreement in AndroidAsking that
// fails silently and misleadingly: a permission the app does not declare is
// refused by checkSelfPermission, shows no dialog when requested, and leaves
// rationale false, which is the shape of a permanent denial. The app would then
// tell somebody to turn it on at a Settings row that does not exist.

package com.getlora.wattrouter.app

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.getlora.wattrouter.Capability
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDeclarationTest {
    @Test
    fun everyCapabilityHasThePermissionItAsksFor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") // The replacement is API 33; the floor here is 29.
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

        Capability.entries.forEach {
            val permission = permissionFor(it)
            assertTrue("$permission is asked for and never declared", permission in declared)
        }
    }
}
