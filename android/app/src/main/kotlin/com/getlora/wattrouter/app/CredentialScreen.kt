// CredentialScreen.kt: the way back to signing in.
//
// History
//   2026-08-13  A. Sigdel  Created with #512.
//
// A key can be well-formed and wrong. Startup.CoreRefused already says so in
// those words, and the app had no route back anyway: Core.open cannot validate
// a credential, because the key is only used at request time, so a wrong one
// gives Startup.Ready and the conversation renders. SignInScreen is reachable
// from NoCredential and CoreRefused alone, and neither is reachable once
// anything is stored.
//
// Credential.forget existed and was called from nowhere in the application.
// This is its caller. Until #641 there was nowhere to put one.
//
// Two taps rather than one. Forgetting is recoverable, since somebody who has
// the key can type it again, and it is not undoable by pressing back: the
// keystore entry is discarded with the ciphertext, which is Credential.forget's
// own decision and the right one. A single button on a settings list is a
// button somebody hits while scrolling.

package com.getlora.wattrouter.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

/**
 * Forget the provider key, so the sign-in screen can be reached again.
 *
 * @param onForget discard it. Answers false if either half of the discard
 *   failed, which leaves a stored key rather than a half-forgotten one.
 * @param onSignIn go back to the start, once there is nothing stored.
 */
@Composable
fun CredentialScreen(onForget: () -> Boolean, onSignIn: () -> Unit, onDone: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Provider key", style = MaterialTheme.typography.titleMedium)

        Text(
            "A key can be the right shape and still be the wrong key. Nothing " +
                "here can tell until a turn is answered with a 401, and at that " +
                "point the only way to type a different one is this.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        if (!asking) {
            Button(onClick = { asking = true }) { Text("Forget the key") }
        } else {
            Text(
                "The key is discarded along with the one that would decrypt it, " +
                    "so this cannot be undone by going back. Your conversation " +
                    "and what the assistant remembers are not touched.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(onClick = {
                if (onForget()) onSignIn() else refused = true
            }) {
                Text("Forget it and sign in again")
            }
            TextButton(onClick = { asking = false }) { Text("Keep it") }
        }

        if (refused) {
            Text(
                "The key could not be discarded, so it is still stored. Nothing " +
                    "was half-forgotten.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        TextButton(onClick = onDone) { Text("Back") }
    }
}
