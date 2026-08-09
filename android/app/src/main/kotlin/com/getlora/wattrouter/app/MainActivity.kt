// MainActivity.kt — the launch, and which of three states it lands in.
//
// History
//   2026-08-08  A. Sigdel  Created. Reported whether the core loaded.
//   2026-08-08  A. Sigdel  Signs in, so it reports which of Startup's three
//                          states it reached instead.
//   2026-08-09  A. Sigdel  Holds a conversation once it is signed in.
//   2026-08-09  A. Sigdel  Reads the calendar, which is the first tool needing
//                          a permission and so the first needing an Activity.
//
// The core and the driver are built once and held for the process. The core
// owns a native pointer and a decision cache, and a second one is a second
// cache that disagrees with the first — the same reasoning WattRouterApp.swift
// gives for building its driver once into @State and never reassigning.

package com.getlora.wattrouter.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.getlora.wattrouter.Agent
import com.getlora.wattrouter.CalendarTool
import com.getlora.wattrouter.ChainWalk
import com.getlora.wattrouter.ContactsTool
import com.getlora.wattrouter.Credential
import com.getlora.wattrouter.LocationTool
import com.getlora.wattrouter.Memory
import com.getlora.wattrouter.Permission
import com.getlora.wattrouter.Tool
import com.getlora.wattrouter.RecallTool
import com.getlora.wattrouter.RememberTool
import com.getlora.wattrouter.NeuralWattInference
import com.getlora.wattrouter.Row
import com.getlora.wattrouter.Startup
import com.getlora.wattrouter.ToolBox
import com.getlora.wattrouter.TurnDriver
import com.getlora.wattrouter.routing

class MainActivity : ComponentActivity() {
    private var started: Startup? = null
    private var memory: Memory? = null
    private lateinit var permission: Permission

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credential = Credential(this)

        // Here rather than in the composition. AndroidAsking registers an
        // activity-result launcher, which has to happen before this Activity is
        // STARTED, and setContent's first composition is not before it — built
        // there it throws about a lifecycle state rather than about the line.
        permission = Permission(AndroidAsking(this))

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
                    tools = ToolBox(remembering() + phone()),
                ),
                scope,
            )
        }
    }

    /**
     * The memory tools, if a store opened.
     *
     * An empty list rather than a refusal when it did not: a phone that cannot
     * open a store can still hold a conversation, and a model offered a tool
     * that always fails learns to apologise rather than to stop asking.
     *
     * The store lives in filesDir rather than cacheDir. Everything in cacheDir
     * is the system's to delete when space is short, and this is the one file
     * here nobody can reconstruct.
     */
    private fun remembering(): List<Tool> {
        val where = java.io.File(filesDir, "memory").apply { mkdirs() }
        val store = Memory.open(java.io.File(where, "memory.db").absolutePath)
            ?: return emptyList()
        memory = store
        return listOf(RememberTool(store, session = "phone"), RecallTool(store))
    }

    /**
     * The tools that read the phone rather than the app's own store.
     *
     * One [Permission] for all of them, so two asking in a round produce one
     * dialog. Contacts and location join this list; each brings a seam of its
     * own and none brings a second way of asking.
     */
    private fun phone(): List<Tool> = listOf(
        CalendarTool(AndroidCalendars(this), permission),
        ContactsTool(AndroidContacts(this), permission),
        LocationTool(AndroidWhereabouts(this), permission),
    )

    override fun onDestroy() {
        (started as? Startup.Ready)?.core?.close()
        memory?.close()
        memory = null
        started = null
        super.onDestroy()
    }
}

@Composable
private fun Conversation(driver: TurnDriver) {
    val rows by driver.rows.collectAsState()
    val isRunning by driver.isRunning.collectAsState()
    val routing by driver.routing.collectAsState()
    val context = LocalContext.current

    // The service follows the driver rather than being started beside a send.
    // Every way a turn can end — answered, failed, interrupted — goes through
    // isRunning, and a notification left behind by one of them is a turn the
    // person believes is still running.
    LaunchedEffect(isRunning) {
        if (isRunning) {
            TurnService.begin(context, rows.filterIsInstance<Row.Said>().lastOrNull()?.text.orEmpty())
        } else {
            TurnService.end(context)
        }
    }

    val asking = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    ChatScreen(
        rows = rows,
        isRunning = isRunning,
        routing = routing,
        onSend = { text ->
            // Asked at the first send rather than at launch: a permission
            // prompt before anybody has done anything is one people refuse.
            // Context.checkSelfPermission rather than ContextCompat: it is
            // API 23 and this app's floor is 29, and androidx.core 1.19 wants
            // an AGP this build does not have (#357).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                asking.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            driver.send(text)
        },
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
