// TurnScreen.kt: what a send does, and who it says did it.
//
// History
//   2026-08-13  A. Sigdel  Created with #712.
//
// The two rows that were on the conversation, behind the door #693 built. #602
// counted the accumulation: ChatScreen was eight stacked things and three of
// them were settings, and it predicted "a fourth arrives with every unit".
//
// Modes.kt argues a setting belongs where it acts and that argument is not
// wrong; it is outweighed. A screen where three of eight things are settings is
// one where the conversation is the minority, and the cost of moving is one tap
// on a control most people set once. Signing.kt's second reason, that the only
// other settings screen could be reached exactly once, expired with #693.
//
// One screen for both rather than two destinations. They are the same question
// asked twice: what happens when I press send, and what it will be signed as.
// A list of two doors each leading to one line is a list that exists to have
// been made.

package com.getlora.wattrouter.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * How involved somebody is, and who commits.
 *
 * Both rows unchanged from the conversation, which is deliberate: this change
 * moves them and does not redesign them. #602 refuses doing both at once,
 * because structural and aesthetic edits look identical in a diff.
 *
 * The stores rather than values and callbacks, unlike every other screen here.
 * They are the truth and this is now the only place either is written, so
 * passing them through the Activity would be a hoist to nowhere.
 */
@Composable
fun TurnScreen(
    modes: Modes,
    signing: Signing,
    onDone: () -> Unit,
) {
    // Held in composition as well as in the store, which is the reason the
    // conversation held it before this: a chip has to repaint when it is tapped
    // and SharedPreferences is not observable. The store stays the truth, and
    // it is what the turn loop reads, per action, through a lambda.
    //
    // Read on entry rather than remembered across visits, because this screen
    // is built and destroyed per visit and the store may have been written by
    // something else in between.
    var mode by remember { mutableStateOf(modes.now) }
    var who by remember { mutableStateOf(signing.who) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("A turn", style = MaterialTheme.typography.titleMedium)

        Text(
            "What happens when you send something, and who a commit from this " +
                "phone says made it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        ModeRow(mode) {
            mode = it
            modes.now = it
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        IdentityRow(who) {
            who = it
            signing.who = it
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        TextButton(onClick = onDone) { Text("Back") }
    }
}
