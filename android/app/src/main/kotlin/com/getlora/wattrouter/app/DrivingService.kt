// DrivingService.kt — the only thing that can read another app's screen.
//
// History
//   2026-08-09  A. Sigdel  Created.
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
import android.view.accessibility.AccessibilityEvent
import com.getlora.wattrouter.Aim
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
