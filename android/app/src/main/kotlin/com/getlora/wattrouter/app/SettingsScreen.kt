// SettingsScreen.kt: the door #641 says this application does not have.
//
// History
//   2026-08-13  A. Sigdel  Created with #641.
//
// The connections screen could be reached exactly once: `connecting` was set
// from the checklist and nowhere else, and the checklist stops appearing once
// the phone is ready. So a merged feature became unusable on the second launch,
// quietly, and every setting added since went on the conversation for want of
// anywhere else.
//
// A list of rows rather than a navigation library. There are at most four
// destinations, and a library is a version to keep in step with AGP for what a
// `when` over an enum already does. Nothing here is a graph.
//
// It holds what does not belong on the conversation rather than everything.
// Modes.kt argues for putting a setting where it acts, and the modes row and
// the identity row still follow that: they are one line each and they are read
// while somebody is deciding what to send. What lands here is what cannot be a
// line, which so far is a list of servers and a checklist somebody passed
// through, and next is the public half of a key and a terminal's scrollback.

package com.getlora.wattrouter.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Where somebody can go from here.
 *
 * An enum rather than a route string, so a destination that does not exist is a
 * compile error rather than a screen nobody reaches, which is the failure #641
 * is about.
 */
enum class Where {
    /** The conversation, which is where a launch lands once the phone is ready. */
    Conversation,

    /** The list below. */
    Settings,

    /** Servers whose tools join the ToolBox. */
    Connections,

    /**
     * What is not switched on yet.
     *
     * Shown by itself at launch while anything required is off, and reachable
     * from here afterwards. ReadinessScreen re-reads on resume, which is what
     * makes it right to come back to as well as to pass through.
     */
    Readiness,

    /** The public half of the key this phone pushes with, and the hosts it pinned. */
    SigningKey,

    /** The provider key, and the only way back to the sign-in screen. */
    Credential,
}

/**
 * One row, and where it goes.
 *
 * @property label what it says. A noun, since it names a place rather than an
 *   action: everything here opens something.
 * @property beneath one line of what is there, or null. It is what makes a list
 *   of four nouns readable, and it is where a count belongs.
 */
data class Destination(val label: String, val beneath: String?, val where: Where)

/** The list of places, and the way back. */
@Composable
fun SettingsScreen(destinations: List<Destination>, onGo: (Where) -> Unit, onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        destinations.forEach { destination ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGo(destination.where) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(destination.label, style = MaterialTheme.typography.bodyLarge)
                    destination.beneath?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text("›", style = MaterialTheme.typography.titleMedium)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = onDone) { Text("Back to the conversation") }
    }
}
