// SignInScreen.kt — where the one credential the stack needs is typed.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The field is a password field, and that is not about secrecy from the person
// holding the phone. It keeps the key out of screenshots, out of the recents
// thumbnail, and out of the keyboard's learned-words dictionary, which is where
// a long unique string otherwise ends up.

package com.getlora.wattrouter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Ask for the provider key.
 *
 * @param refused true when a key is already stored and the core would not start
 *   with it — a different thing from never having signed in, and worth saying,
 *   because retyping the same key will not help.
 * @param onEntered what to do with what was typed. It has not been trimmed or
 *   checked; `Credential.store` does both and answers whether it took.
 */
@Composable
fun SignInScreen(refused: Boolean, onEntered: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WattRouter", style = MaterialTheme.typography.headlineMedium)

        Text(
            if (refused) {
                "The stored key did not start the router. Check it, or paste another."
            } else {
                "Paste your NeuralWatt key. It is kept on this phone and nowhere else."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("NEURALWATT_API_KEY") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onEntered(typed) },
            enabled = typed.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in")
        }
    }
}
