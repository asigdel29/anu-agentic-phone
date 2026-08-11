// Border.kt: a frame around whatever the agent is driving.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// The banner says what is happening and carries the stop. It does not say
// *where*: it sits at the top of the display whether the agent is driving the
// app underneath it or the one behind that. Somebody glancing at the phone sees
// their banking app with a strip on it and no indication that the screen itself
// is the thing being operated.
//
// So this is the screen-share indicator's answer, and it is chosen over the
// other two in #598 for what it costs. Highlighting the node about to be
// touched says more and needs bounds threaded through a path that already races
// a screen that can move; a replay card says most and needs a capture per
// action. A frame says one thing, unmistakably, and needs nothing that is not
// already here.
//
// It draws nothing in the middle, which is the whole of the implementation:
// four edges as a shape with a transparent centre, so there is no bitmap the
// size of the display and nothing to redraw when the app underneath scrolls.
//
// What it cannot do is appear over a FLAG_SECURE window, and neither can the
// banner. That is the platform's rule rather than a choice here, and it means
// the indicator is absent on exactly the screens somebody would most want one.
// docs/decisions/what-android-allows.md records it under overlays.

package com.getlora.wattrouter.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** A frame drawn around the display while a turn is driving. */
internal class Border(context: Context) {

    /**
     * Four edges and nothing else.
     *
     * A stroked rectangle with no fill: the centre is not drawn rather than
     * drawn transparent, so nothing composites over the app underneath and a
     * scroll behind it costs nothing.
     */
    val view: View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(THICKNESS, EDGE)
        }
        // Belt and braces with the window flags. A view that is not clickable
        // and takes no focus cannot swallow a tap even if a later change to
        // those flags would have let it.
        isClickable = false
        isFocusable = false
    }

    private companion object {
        /**
         * Thick enough to read at a glance, thin enough not to cover a control.
         *
         * The edge of a screen is where a scrollbar, a back gesture and a
         * navigation bar all live, and a frame that hid one of them would make
         * the app underneath harder to use while the agent used it.
         */
        const val THICKNESS = 8

        /**
         * The one colour that means this and nothing else on a phone.
         *
         * Not the theme's, deliberately: this is drawn over somebody else's
         * application and has to be legible against whatever they chose, which
         * is the reason chat_head.xml gives for bringing its own ground.
         */
        val EDGE = Color.parseColor("#FF6D00")
    }
}
