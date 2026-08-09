// DrivingService.kt — the only thing that can read another app's screen.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  Acts as well as reads.
//   2026-08-09  A. Sigdel  Types, and refuses a password field here rather than
//                          in the tool, so a later one cannot route around it.
//   2026-08-09  A. Sigdel  Refuses to act where #440 says not to, which is why
//                          it now consumes exactly one event.
//   2026-08-09  A. Sigdel  Shows a banner while a turn is driving.
//
// It keeps no model of the screen, which is the decision worth reading twice.
// The obvious shape for an accessibility service is event-driven: track
// TYPE_WINDOW_CONTENT_CHANGED, keep a model, answer from it. That is what
// how-the-agent-drives.md rules out. Events arrive faster than a screen changes
// meaningfully and out of order with the tree they describe, and a cached model
// is the "remembered rather than re-read" failure #233 opens by warning about.
// So every read fetches the tree.
//
// It does consume one event, and this header used to say it consumed none. The
// activity's class name is what tells the accessibility settings page from the
// display settings page, #440 bars the first and not the second, and that name
// arrives only on TYPE_WINDOW_STATE_CHANGED — rootInActiveWindow gives a package
// and no more. So one string is recorded, compared against a deny list, and used
// for nothing else. The argument above is about keeping a model of the screen in
// step; this is not that, and leaving the header claiming otherwise would be
// worse than amending it.
//
// One Viewing per connection, built in onServiceConnected. A service the system
// restarts gets a fresh epoch, which is the whole mechanism by which a handle
// cannot survive a restart. Building it lazily, or holding it in the companion,
// would give two lives one epoch and put the hole back.

package com.getlora.wattrouter.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.getlora.wattrouter.Aim
import com.getlora.wattrouter.Barred
import com.getlora.wattrouter.barred
import com.getlora.wattrouter.Done
import com.getlora.wattrouter.Generation
import com.getlora.wattrouter.Generations
import com.getlora.wattrouter.Handle
import com.getlora.wattrouter.Node
import com.getlora.wattrouter.Onward
import com.getlora.wattrouter.Reading
import com.getlora.wattrouter.Viewing
import com.getlora.wattrouter.Way

/** The screen, when somebody has allowed it to be read. */
class DrivingService : AccessibilityService() {

    /**
     * Built here rather than lazily. This runs once per life of the service, so
     * the epoch inside it is once per life too.
     */
    private var viewing: Viewing? = null

    /**
     * The foreground activity's class, or null before the first window change.
     *
     * Volatile for [connected]'s reason: written on the service's thread and
     * read from a turn on another.
     */
    @Volatile
    private var inFront: String? = null

    /** The banner, while a turn is driving. */
    private var banner: Banner? = null
    private var shown = false

    /** What the stop button does. Set by whoever started the turn. */
    var onStop: (() -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        viewing = Viewing(Generations.fresh())
        connected = this
    }

    /**
     * Which window is in front, and nothing else.
     *
     * Not a model of the screen — one string, compared against #440's deny
     * list. Everything about what is *on* the screen is still read when it is
     * needed, for the reason the header gives.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        inFront = event.className?.toString()
    }

    /** Nothing to abandon: no work is held between calls. */
    override fun onInterrupt() = Unit

    /**
     * Show what the agent is doing, over whatever it is doing it to.
     *
     * @param what the person's own words. Null takes the banner away, which is
     *   what the end of a turn does — however it ended.
     */
    fun showing(what: String?) {
        if (what == null) {
            hide()
            return
        }
        val showing = banner ?: Banner(this) { onStop?.invoke() }.also { banner = it }
        showing.say(what)
        if (!shown) attach(showing)
    }

    /**
     * Put it on the display.
     *
     * TYPE_ACCESSIBILITY_OVERLAY, which needs no permission on any release
     * this app runs on — it is available to an accessibility service and to
     * nothing else, which is exactly what this is. #446 planned for
     * SYSTEM_ALERT_WINDOW below API 34 on the belief that the free route was
     * attachAccessibilityOverlayToDisplay and so new; that call takes a
     * SurfaceControl there is no public way to build, and this window type has
     * been here since API 22. So the permission is gone and so is the choice.
     */
    private fun attach(showing: Banner) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE so the app underneath keeps the keyboard, and
            // NOT_TOUCH_MODAL so a tap outside the banner reaches the app the
            // agent is aiming at. An overlay that swallows a tap makes the
            // agent's own actions fail as though the app refused them.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        // Answering false rather than throwing: a turn that cannot show a
        // banner is still a turn, and the service is not the place to decide
        // that a missing overlay ends one.
        shown = runCatching {
            getSystemService(WindowManager::class.java).addView(showing.view, params)
            true
        }.getOrDefault(false)
    }

    private fun hide() {
        val showing = banner ?: return
        if (shown) {
            runCatching { getSystemService(WindowManager::class.java).removeView(showing.view) }
        }
        banner = null
        shown = false
    }

    override fun onDestroy() {
        hide()
        connected = null
        viewing = null
        super.onDestroy()
    }

    /**
     * What is on screen now.
     *
     * @return null when the service is not connected, or while no window has
     *   focus — which the framework answers for a moment after a launch and
     *   whenever the screen is off.
     */
    fun read(): Reading? {
        val tree = screen() ?: return null
        return viewing?.read(tree)
    }

    /**
     * Why the agent may not act on what is showing, or null.
     *
     * Reading goes through this too, which is where the tempting exception is:
     * looking at a screen sounds harmless, and read_screen on the permissions
     * page tells the model exactly which button says Allow. The refusal it
     * would then get from tap is one it can plan around.
     *
     * The keyguard is read now rather than remembered — a phone locks while a
     * turn is running, which is the case this is for.
     */
    fun barredNow(): Barred? = barred(
        packageName = rootInActiveWindow?.packageName?.toString(),
        activity = inFront,
        own = packageName,
        locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
    )

    /**
     * Aim at a handle, against the screen as it is now.
     *
     * @return null on the terms [read] states. Everything else is an [Aim],
     *   including every way of missing.
     */
    fun aim(at: Handle, from: Generation): Aim? {
        val tree = screen() ?: return null
        return viewing?.aim(tree, at, from)
    }

    /**
     * Tap what a handle names.
     *
     * The tree is retained for this call and released before it returns, which
     * is the only place in the app that owes the framework anything back. A
     * copy is not something performAction can be called on, so acting needs the
     * node the copy was made from — and needs it only until the click lands.
     */
    fun tap(at: Handle, from: Generation): Done? {
        barredNow()?.let { return Done.Refused(it.why) }
        val viewing = viewing ?: return null
        val live = snapshot(rootInActiveWindow, retain = true) ?: return null

        return try {
            when (val aim = viewing.aim(live, at, from)) {
                is Aim.Moved -> Done.Moved(aim.now)
                is Aim.Lost -> Done.Lost(aim.resolution)
                is Aim.At -> click(aim.node)
            }
        } finally {
            release(live)
        }
    }

    /**
     * Move a list through its content.
     *
     * The same retain-and-release as [tap]. A node action rather than a
     * gesture, so canPerformGestures stays out of driving.xml and no
     * coordinate is involved anywhere on this path — which is stronger than
     * how-the-agent-drives.md promised, and worth keeping.
     */
    fun scroll(at: Handle, from: Generation, onward: Onward): Done? {
        barredNow()?.let { return Done.Refused(it.why) }
        val viewing = viewing ?: return null
        val live = snapshot(rootInActiveWindow, retain = true) ?: return null

        return try {
            when (val aim = viewing.aim(live, at, from)) {
                is Aim.Moved -> Done.Moved(aim.now)
                is Aim.Lost -> Done.Lost(aim.resolution)
                is Aim.At -> shift(aim.node, onward)
            }
        } finally {
            release(live)
        }
    }

    /**
     * Scroll a node, or say why not.
     *
     * A list already at its end answers false, and that is the answer to "is
     * there any more" rather than a failure. Told it failed, a model retries;
     * told the list is at its end, it stops.
     */
    private fun shift(node: Node, onward: Onward): Done {
        if (!node.isScrollable) {
            return Done.Refused(
                "that does not scroll. read_screen marks the lines that do",
            )
        }
        val source = (node as? Copied)?.source
            ?: return Done.Refused("it could not be reached on the screen any more")

        val action = when (onward) {
            Onward.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Onward.BACK -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return if (source.performAction(action)) {
            Done.Did(read())
        } else {
            Done.Refused(
                "it is already at the ${if (onward == Onward.FORWARD) "end" else "start"}, " +
                    "so nothing moved",
            )
        }
    }

    /**
     * Press one of the system's own buttons.
     *
     * No tree is fetched first and none is retained: a global action names no
     * node, so there is nothing to resolve and nothing to give back.
     */
    fun navigate(way: Way): Done? {
        // Barred here too, and the locked case is why: back and home on a
        // locked phone are somebody else's presses.
        barredNow()?.let { return Done.Refused(it.why) }
        if (viewing == null) return null

        val pressed = performGlobalAction(
            when (way) {
                Way.BACK -> GLOBAL_ACTION_BACK
                Way.HOME -> GLOBAL_ACTION_HOME
                Way.RECENTS -> GLOBAL_ACTION_RECENTS
                Way.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            },
        )

        // The screen is read after the press rather than before, and it is the
        // only way to say where that ended up — what back does depends on where
        // it was pressed.
        return if (pressed) Done.Did(read()) else Done.Refused("the system would not do that here")
    }

    /**
     * Put text in what a handle names, replacing what is there.
     *
     * The same retain-and-release as [tap]: setting text is a node action, so
     * it needs the node rather than the copy of it.
     */
    fun type(at: Handle, from: Generation, text: String): Done? {
        barredNow()?.let { return Done.Refused(it.why) }
        val viewing = viewing ?: return null
        val live = snapshot(rootInActiveWindow, retain = true) ?: return null

        return try {
            when (val aim = viewing.aim(live, at, from)) {
                is Aim.Moved -> Done.Moved(aim.now)
                is Aim.Lost -> Done.Lost(aim.resolution)
                is Aim.At -> write(aim.node, text)
            }
        } finally {
            release(live)
        }
    }

    /**
     * Set a node's text, or say why not.
     *
     * The password refusal is here rather than in the tool, which is
     * how-the-agent-drives.md's rule about where safety lives: a second tool
     * that also types would otherwise have to remember this one, and the ninth
     * one would not. prune never carries a password's value out, so a model
     * asked to fill one in is typing something it invented or something the
     * person said out loud — and the person can type their own password.
     */
    private fun write(node: Node, text: String): Done {
        if (node.isPassword) {
            return Done.Refused(
                "that is a password field and the assistant does not type into " +
                    "those. Ask the person to fill it in",
            )
        }
        if (!node.isEditable) {
            return Done.Refused(
                "there is nowhere to type there. read_screen marks the lines that " +
                    "take text, and this is not one of them",
            )
        }

        val source = (node as? Copied)?.source
            ?: return Done.Refused("it could not be reached on the screen any more")

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            Done.Did(read())
        } else {
            Done.Refused("the app would not take text there")
        }
    }

    /**
     * Click a node, or say why not.
     *
     * A node that will not take a click is refused rather than escalated to
     * whichever ancestor would. Walking up is what a person tapping a row's
     * label effectively does, and it is also tapping something the model did
     * not name — and read_screen already marks which lines can be tapped, so
     * the refusal points at that column instead.
     */
    private fun click(node: Node): Done {
        val source = (node as? Copied)?.source
            ?: return Done.Refused("it could not be reached on the screen any more")

        return if (source.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Done.Did(read())
        } else {
            Done.Refused(
                "the app would not take a tap there. read_screen marks the lines " +
                    "that can be tapped, and this is not one of them",
            )
        }
    }

    /**
     * The tree, copied.
     *
     * `rootInActiveWindow` is the active window rather than every window, which
     * is what a person is looking at. A dialog over an app is the active one; a
     * notification shade pulled down is too.
     */
    private fun screen(): Node? = snapshot(rootInActiveWindow)

    companion object {
        /**
         * The connected service, or null.
         *
         * Volatile because it is written on the service's own thread and read
         * from a turn on another. A tool holding a stale reference would call
         * into a service the system has already torn down.
         */
        @Volatile
        var connected: DrivingService? = null
            private set
    }
}
