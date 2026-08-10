// Asked.kt — the question, over whatever the agent is about to touch.
//
// History
//   2026-08-10  A. Sigdel  Created with #556.
//
// Contents
//   Asked  A question with two answers, as an accessibility overlay.
//
// A sibling of Banner and deliberately not a part of it. The banner reports and
// this asks, and the difference shows in one flag: the banner sets
// NOT_TOUCH_MODAL so a tap falls through to the app the agent is aiming at, and
// a question must not — a tap meant for Allow that lands in the app underneath
// is the failure the whole feature exists to prevent.
//
// Modality is safe here for a reason worth writing down, because it stops being
// true if tapping ever changes. DrivingService.click calls
// performAction(ACTION_CLICK) on a node rather than dispatchGesture, and an
// accessibility action does not go through touch dispatch. So an overlay that
// swallows every touch still cannot block the agent's own taps. A move to
// gestures moves this decision with it.
//
// It is also unanswerable by the model, and that is not an accident of the view
// hierarchy. An accessibility overlay is absent from the node tree — #526 found
// it the hard way, three tests failing against a feature that worked — so
// read_screen cannot see this and tap cannot reach it. The thing being asked
// about cannot answer.

package com.getlora.wattrouter.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A question with two answers.
 *
 * @param onAnswer called once, on the main thread, with what was chosen. Never
 *   called twice: both buttons are disabled the moment either is pressed, so a
 *   double tap on a slow frame cannot answer a second question that has not
 *   been asked yet.
 */
internal class Asked(
    context: Context,
    question: String,
    private val onAnswer: (Boolean) -> Unit,
) {
    private var answered = false

    private fun answer(yes: Boolean) {
        if (answered) return
        answered = true
        onAnswer(yes)
    }

    private fun button(context: Context, label: Int, yes: Boolean) =
        Button(context).apply {
            text = context.getString(label)
            setOnClickListener { answer(yes) }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP, 0f)
        }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BACKING)
        setPadding(PAD, PAD, PAD, PAD)

        addView(
            TextView(context).apply {
                text = question
                setTextColor(Color.WHITE)
                textSize = SIZE
            },
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // Right, where a dialog's buttons are on this platform. The
                // overlay is the agent's and the convention is the phone's.
                gravity = Gravity.END
                // Refuse first, in reading order. The one that does nothing is
                // the one somebody reaching for it in a hurry should find.
                addView(button(context, R.string.asked_no, yes = false))
                addView(button(context, R.string.asked_yes, yes = true))
            },
        )
    }

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val SIZE = 16f
        const val PAD = 32

        /** As Banner: it sits over somebody else's design and brings its own
         *  contrast, because it has to be legible on all of them. */
        const val BACKING = 0xF21A1A1AL.toInt()
    }
}
