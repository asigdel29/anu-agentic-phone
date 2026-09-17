// ScrollbackScreen.kt: what the terminal ran, most recent last.
//
// History
//   2026-09-17  A. Sigdel  Created with #713.
//
// The third step #602 asked for. A terminal is the first tool that produces
// something a transcript row cannot hold, and #693's door is where a thing
// like that goes. The transcript answers "what did the agent say it did";
// this answers "what has it actually run on this phone", which outlives the
// turn and the row scrolled past, and that is the one thing a terminal is for
// that #713 says a chat row cannot be.
//
// The gate's prompt is not here, on purpose. AndroidConsent raises its dialog
// through DrivingService's overlay, and a Compose surface that also asked
// would be a second way of asking, which #673 forbids. What runs is asked
// where it always was; this screen only shows what happened afterwards.
//
// Worded by RunCommandTool.say rather than a wording of its own. That is the
// sentence the transcript showed when the command ran, and a second wording is
// a second place to keep in step with Ran.

package com.getlora.wattrouter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.KeptCommand
import com.getlora.wattrouter.RunCommandTool

/**
 * What the terminal has run, and what each command came back.
 *
 * @param commands most recent last, as [com.getlora.wattrouter.Scrollback]
 *   keeps them. Read at composition rather than collected, for the replay
 *   card's reason: the list changes only while a turn runs, and a screen whose
 *   body moves under the eye of somebody reading it is a screen nobody reads.
 *   The screen is built per visit, so this is what there was on the way in.
 * @param onDone back to the settings list.
 */
@Composable
fun ScrollbackScreen(commands: List<KeptCommand>, onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("What ran", style = MaterialTheme.typography.titleMedium)

        Text(
            "Every command the terminal has run on this phone, most recent " +
                "last, with what each came back.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        // A sentence rather than a blank screen, for unreadable's reason:
        // "nothing" has to be distinguishable from "broken".
        if (commands.isEmpty()) {
            Text(
                "Nothing yet. Commands the agent runs are kept here.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(commands) { Entry(it) }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = onDone) { Text("Back") }
    }
}

/**
 * One command, above what it came back.
 *
 * The command in the transcript row's face, monospace, so a command reads as
 * a thing that was typed and what follows as what answered it.
 */
@Composable
private fun Entry(kept: KeptCommand) {
    Column {
        Text(
            kept.command,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            RunCommandTool.say(kept.ran),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
