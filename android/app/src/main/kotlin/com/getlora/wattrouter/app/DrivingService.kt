// DrivingService.kt — the only thing that can read another app's screen.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  Acts as well as reads.
//   2026-08-09  A. Sigdel  Types, and refuses a password field here rather than
//                          in the tool, so a later one cannot route around it.
//
// It listens to nothing, which is the decision worth reading twice. The obvious
// shape for an accessibility service is event-driven: track
// TYPE_WINDOW_CONTENT_CHANGED, keep a model of the screen, answer from it. That
// is what how-the-agent-drives.md rules out. Events arrive faster than a screen
// changes meaningfully and out of order with the tree they describe, and a
// cached model is the "remembered rather than re-read" failure #233 opens by
// warning about. So onAccessibilityEvent does nothing and every read fetches the
// tree; the event types in the config are there because a service must declare
// some to be listed at all.
//
// One Viewing per connection, built in onServiceConnected. A service the system
// restarts gets a fresh epoch, which is the whole mechanism by which a handle
// cannot survive a restart. Building it lazily, or holding it in the companion,
// would give two lives one epoch and put the hole back.

package com.getlora.wattrouter.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.getlora.wattrouter.Aim
import com.getlora.wattrouter.Done
import com.getlora.wattrouter.Generation
import com.getlora.wattrouter.Generations
import com.getlora.wattrouter.Handle
import com.getlora.wattrouter.Node
import com.getlora.wattrouter.Reading
import com.getlora.wattrouter.Viewing

/** The screen, when somebody has allowed it to be read. */
class DrivingService : AccessibilityService() {

    /**
     * Built here rather than lazily. This runs once per life of the service, so
     * the epoch inside it is once per life too.
     */
    private var viewing: Viewing? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        viewing = Viewing(Generations.fresh())
        connected = this
    }

    /**
     * Nothing. See the header: a screen is read when it is needed, and a model
     * kept in step by events is a model that disagrees with the tree.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** Nothing to abandon: no work is held between calls. */
    override fun onInterrupt() = Unit

    override fun onDestroy() {
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
     * Put text in what a handle names, replacing what is there.
     *
     * The same retain-and-release as [tap]: setting text is a node action, so
     * it needs the node rather than the copy of it.
     */
    fun type(at: Handle, from: Generation, text: String): Done? {
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
