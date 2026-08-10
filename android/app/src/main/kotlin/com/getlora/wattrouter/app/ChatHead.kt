// ChatHead.kt — the way back to the agent from inside another app.
//
// History
//   2026-08-09  A. Sigdel  Created with #522.
//
// Contents
//   ChatHead  A bubble over everything, dragged anywhere, tapped to open.
//
// The same window type as Banner and for the same reason: TYPE_ACCESSIBILITY_OVERLAY
// is available to an accessibility service and to nothing else, and needs no
// permission on any release this runs on. #446 spent a round trip establishing
// that SYSTEM_ALERT_WINDOW is not required, and that finding is what makes this
// cheap enough to be worth having.
//
// A surface for before and after a task, never during one. The design review's
// finding, recorded on DrivingService's summon callback: an expanded surface *is*
// the foreground app, so anything the agent reads while one is open is the
// agent's own window rather than the screen it meant to look at. DrivingService
// takes the bubble away while a turn drives, and the banner is the surface for
// that moment.
//
// Not focusable, which is the same rule Banner follows and the one that is easy
// to break later: an overlay that takes touches makes the agent's own taps fail
// as though the app underneath refused them, and nothing in the transcript looks
// wrong when it happens.

package com.getlora.wattrouter.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A bubble that sits over every app until it is tapped.
 *
 * @param onOpen what a tap means. A drag is not a tap and does not call this,
 *   which is the whole of the touch handling below.
 */
internal class ChatHead(context: Context, private val onOpen: () -> Unit) {

    /** Where it is, and what a caller hands to `addView`. */
    val params: WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WRAP,
            WRAP,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // As Banner. NOT_FOCUSABLE keeps the keyboard with the app
            // underneath; NOT_TOUCH_MODAL lets every tap outside the bubble
            // through to it. The bubble itself still receives its own touches —
            // these flags are about everywhere it is not.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = context.dp(HIGH)
        }

    /**
     * The bubble.
     *
     * A TextView rather than a drawable: this repository does not track binaries
     * it cannot review, and an icon would be one. A letter is legible at this
     * size and says which app is asking.
     */
    val view: View =
        TextView(context).apply {
            text = context.getString(R.string.chat_head_label)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.chat_head)
            val side = context.dp(SIDE)
            minWidth = side
            minHeight = side
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            contentDescription = context.getString(R.string.chat_head_description)
        }

    /**
     * Follow a finger, and tell a tap from a drag.
     *
     * The threshold is the point of it. A bubble that opens on any touch cannot
     * be moved, and one that moves on any touch cannot be opened; a few
     * density-independent pixels of travel separates the two intentions well
     * enough that neither is surprising.
     *
     * SuppressLint("ClickableViewAccessibility"): the view is not clickable and
     * has no click listener to call — the tap is decided here, from the same
     * gesture that might have been a drag, and performClick would fire on drags
     * too. The content description above is what a screen reader reads.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun follow(windows: WindowManager) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var travelled = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    travelled = 0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // Largest travel rather than final travel: a finger that
                    // goes out and comes back moved, and treating that as a tap
                    // opens the agent when somebody was putting the bubble
                    // where it was already.
                    travelled = maxOf(travelled, abs(dx) + abs(dy))
                    params.x = startX + dx.roundToInt()
                    params.y = startY + dy.roundToInt()
                    // Answering rather than throwing: a bubble that cannot be
                    // moved is a bubble in the wrong place, not a broken turn.
                    runCatching { windows.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (travelled < view.context.dp(SLOP)) onOpen()
                    true
                }

                else -> false
            }
        }
    }

    private companion object {
        const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT

        /** How far down it starts. Clear of a status bar and of a notification. */
        const val HIGH = 120

        /** How big. A comfortable target without covering what is underneath. */
        const val SIDE = 52

        const val TEXT_SP = 20f

        /**
         * Travel that makes a touch a drag rather than a tap.
         *
         * Deliberately larger than the platform's own touch slop: this is a
         * small target that people press with a thumb while walking, and an
         * accidental open costs a context switch out of whatever they were
         * doing.
         */
        const val SLOP = 12
    }
}

/** Density-independent pixels as this display's pixels. */
private fun Context.dp(of: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        of.toFloat(),
        resources.displayMetrics,
    ).roundToInt()
