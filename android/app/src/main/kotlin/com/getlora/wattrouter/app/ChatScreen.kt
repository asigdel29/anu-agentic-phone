// ChatScreen.kt — the conversation, and the field under it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// A `when` over five row types and nothing else. The fold in Transcript.kt
// exists so this stays that simple: every question about whether a fragment
// opens a bubble or extends one was answered there, off the main thread and
// under test, rather than here in a function that also has to lay one out.
//
// Keyed on Row.id, which is why those ids come from a counter. A LazyColumn
// keyed on position re-creates every item below an insert, and with a row that
// grows a character at a time that reads as the whole conversation flickering.

package com.getlora.wattrouter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Decision
import com.getlora.wattrouter.Row

/**
 * The conversation.
 *
 * @param onSend what the person typed. Blank text is the driver's to refuse,
 *   not this function's — one place deciding what counts as a message.
 * @param onInterrupt stop the turn in flight.
 */
@Composable
fun ChatScreen(
    rows: List<Row>,
    isRunning: Boolean,
    routing: Decision?,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val scroll = rememberLazyListState()

    // Follow the bottom when the reader is already at it, and leave them alone
    // when they have scrolled up.
    //
    // Not `if (isRunning)`, which is what this was first: the last row of a
    // turn — the end of an answer, or the reason it failed — is appended as the
    // turn ends, so gating on isRunning leaves exactly the row that matters
    // below the fold. Found by driving it on an emulator and being unable to
    // see a 401 the transcript had recorded correctly.
    LaunchedEffect(rows.size) {
        val lastSeen = scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (rows.isNotEmpty() && lastSeen >= rows.size - 2) {
            scroll.animateScrollToItem(rows.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        routing?.let { RoutingPanel(it) }

        LazyColumn(
            state = scroll,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.id }) { Line(it) }
        }

        LayoutRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Ask something") },
                modifier = Modifier.weight(1f),
            )
            // One button, two jobs. A separate stop button would be dead most
            // of the time, and the two states are never both available.
            Button(
                onClick = {
                    if (isRunning) {
                        onInterrupt()
                    } else {
                        onSend(typed)
                        typed = ""
                    }
                },
            ) {
                Text(if (isRunning) "Stop" else "Send")
            }
        }
    }
}

@Composable
private fun Line(row: Row) {
    when (row) {
        is Row.Said -> Text(row.text, style = MaterialTheme.typography.bodyLarge)

        is Row.Answered -> Column {
            // The model, above what it said, and only once it has announced
            // itself: naming it before it speaks would label an empty bubble.
            row.model?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
            Text(row.text, style = MaterialTheme.typography.bodyMedium)
        }

        // The name appears while the tool runs and the result fills in under
        // it, so ten seconds of work is not ten seconds of nothing.
        is Row.Used -> Column {
            Text(
                if (row.result == null) "${row.tool}…" else row.tool,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            row.result?.let {
                Text(it.lineSequence().take(RESULT_LINES).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        is Row.Failed -> Text(
            row.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is Row.Interrupted -> Text("stopped", style = MaterialTheme.typography.labelSmall)
    }
}

/** Why this turn went where it went. The question the repository is about. */
@Composable
private fun RoutingPanel(decision: Decision) {
    val score = decision.score?.let { " · %.2f".format(it) }.orEmpty()
    Text(
        "${decision.tier} · ${decision.reason}$score",
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Lines of a tool's result shown before it is cut off. */
private const val RESULT_LINES = 6
