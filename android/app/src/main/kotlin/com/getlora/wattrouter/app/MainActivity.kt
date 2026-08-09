// MainActivity.kt — the launch, and which of three states it lands in.
//
// History
//   2026-08-08  A. Sigdel  Created. Reported whether the core loaded.
//   2026-08-08  A. Sigdel  Signs in, so it reports which of Startup's three
//                          states it reached instead.
//   2026-08-09  A. Sigdel  Holds a conversation once it is signed in.
//
// The core and the driver are built once and held for the process. The core
// owns a native pointer and a decision cache, and a second one is a second
// cache that disagrees with the first — the same reasoning WattRouterApp.swift
// gives for building its driver once into @State and never reassigning.

package com.getlora.wattrouter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.getlora.wattrouter.Agent
import com.getlora.wattrouter.ChainWalk
import com.getlora.wattrouter.Credential
import com.getlora.wattrouter.NeuralWattInference
import com.getlora.wattrouter.Startup
import com.getlora.wattrouter.ToolBox
import com.getlora.wattrouter.TurnDriver
import com.getlora.wattrouter.routing

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
                        is Startup.Ready -> Conversation(driverFor(now, credential))
                        Startup.NoCredential, Startup.CoreRefused ->
                            SignInScreen(refused = now == Startup.CoreRefused) { typed ->
                                // store() answers false for an unusable key, and
                                // leaving the state alone keeps the field on
                                // screen rather than flashing past it.
                                if (credential.store(typed)) {
                                    state = Startup.begin(this@MainActivity)
                                }
                            }
                    }
                }
            }
        }
    }

    /**
     * The driver, built once and kept.
     *
     * Keyed on the core, so a sign-in that replaces one does not leave a driver
     * holding the old handle. rememberCoroutineScope rather than lifecycleScope:
     * it needs no dependency, and it cancels a turn with the composition that
     * started it. A turn that should outlive the screen is #233's item on long
     * work, and wants a foreground service rather than a wider scope here.
     */
    @Composable
    private fun driverFor(ready: Startup.Ready, credential: Credential): TurnDriver {
        val scope = rememberCoroutineScope()
        return remember(ready.core) {
            TurnDriver(
                Agent(
                    router = ready.core.routing(),
                    walk = ChainWalk(NeuralWattInference(credential.read().orEmpty())),
                    // No tools yet. An empty array tells the model so, rather
                    // than telling it nothing.
                    tools = ToolBox(emptyList()),
                ),
                scope,
            )
        }
    }

    override fun onDestroy() {
        (started as? Startup.Ready)?.core?.close()
        started = null
        super.onDestroy()
    }
}

@Composable
private fun Conversation(driver: TurnDriver) {
    val rows by driver.rows.collectAsState()
    val isRunning by driver.isRunning.collectAsState()
    val routing by driver.routing.collectAsState()

    ChatScreen(
        rows = rows,
        isRunning = isRunning,
        routing = routing,
        onSend = driver::send,
        onInterrupt = driver::interrupt,
    )
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
