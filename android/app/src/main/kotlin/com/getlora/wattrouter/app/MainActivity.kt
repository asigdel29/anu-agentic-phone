// MainActivity.kt — the one screen there is, so far.
//
// History
//   2026-08-08  A. Sigdel  Created. Reports whether the core loaded and nothing
//                          else; the credential and the conversation follow.
//
// What it shows is the one thing this module can be wrong about. Splitting the
// build in two puts the shared object in the AAR and the AAR in the APK, and
// either hop can be misconfigured in a way that builds cleanly and dies at
// launch. A screen saying "hello" would prove the Compose setup and none of it.

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Core

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Loaded(coreLoads())
                }
            }
        }
    }
}

/**
 * Whether the native library is present and linkable in this APK.
 *
 * `Core`'s companion calls `System.loadLibrary` when the class initialises, so
 * touching it at all is the test. An empty credential is refused and returns
 * null, which is a normal answer and means the library answered; a missing or
 * unloadable `.so` throws out of the initialiser instead.
 */
internal fun coreLoads(): Boolean = runCatching { Core.open("") }.isSuccess

@Composable
private fun Loaded(yes: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("WattRouter", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (yes) "the routing core is loaded" else "the routing core did not load",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
