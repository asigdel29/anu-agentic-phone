// Barred.kt: where the agent may not act, whatever it was asked to do.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Barred  Why an action may not happen here.
//   barred  Whether it may.
//
// how-the-agent-drives.md puts safety where the action is dispatched rather
// than in the tools, so the enforcement lives in the service. The deciding is
// here because it is a function of four strings, and a rule nobody can test is
// a rule that is one refactor from being wrong.
//
// The rule is on the activity rather than the package for the settings cases,
// and that is deliberate. Settings is otherwise a legitimate place to act,
// since somebody asking to turn on dark mode means Settings, so barring it
// would trade a real capability for a rule that could be narrower.

package com.getlora.wattrouter

/** Why an action may not happen where it was asked. */
enum class Barred(val why: String) {
    /**
     * The keyguard is up. A locked phone is somebody who is not there, and
     * whatever was asked for was asked before they left.
     */
    LOCKED(
        "the phone is locked, so nothing on the screen can be acted on. " +
            "Ask the person to unlock it",
    ),

    /**
     * This app. An agent reading its own conversation and acting on it is a
     * loop whose first step looks reasonable.
     */
    ITSELF("that is this assistant's own screen, which it does not drive"),

    /**
     * The runtime-permission UI, as a whole package. An agent that can tap
     * Allow can grant itself the calendar, contacts and location it was
     * refused, and Permission's own refusal puts the words for it into the
     * transcript, so the chain is not hypothetical.
     */
    PERMISSIONS(
        "that is the screen for granting permissions, and the assistant does " +
            "not use it. Ask the person to make that choice themselves",
    ),

    /**
     * The accessibility and device-admin settings. The first is the switch
     * this agent runs on, so reaching it means being able to turn itself off
     * mid-task or on for something else; the second hands over a policy
     * controller.
     */
    CONTROLS(
        "that screen controls what the assistant itself is allowed to do, so " +
            "it does not act there. Ask the person to change it if they want to",
    ),
}

/**
 * Whether the agent may act on what is showing.
 *
 * @param packageName the foreground app, as `rootInActiveWindow` reports it.
 * @param activity the foreground activity's class. Null when it is not known,
 *   which is the state after a restart until the first window change, and is
 *   treated as unknown rather than as safe.
 * @param own this app's own package.
 * @param locked whether the keyguard is up.
 * @return why not, or null to go ahead.
 */
fun barred(packageName: String?, activity: String?, own: String, locked: Boolean): Barred? = when {
    locked -> Barred.LOCKED
    packageName == own -> Barred.ITSELF
    packageName != null && packageName.endsWith(PERMISSION_UI) -> Barred.PERMISSIONS
    // Substring rather than an exact class list. The settings app renames and
    // rearranges these between releases, and a list of exact names is a list
    // that silently stops matching, which here means silently stops refusing.
    activity != null && CONTROLLING.any { activity.contains(it, ignoreCase = true) } ->
        Barred.CONTROLS
    else -> null
}

/**
 * The permission UI's package suffix.
 *
 * Matched on the end because it is `com.android.permissioncontroller` on some
 * builds and `com.google.android.permissioncontroller` on others, and a phone
 * that used a third spelling would otherwise be one where this does not apply.
 */
private const val PERMISSION_UI = "permissioncontroller"

/** What in an activity's name means it controls the agent. */
private val CONTROLLING = listOf("Accessibility", "DeviceAdmin")
