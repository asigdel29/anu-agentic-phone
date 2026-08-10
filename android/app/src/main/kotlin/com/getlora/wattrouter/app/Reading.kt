// Reading.kt: filling in the checklist from what the phone says.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   isEnabled     Whether the service is switched on.
//   isSideloaded  Whether anybody vouched for this install.
//   readiness     The whole list.
//
// Four of the six rows are checkSelfPermission and settled. The two here each
// have a trap, and both are functions of a string, so they are checked on the
// JVM rather than by installing the app two ways.

package com.getlora.wattrouter.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.getlora.wattrouter.Readiness

/**
 * Whether the service is in the system's enabled list.
 *
 * Split rather than searched. The list is colon-separated `package/class`
 * entries, and a plain `contains` would also match an app whose own component
 * name happens to contain ours: contrived, and removing the question costs a
 * `split`.
 *
 * Both spellings are accepted. The platform writes either the fully qualified
 * `package/com.package.Class` or the abbreviated `package/.Class`, and a phone
 * that wrote the short form would read as off, sending somebody to switch on
 * what they had already switched on.
 *
 * @param enabled the raw setting, which is null before anything has ever been
 *   enabled rather than empty.
 */
internal fun isEnabled(enabled: String?, packageName: String, service: String): Boolean {
    val full = "$packageName/$service"
    val short = "$packageName/${service.removePrefix(packageName)}"

    return enabled.orEmpty()
        .split(':')
        .any { it.trim().equals(full, ignoreCase = true) || it.trim().equals(short, true) }
}

/**
 * Whether this install came from anywhere that vouched for it.
 *
 * @param installer the installing package, or null. Null is `adb install` and
 *   a file manager alike: nobody vouched, so restricted settings applies and
 *   the accessibility toggle is greyed until somebody clears it.
 */
internal fun isSideloaded(installer: String?): Boolean = installer.isNullOrBlank()

/** The checklist, as this phone stands right now. */
internal fun readiness(context: Context): Readiness {
    val granted = { permission: String ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    return Readiness.of(
        // The setting rather than DrivingService.connected: the service binds a
        // moment after the switch, and a screen re-read on resume is read at
        // exactly that moment, when somebody comes back from Settings.
        driving = isEnabled(
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            context.packageName,
            DrivingService::class.java.name,
        ),
        // Below API 33 there is no permission and notifications are on unless
        // somebody turned the channel off, which is not this list's business.
        notifying = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(android.Manifest.permission.POST_NOTIFICATIONS),
        calendar = granted(android.Manifest.permission.READ_CALENDAR),
        contacts = granted(android.Manifest.permission.READ_CONTACTS),
        location = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION),
        sideloaded = isSideloaded(installerOf(context)),
    )
}

/** Who installed this, as the platform will say. */
private fun installerOf(context: Context): String? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
}.getOrNull()
