// ChatScreen.kt: the conversation, and the field under it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-11  A. Sigdel  Press to talk, into the field rather than into the
//                          send it would otherwise be, #659.
//
// A `when` over five row types and nothing else. The fold in Transcript.kt
// exists so this stays that simple: every question about whether a fragment
// opens a bubble or extends one was answered there, off the main thread and
// under test, rather than here in a function that also has to lay one out.
//
// Keyed on Row.id, which is why those ids come from a counter. A LazyColumn
// keyed on position re-creates every item below an insert, and with a row that
// grows a character at a time that reads as the whole conversation flickering.
//
// The microphone writes into the field and nothing else, which is the one
// decision in this file that is not layout. `spokenInto` below is where it is
// made, and #659 is where it was argued.

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Acted
import com.getlora.wattrouter.Autonomy
import com.getlora.wattrouter.Decision
import com.getlora.wattrouter.Heard
import com.getlora.wattrouter.Row
import com.getlora.wattrouter.Who
import kotlinx.coroutines.launch

/**
 * The conversation.
 *
 * @param onSend what the person typed. Blank text is the driver's to refuse,
 *   not this function's: one place deciding what counts as a message.
 * @param onInterrupt stop the turn in flight.
 * @param mode how involved this person wants to be. Above the field rather
 *   than behind a settings screen: it changes what the next send does, so it
 *   belongs where the next send is typed.
 */
@Composable
fun ChatScreen(
    /**
     * Open the settings.
     *
     * The one control here that goes somewhere else, and the reason #641 could
     * be closed: before it, the connections screen was reachable exactly once
     * and every setting since landed on this screen for want of anywhere else.
     */
    onSettings: () -> Unit,
    rows: List<Row>,
    /**
     * What the last turn did to the phone, oldest first.
     *
     * Drawn under the conversation and only while nothing is running. A card
     * that grew mid-turn would move under the eye of somebody reading the
     * answer, and #598 is about review after the fact.
     */
    replay: List<Acted>,
    isRunning: Boolean,
    routing: Decision?,
    mode: Autonomy,
    /**
     * Who a commit from this phone would say made it, or null while nobody has
     * said.
     *
     * Here rather than behind a settings screen for [ModeRow]'s reason, and for
     * a second one #641 records: the only other settings screen this
     * application has can be reached exactly once.
     */
    who: Who?,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onMode: (Autonomy) -> Unit,
    onWho: (Who?) -> Unit,
    /**
     * Listen once, and answer with what was said or why nothing was.
     *
     * Suspends for as long as somebody speaks, so the button it is behind
     * refuses a second press while one is in flight: two calls must not overlap.
     */
    onListen: suspend () -> Heard,
) {
    var typed by remember { mutableStateOf("") }

    // Both belong to the microphone button and to nothing else, so they live
    // beside it. `typed` stays here for the same reason: hoisting a field's
    // contents into the Activity so that one caller can write to it once would
    // put the composer's state two files from the composer.
    var listening by remember { mutableStateOf(false) }
    var unheard by remember { mutableStateOf<String?>(null) }
    val speaking = rememberCoroutineScope()
    val scroll = rememberLazyListState()

    // Follow the bottom when the reader is already at it, and leave them alone
    // when they have scrolled up.
    //
    // Not `if (isRunning)`, which is what this was first: the last row of a
    // turn (the end of an answer, or the reason it failed) is appended as the
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
        // Above the transcript rather than below the composer: it is read once
        // and then never again by most people, and everything under the
        // transcript is read while somebody decides what to send.
        LayoutRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onSettings) { Text("Settings") }
        }

        routing?.let { RoutingPanel(it) }

        LazyColumn(
            state = scroll,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.id }) { Line(it) }
        }

        if (!isRunning) ReplayCard(replay)

        ModeRow(mode, onMode)

        // Under the modes rather than over them. What a send does is the more
        // often read of the two, and this is a line most people never act on.
        IdentityRow(who, onWho)

        // Why the last press produced nothing, until the next one. Heard.Silence
        // carries a whole sentence for the person who spoke, and a microphone
        // that did nothing and said nothing is what it was written against.
        unheard?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        LayoutRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Ask something") },
                modifier = Modifier.weight(1f),
            )

            // Into the field, never into onSend, and that is the whole design.
            // TurnDriver.send appends and starts the turn in one call, with no
            // draft and no undo, so a mishearing that went straight there is an
            // action before anybody read it, and in Auto mode one the agent
            // then carries out. #601 names that as where this meets #595.
            //
            // Refuses a second press while one is open, because listen() holds
            // the microphone until somebody stops speaking, and the label says
            // which state it is in rather than leaving that to be guessed.
            Button(
                onClick = {
                    unheard = null
                    listening = true
                    speaking.launch {
                        try {
                            when (val heard = onListen()) {
                                is Heard.Words -> typed = spokenInto(typed, heard.said)
                                is Heard.Silence -> unheard = heard.why
                            }
                        } finally {
                            // In a finally, because a press that threw would
                            // otherwise leave the button disabled for good: one
                            // failure would become the failure of every press
                            // after it, with nothing on screen saying why.
                            listening = false
                        }
                    }
                },
                enabled = !listening,
            ) {
                Text(if (listening) "Listening" else "Speak")
            }

            // One button, two jobs. A separate stop button would be dead most
            // of the time, and the two states are never both available.
            Button(
                onClick = {
                    if (isRunning) {
                        onInterrupt()
                    } else {
                        onSend(typed)
                        typed = ""
                        unheard = null
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

/**
 * What the field holds once a transcript arrives in it.
 *
 * Appended rather than substituted. The field may already hold something
 * somebody typed, and losing it is the same class of loss the whole approach
 * exists to avoid, only smaller: a press of the microphone should never be a
 * way to delete a sentence.
 *
 * One space between the two, and none at either end. A transcript is handed
 * back without surrounding space, an empty field must not produce a leading
 * one, and a field ending in a space must not produce two.
 */
internal fun spokenInto(typed: String, said: String): String =
    listOf(typed.trim(), said.trim()).filter { it.isNotEmpty() }.joinToString(" ")

/** Lines of a tool's result shown before it is cut off. */
private const val RESULT_LINES = 6
