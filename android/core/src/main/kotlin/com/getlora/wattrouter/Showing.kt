// Showing.kt — how the agent shows itself over the app it is driving.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Showing    Which way, or none.
//   showingOn  Which one applies here.
//
// Two mechanisms, and #229 separated them before either was built. Since API 34
// an accessibility service can attach an overlay of its own: no
// SYSTEM_ALERT_WINDOW, not blocked over Settings, and owned by the service that
// is already running. Below that it is TYPE_APPLICATION_OVERLAY, which is
// granted from a Settings screen rather than a dialog and so costs a trip out of
// the app.
//
// The consequence worth writing down is what it does to the manifest. The
// permission is declared with maxSdkVersion, so a phone on 34 or later never
// sees it in the app's permission list and never has it to grant. Declaring it
// unconditionally would ask every phone for the one permission
// what-android-allows.md records as the one Play looks at hardest, in order to
// use it on none of them.

package com.getlora.wattrouter

/** How the agent may show itself over another app. */
enum class Showing {
    /**
     * The service attaches its own overlay. API 34 and later, and it needs
     * nothing granted.
     */
    ATTACHED,

    /** A window over everything. Below 34, and only once somebody allows it. */
    OVER_EVERYTHING,

    /**
     * Nowhere yet: an older phone whose permission has not been granted.
     *
     * Its own answer rather than falling back to nothing, because the caller
     * has somewhere to send the person and a silent no would leave the agent
     * driving invisibly — which is the thing an overlay exists to prevent.
     */
    NOT_YET,
}

/**
 * Which way applies on this phone.
 *
 * @param sdk the running API level.
 * @param allowed whether `Settings.canDrawOverlays` says yes. Read fresh:
 *   it is granted and revoked from a Settings screen while the app is not
 *   looking, which is the same reason Permission re-reads its own state.
 */
fun showingOn(sdk: Int, allowed: Boolean): Showing = when {
    // First, and without consulting `allowed` at all: on 34 the permission is
    // not merely unnecessary, it is not declared, so its absence there says
    // nothing about whether an overlay can be shown.
    sdk >= ATTACHABLE -> Showing.ATTACHED
    allowed -> Showing.OVER_EVERYTHING
    else -> Showing.NOT_YET
}

/** The first release where a service can attach its own overlay. */
const val ATTACHABLE = 34
