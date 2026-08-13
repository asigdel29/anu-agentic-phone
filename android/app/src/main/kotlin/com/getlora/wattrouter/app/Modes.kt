// Modes.kt: where the autonomy mode is kept, and how it is picked.
//
// History
//   2026-08-10  A. Sigdel  Created with #558.
//
// Contents
//   modeFrom  A saved string back into a mode.
//   Modes     The setting.
//   ModeRow   The three of them, to choose between.
//
// Read on every call rather than held, which is what Confirmed relies on:
// somebody who turns Ask off has stopped wanting to be asked and should not go
// on being asked until the turn ends. SharedPreferences is a map in memory
// after its first load, so per-action is a lookup rather than a read of a file.
//
// It defaults to Auto, which is what this app has always done. A default of Ask
// would be safer for exactly one turn and then be switched off by everybody, so
// it is not the safer choice: it is the one that teaches people to dismiss the
// question without reading it.

package com.getlora.wattrouter.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Autonomy

/**
 * A saved string back into a mode.
 *
 * Anything unrecognised is [Autonomy.AUTO] rather than a failure: a mode
 * written by a later build of this app, or a preferences file somebody edited,
 * should leave the phone working the way it did.
 */
internal fun modeFrom(saved: String?): Autonomy =
    Autonomy.entries.firstOrNull { it.name == saved } ?: Autonomy.AUTO

/** How involved this person wants to be. */
class Modes(context: Context) {
    private val store = context.getSharedPreferences("autonomy", Context.MODE_PRIVATE)

    /**
     * The mode now.
     *
     * # Rely
     * Read once per action from the turn loop, on whatever thread the tool is
     * running on. SharedPreferences is safe from any and answers from memory.
     */
    var now: Autonomy
        get() = modeFrom(store.getString(KEY, null))
        set(value) = store.edit().putString(KEY, value.name).apply()

    private companion object {
        const val KEY = "mode"
    }
}

/** What each one is called. */
internal fun labelOf(mode: Autonomy): Int = when (mode) {
    Autonomy.PLAN -> R.string.mode_plan
    Autonomy.AUTO -> R.string.mode_auto
    Autonomy.ASK -> R.string.mode_ask
}

/** What choosing it means, which is the part worth reading. */
internal fun meaningOf(mode: Autonomy): Int = when (mode) {
    Autonomy.PLAN -> R.string.mode_plan_means
    Autonomy.AUTO -> R.string.mode_auto_means
    Autonomy.ASK -> R.string.mode_ask_means
}

/**
 * The modes a person can actually pick.
 *
 * All three, since #595. Plan was missing while it behaved exactly like Auto,
 * because a picker offering a setting that does nothing teaches somebody the
 * picker does not work, and that is a lesson they keep after it starts working.
 *
 * Ordered by how involved somebody is, least first, which is the order the
 * sentence under the row reads in.
 */
internal val shown = listOf(Autonomy.AUTO, Autonomy.PLAN, Autonomy.ASK)

// The rule this file argues, that a setting belongs where it acts, held for
// this row until #712 and was then outweighed rather than shown wrong. Three of
// the conversation's eight things were settings; TurnScreen has the argument.
// The rule still stands everywhere else, and the microphone and the read-aloud
// switch are both still on the conversation because of it.

/**
 * The modes, and a sentence for whichever is chosen.
 *
 * The sentence is not decoration. "Ask" tells nobody what they are choosing,
 * and one line under the row costs less than a screen somebody has to go and
 * find.
 */
@Composable
fun ModeRow(now: Autonomy, onPick: (Autonomy) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { mode ->
                FilterChip(
                    selected = mode == now,
                    onClick = { onPick(mode) },
                    label = { Text(stringResource(labelOf(mode))) },
                )
            }
        }
        Text(
            stringResource(meaningOf(now)),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
