// ReadinessScreen.kt: the checklist, and what the agent can actually see.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Re-read on resume rather than on first composition. The whole point of a
// checklist over a wizard is that it is right when you look at it, and the
// moment somebody looks is the moment they come back from Settings. Built once
// and never re-read, it would show the state as it was before they went,
// which is the lie a wizard tells, arrived at by accident.
//
// It ends by showing what the model would see. "The switch is on" is a claim
// and "this is what it can read" is evidence, and it is the moment somebody
// decides whether they are comfortable with any of it, a decision nobody can
// make from a list of green ticks.

package com.getlora.wattrouter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Needed
import com.getlora.wattrouter.Readiness

/**
 * What is switched on, what is not, and where to go about it.
 *
 * @param seeing the first lines of a real reading, or null while there is
 *   nothing to read. Shown rather than described.
 * @param onOpen take the person to the screen a row names. Null for the row
 *   that names a menu instead, because there is no intent for one.
 */
@Composable
internal fun ReadinessScreen(
    readiness: Readiness,
    seeing: String?,
    onOpen: (Needed) -> Unit,
    onCarryOn: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Before it can use the phone", style = MaterialTheme.typography.headlineSmall)

        readiness.steps.forEach { step -> NeededRow(step, onOpen) }

        // Skippable on purpose. Blocking the conversation would make the app
        // unusable to somebody who wants to talk and never intends to let it
        // drive anything; the phone tools are one capability among several.
        TextButton(onClick = onCarryOn) {
            Text(if (readiness.canDrive) "Start talking" else "Carry on without it")
        }

        if (seeing != null) {
            Text("This is what it can see right now", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    seeing,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NeededRow(step: Needed, onOpen: (Needed) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The mark before the words, so a column of them can be read
                // down without reading any of the sentences.
                Text(
                    (if (step.isOn) "on   " else if (step.isRequired) "off  " else "-    ") +
                        step.what,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!step.isOn) {
                    Text(step.where, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!step.isOn) {
                TextButton(onClick = { onOpen(step) }) { Text("Open") }
            }
        }
    }
}
