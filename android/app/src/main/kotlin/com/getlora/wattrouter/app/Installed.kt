// Installed.kt: the apps this phone can start, and starting one.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   installed  Every app with a launcher icon.
//   start      Opening one.
//
// The manifest's <queries> element is what makes any of this work. Since
// targetSdk 30 an app sees its own package and almost nothing else, so without
// it every lookup answers "there is no app called that": a total failure whose
// message reads like a spelling mistake.
//
// The scoped filter is the right one: ACTION_MAIN with CATEGORY_LAUNCHER grants
// visibility of exactly the apps that have an icon, which is exactly the set
// open_app offers. QUERY_ALL_PACKAGES would also work, asks for far more than is
// needed, and is a restricted permission, and a sideloaded build could carry it,
// which is not a reason to.

package com.getlora.wattrouter.app

import android.content.Context
import android.content.Intent
import com.getlora.wattrouter.Launchable

/**
 * Every app that can be started from the launcher.
 *
 * Matched on the label rather than the package name, because the label is what
 * somebody sees under the icon and what they will say. This app is left out:
 * an agent restarting the app it is running inside is a loop with a plausible
 * first step.
 */
internal fun installed(context: Context): List<Launchable> {
    val wanted = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val packages = context.packageManager

    @Suppress("DEPRECATION") // The replacement is API 33; the floor here is 29.
    return packages.queryIntentActivities(wanted, 0)
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .filter { it.packageName != context.packageName }
        .map { Launchable(it.loadLabel(packages).toString(), it.packageName) }
        // One app can expose two launcher activities, and two entries with one
        // package would read to the model as two apps to choose between.
        .distinctBy { it.packageName }
        .sortedBy { it.label }
        .toList()
}

/**
 * Start an app.
 *
 * @return whether there was anything to start. FLAG_ACTIVITY_NEW_TASK is not
 *   optional from a service: without it the call throws rather than failing
 *   quietly, which is the better of the two failures and still not one to ship.
 */
internal fun start(context: Context, packageName: String): Boolean {
    val opening = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: return false

    context.startActivity(opening.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}
