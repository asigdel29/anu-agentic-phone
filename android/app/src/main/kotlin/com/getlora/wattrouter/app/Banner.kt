// Banner.kt — what the agent shows over the app it is driving.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Banner  A strip across the top, and the way to stop.
//
// Small on purpose. The person is looking at the app being driven, not at this;
// its job is to make it obvious that something else is moving the screen, and to
// be one tap from ending that. A transcript here would be a second thing to read
// while watching a first.
//
// Untouchable except for the button, and that is not a nicety. An overlay that
// swallows a tap makes the agent's own actions fail in a way that looks like the
// app refusing them — which is the hardest kind of failure to attribute, because
// nothing in the transcript is wrong.

package com.getlora.wattrouter.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A strip across the top of whatever the agent is driving. */
internal class Banner(context: Context, private val onStop: () -> Unit) {

    /** What is on screen. Built once; the text changes, the view does not. */
    private val saying = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = SIZE
        // Weight 1 against the button's 0: a long sentence takes the space
        // that is left rather than pushing the stop control off the edge.
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BACKING)
        setPadding(PAD, PAD, PAD, PAD)

        addView(saying)
        addView(
            Button(context).apply {
                text = context.getString(R.string.banner_stop)
                // The only touchable thing here. Everything else lets a tap
                // through to the app underneath, which the agent is aiming at.
                setOnClickListener { onStop() }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP, 0f)
            },
        )
    }

    /**
     * Say what is happening.
     *
     * @param what the person's own words rather than a tier name or a tool
     *   name — the same call TurnService.begin makes, and for the same reason:
     *   "Working" tells them nothing they did not know when they pressed send.
     */
    fun say(what: String) {
        saying.text = what
    }

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val SIZE = 14f
        const val PAD = 24

        /** Dark and opaque: it sits over somebody else's design and has to be
         *  legible on all of them, so it brings its own contrast. */
        const val BACKING = 0xEE1A1A1AL.toInt()
    }
}
