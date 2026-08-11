// Signing.kt: the name and the email a commit from this phone carries.
//
// History
//   2026-08-11  A. Sigdel  Created with #636.
//
// Contents
//   whoFrom       Two saved strings back into a Who, or nobody.
//   signature     What the row says, in one line.
//   Signing       The setting.
//   IdentityRow   Where it is read and changed.
//
// Modes.kt's shape, for the reason it gives: read on every commit rather than
// held, so somebody who corrects a misspelt address means this commit.
// SharedPreferences is a map in memory after its first load, so per-commit is a
// lookup rather than a read of a file.
//
// It defaults to nobody, and there is no sensible default to have instead.
// Every candidate this phone could reach is a guess at a person's name, and a
// guess here is a fabricated author in a history that outlives the phone.
//
// On the conversation rather than behind a settings screen, which is Modes.kt's
// argument, and here there is a second reason: the only other settings screen
// this application has is reachable from the checklist and the checklist stops
// appearing once the phone is ready, so a setting put beside it would be one
// nobody could get back to. That route is a defect of its own and #641 has it.
//
// One line while it is set, because most people committing nothing should not
// have to read about git every time they look at the screen. Unset it says so,
// because a commit refused for a reason nobody can see is the worse failure.

package com.getlora.wattrouter.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Who

/**
 * Two saved strings back into somebody, or nobody.
 *
 * Blank on either side is nobody rather than half a person: the core refuses a
 * half-filled identity anyway, and answering [Who] here would send a commit all
 * the way down to be refused for something this already knew.
 */
internal fun whoFrom(name: String?, email: String?): Who? {
    val both = Who(name.orEmpty().trim(), email.orEmpty().trim())
    return both.takeIf { it.name.isNotEmpty() && it.email.isNotEmpty() }
}

/**
 * What the row says.
 *
 * Named as the git convention writes it, `Name <address>`, because that is what
 * will appear in the log and somebody checking it should be checking the thing
 * itself rather than a rendering of it.
 */
internal fun signature(who: Who?): String =
    who?.let { "Commits are signed ${it.name} <${it.email}>" }
        ?: "Commits are unsigned, and will be refused. Say who you are."

/** Who commits from this phone. */
class Signing(context: Context) {
    private val store = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    /**
     * Who, now.
     *
     * # Rely
     * Read once per commit from the turn loop, on whatever thread the tool is
     * running on. SharedPreferences is safe from any and answers from memory.
     */
    var who: Who?
        get() = whoFrom(store.getString(NAME, null), store.getString(EMAIL, null))
        set(value) = store.edit()
            .putString(NAME, value?.name.orEmpty())
            .putString(EMAIL, value?.email.orEmpty())
            .apply()

    private companion object {
        const val NAME = "name"
        const val EMAIL = "email"
    }
}

/**
 * The line saying who commits, and the two fields behind it.
 *
 * @param who what is saved now.
 * @param onSaid what was typed, or null when both fields were emptied, which is
 *   how somebody takes their name off this phone.
 */
@Composable
fun IdentityRow(who: Who?, onSaid: (Who?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var name by remember(who) { mutableStateOf(who?.name.orEmpty()) }
    var email by remember(who) { mutableStateOf(who?.email.orEmpty()) }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(signature(who), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { open = !open }) {
                Text(if (open) "Close" else "Change")
            }
        }

        if (open) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                // Neither capitalised nor autocorrected. An address is not a
                // sentence, and a keyboard that helpfully capitalises the first
                // letter puts a name in every commit that nobody typed.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextButton(onClick = {
                onSaid(whoFrom(name, email))
                open = false
            }) {
                Text("Save")
            }
        }
    }
}
