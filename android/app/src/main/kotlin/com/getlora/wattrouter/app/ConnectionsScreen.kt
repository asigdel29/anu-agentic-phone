// ConnectionsScreen.kt: the servers somebody has connected, and adding one.
//
// History
//   2026-08-11  A. Sigdel  Created with #596.
//
// Contents
//   ConnectionsScreen  The list, the form, and what each server is doing.
//
// A server's tools are tools the model will call, so this is a permission
// screen wearing a list's clothes, and it says so at the top rather than in a
// row somebody would have to already understand.
//
// Every row says what happened to that server, because `Reached` keeps it. A
// server with no tools and one that could not be asked look identical
// otherwise, and the second is the one somebody can fix.
//
// The form refuses rather than warns, and the core composes the sentence: this
// screen draws whatever `refusing` said, so the rule lives in one place.

package com.getlora.wattrouter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Reached

/**
 * What a server is doing, in one line under its name.
 *
 * The count rather than the names: a server offering twenty tools would push
 * the next server off the screen, and the names are already in front of the
 * model where they matter.
 */
internal fun standing(reached: Reached): String = when {
    reached.why != null -> "could not be reached: ${reached.why}"
    reached.tools.isEmpty() -> "connected, and offering nothing"
    reached.tools.size == 1 -> "1 tool"
    else -> "${reached.tools.size} tools"
}

/**
 * The servers, and a form to add one.
 *
 * @param connected what each saved server answered, in the order saved.
 * @param onAdd asked to save a pair. Answers the reason it was refused, or null
 *   when it was saved, which is [Connections.add]'s shape so the screen never
 *   holds a second copy of the rule.
 * @param onForget take one away.
 * @param onDone leave.
 */
@Composable
fun ConnectionsScreen(
    connected: List<Reached>,
    onAdd: (String, String) -> String?,
    onForget: (String) -> Unit,
    onDone: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Connected servers", style = MaterialTheme.typography.headlineSmall)

        // The sentence that makes this a permission screen. Not a row somebody
        // has to already understand: what a server grants is that its
        // description of a tool becomes model input on every turn.
        Text(
            "A server's tools are tools the agent can run, and what a server says " +
                "they do is written by whoever runs it. Connect ones you trust.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(connected, key = { it.connection.label }) { reached ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(reached.connection.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            reached.connection.endpoint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(standing(reached), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onForget(reached.connection.label) }) {
                        Text("Forget")
                    }
                }
            }
        }

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("Address") },
            placeholder = { Text("https://") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        // The core's sentence, not one composed here. A second copy of the rule
        // is a copy that disagrees with the one that decides.
        refused?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onDone) { Text("Done") }
            Button(onClick = {
                refused = onAdd(label, endpoint)
                if (refused == null) {
                    label = ""
                    endpoint = ""
                }
            }) {
                Text("Connect")
            }
        }
    }
}
