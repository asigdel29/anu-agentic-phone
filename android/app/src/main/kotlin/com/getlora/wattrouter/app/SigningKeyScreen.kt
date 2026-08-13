// SigningKeyScreen.kt: the public half of the key, and the hosts it has met.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// The last piece of unit 6, and it had nowhere to go until #641: the only other
// settings screen this application had could be reached exactly once.
//
// One line to copy and a list of hosts to read. There is no field to paste a
// key into, and that absence is the design rather than an omission:
// docs/decisions/pushing-from-a-phone.md rejected the paste because a key that
// arrives by paste has been in a clipboard, and on this phone the clipboard is
// something the agent can read. What is offered instead is a key made here that
// has never been anywhere a tool could reach.
//
// Copying the public half to the clipboard is safe in the way pasting the
// private half is not: it is the half meant to be given away, and it is going
// somewhere the person is about to paste it anyway.
//
// The pinned hosts are shown rather than managed. A host whose key changed is
// refused in words and never with a prompt, which is that record's other
// decision, so there is no button here that would clear one: somebody who knows
// the host was rebuilt says so by removing the line, and doing that from a
// phone screen at the moment of the refusal is exactly the dialog it argues
// against.

package com.getlora.wattrouter.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The key, the hosts, and the way back.
 *
 * @param shown the public half, or null until one has been made. Null is the
 *   ordinary first state rather than a failure: nothing makes a key at startup,
 *   because a key nobody has authorised is a key that does nothing.
 * @param hosts one line per host pinned, as `git::trust` wrote them.
 * @param onMake make one, and answer the public half or null if the platform
 *   refused. Called from a press rather than from composition, so somebody who
 *   never opens this screen never has a key.
 */
@Composable
fun SigningKeyScreen(
    shown: String?,
    hosts: List<String>,
    onMake: () -> String?,
    onDone: () -> Unit,
) {
    var line by remember { mutableStateOf(shown) }
    var refused by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("Signing key", style = MaterialTheme.typography.titleMedium)

        Text(
            "This phone pushes with a key it made itself. Only the half below " +
                "ever leaves it. Add that line to a forge as an authorised key " +
                "for the repository you want to push to.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        when (val made = line) {
            null -> {
                Button(onClick = {
                    line = onMake()
                    refused = line == null
                }) {
                    Text("Make one")
                }
                if (refused) {
                    Text(
                        "This phone would not make a key. Nothing was stored, " +
                            "so pressing again is safe.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            else -> {
                // Selectable would be better and is not enough: the line is
                // about seven hundred characters and selecting it by dragging on
                // a phone is the part somebody gets wrong.
                Text(
                    made,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Button(onClick = { copy(context, made) }) { Text("Copy it") }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("Hosts this phone has met", style = MaterialTheme.typography.titleSmall)
        Text(
            "A host is remembered the first time it answers. One that answers " +
                "with a different key afterwards is refused, and nothing is sent.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        if (hosts.isEmpty()) {
            Text("None yet.", style = MaterialTheme.typography.labelSmall)
        } else {
            hosts.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        TextButton(onClick = onDone) { Text("Back") }
    }
}

/** Put the public half where the person is about to paste it. */
private fun copy(context: Context, line: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("ssh public key", line))
}
