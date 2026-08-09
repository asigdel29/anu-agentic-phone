// MainActivity.kt — the launch, and which of three states it lands in.
//
// History
//   2026-08-08  A. Sigdel  Created. Reported whether the core loaded.
//   2026-08-08  A. Sigdel  Signs in, so it reports which of Startup's three
//                          states it reached instead.
//
// The core is held for the process rather than opened per screen. It owns a
// native pointer and a decision cache, and a second one is a second cache that
// disagrees with the first — the same reasoning WattRouterApp.swift gives for
// building its driver once into @State and never reassigning.

package com.getlora.wattrouter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Credential
import com.getlora.wattrouter.Startup

class MainActivity : ComponentActivity() {
    private var started: Startup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credential = Credential(this)

        setContent {
            var state by remember { mutableStateOf(started ?: Startup.begin(this)) }
            started = state

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val now = state) {
                        is Startup.Ready -> Running()
                        Startup.NoCredential, Startup.CoreRefused ->
                            SignInScreen(refused = now == Startup.CoreRefused) { typed ->
                                // store() answers false for an unusable key, and
                                // leaving the state alone is what keeps the field
                                // on screen rather than flashing past it.
                                if (credential.store(typed)) {
                                    state = Startup.begin(this@MainActivity)
                                }
                            }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        (started as? Startup.Ready)?.core?.close()
        started = null
        super.onDestroy()
    }
}

/** Signed in, with nothing yet to do about it. */
@Composable
private fun Running() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("WattRouter", style = MaterialTheme.typography.headlineMedium)
        Text("signed in; the router is up", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Whether the native library is present and linkable in this APK.
 *
 * `Core`'s companion calls `System.loadLibrary` when the class initialises, so
 * touching it at all is the test. An empty credential is refused and answers
 * [Startup.CoreRefused], which is a normal answer and means the library
 * answered; a missing or unloadable `.so` throws out of the initialiser.
 */
internal fun coreLoads(): Boolean = runCatching { Startup.from("") }.isSuccess
