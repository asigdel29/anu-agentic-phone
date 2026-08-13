// Aloud.kt: whether the phone reads an answer out, kept between launches.
//
// History
//   2026-08-13  A. Sigdel  Created with #709.
//
// Modes.kt's shape, and off by default, which is the decision here. A phone that
// starts talking because a turn finished is one somebody silences by
// uninstalling, and there is no way to ask first: the first answer would already
// have been read aloud by the time the question arrived.
//
// A boolean rather than a mode with three values. Listening has a button because
// a press is the whole of the consent; speaking has a switch because the consent
// has to be given before the thing happens rather than by doing it.

package com.getlora.wattrouter.app

import android.content.Context

/** Whether the phone reads answers out. */
class Aloud(context: Context) {
    private val store = context.getSharedPreferences("aloud", Context.MODE_PRIVATE)

    /**
     * On or off.
     *
     * # Rely
     * Read from the composition when a turn ends, on the main thread.
     * SharedPreferences is safe from any and answers from memory.
     */
    var on: Boolean
        get() = store.getBoolean(KEY, false)
        set(value) = store.edit().putBoolean(KEY, value).apply()

    private companion object {
        const val KEY = "speak-answers"
    }
}
