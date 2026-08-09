// MainActivity.kt — the launch, and which of three states it lands in.
//
// History
//   2026-08-08  A. Sigdel  Created. Reported whether the core loaded.
//   2026-08-08  A. Sigdel  Signs in, so it reports which of Startup's three
//                          states it reached instead.
//   2026-08-09  A. Sigdel  Holds a conversation once it is signed in.
//   2026-08-09  A. Sigdel  Reads the calendar, which is the first tool needing
//                          a permission and so the first needing an Activity.
//   2026-08-09  A. Sigdel  Takes what another app shares, once.
//   2026-08-09  A. Sigdel  Shows the checklist when the phone half is not on.
//   2026-08-09  A. Sigdel  Can look at the screen and tap it, when the service
//                          behind that has been switched on.
//
// The core and the driver are built once and held for the process. The core
// owns a native pointer and a decision cache, and a second one is a second
// cache that disagrees with the first — the same reasoning WattRouterApp.swift
// gives for building its driver once into @State and never reassigning.

package com.getlora.wattrouter.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.getlora.wattrouter.Agent
import com.getlora.wattrouter.Budget
import com.getlora.wattrouter.Budgeted
import com.getlora.wattrouter.CalendarTool
import com.getlora.wattrouter.ChainWalk
import com.getlora.wattrouter.ContactsTool
import com.getlora.wattrouter.Credential
import com.getlora.wattrouter.FindOnScreenTool
import com.getlora.wattrouter.GitAddTool
import com.getlora.wattrouter.GitCommitTool
import com.getlora.wattrouter.GitStatusTool
import com.getlora.wattrouter.LocationTool
import com.getlora.wattrouter.Memory
import com.getlora.wattrouter.Needed
import com.getlora.wattrouter.NavigateTool
import com.getlora.wattrouter.OpenAppTool
import com.getlora.wattrouter.Permission
import com.getlora.wattrouter.Tool
import com.getlora.wattrouter.ReadScreenTool
import com.getlora.wattrouter.RecallTool
import com.getlora.wattrouter.RememberTool
import com.getlora.wattrouter.Repository
import com.getlora.wattrouter.NeuralWattInference
import com.getlora.wattrouter.Row
import com.getlora.wattrouter.ScrollTool
import com.getlora.wattrouter.Startup
import com.getlora.wattrouter.TapTool
import com.getlora.wattrouter.ToolBox
import com.getlora.wattrouter.TypeTextTool
import com.getlora.wattrouter.WaitForChangeTool
import com.getlora.wattrouter.TurnDriver
import com.getlora.wattrouter.routing

class MainActivity : ComponentActivity() {
    private var started: Startup? = null
    private var memory: Memory? = null
    private lateinit var permission: Permission

    /**
     * What one turn may do to the phone.
     *
     * Held here so the Agent that resets it and the tools that spend it are the
     * same one. A second would be a second allowance.
     */
    private val budget = Budget()

    /**
     * What another app shared, until a turn takes it.
     *
     * Compose state rather than a field, because the composition is what
     * notices: a share arriving while the app is already open reaches
     * [onNewIntent] and nothing recomposes on its own.
     */
    private val handed = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credential = Credential(this)
        take(intent)

        // Here rather than in the composition. AndroidAsking registers an
        // activity-result launcher, which has to happen before this Activity is
        // STARTED, and setContent's first composition is not before it — built
        // there it throws about a lifecycle state rather than about the line.
        permission = Permission(AndroidAsking(this))

        setContent {
            var state by remember { mutableStateOf(started ?: Startup.begin(this)) }
            started = state

            // Shown only when something required is off. Somebody whose phone
            // half is already on never sees it, which is the difference
            // between a checklist and a wizard nobody can dismiss.
            var checking by remember { mutableStateOf(!readiness(this).canDrive) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val now = state) {
                        is Startup.Ready ->
                            if (checking) {
                                Checklist { checking = false }
                            } else {
                                Conversation(driverFor(now, credential), handed)
                            }
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
                    tools = ToolBox(remembering() + phone() + working() + driving()),
                    budget = budget,
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

    /**
     * The repository the agent works in.
     *
     * `filesDir/work`, made if it is not there. A directory is not a repository
     * and the core has no `init` (#393), so until one arrives here these three
     * answer that it is not one — which is true and actionable, and the whole
     * of what is missing is a single entry point rather than anything above it.
     */
    private fun working(): List<Tool> {
        val root = java.io.File(filesDir, "work").apply { mkdirs() }
        val repository = Repository(root.absolutePath)
        return listOf(
            GitStatusTool(repository),
            GitAddTool(repository),
            GitCommitTool(repository),
        )
    }

    /**
     * A share arriving while the app is already open.
     *
     * `setIntent` as well, because `getIntent` keeps answering with whatever
     * started the Activity: without it a later recreation would read this same
     * intent again. [take] then empties it, so the pair is what makes a share
     * happen once — Inbox.drain's "a read removes", in the shape Android has.
     */
    override fun onNewIntent(incoming: Intent) {
        super.onNewIntent(incoming)
        setIntent(incoming)
        take(incoming)
    }

    /** Read a share out of an intent, and leave nothing behind to read twice. */
    private fun take(incoming: Intent?) {
        handedIn(
            incoming?.action,
            incoming?.type,
            incoming?.getStringExtra(Intent.EXTRA_TEXT),
            incoming?.getStringExtra(Intent.EXTRA_SUBJECT),
        )?.let { shared ->
            handed.value = shared
            // Emptied rather than remembered as handled. A rotation recreates
            // the Activity and reads getIntent() again, and the same note
            // shared twice is a conversation nobody had.
            incoming?.action = null
        }
    }

    /**
     * The tools that drive the phone rather than read its data.
     *
     * Offered whether or not the accessibility service is on. A model that
     * cannot see the tools cannot be told why they are unavailable, and
     * read_screen's own answer names the switch and the restricted-settings
     * trap behind it — which is the only place somebody learns about either.
     */
    private fun driving(): List<Tool> {
        val screen = Budgeted(AndroidPhone(applicationContext), budget)
        return listOf(
            ReadScreenTool(screen),
            TapTool(screen),
            TypeTextTool(screen),
            NavigateTool(screen),
            ScrollTool(screen),
            OpenAppTool(screen),
            WaitForChangeTool(screen),
            FindOnScreenTool(screen),
        )
    }

    /**
     * The checklist, re-read every time this screen comes back.
     *
     * ON_RESUME rather than first composition: the moment somebody looks at
     * this is the moment they return from Settings, and a state read before
     * they went is the lie a wizard tells.
     */
    @Composable
    private fun Checklist(onCarryOn: () -> Unit) {
        val owner = LocalLifecycleOwner.current
        var now by remember { mutableStateOf(readiness(this)) }
        var seeing by remember { mutableStateOf<String?>(null) }

        DisposableEffect(owner) {
            val watching = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) now = readiness(this@MainActivity)
            }
            owner.lifecycle.addObserver(watching)
            onDispose { owner.lifecycle.removeObserver(watching) }
        }

        // Evidence rather than a claim, and only once there is any: before the
        // service is on there is nothing to show and nothing to promise.
        LaunchedEffect(now.canDrive) {
            seeing = if (!now.canDrive) {
                null
            } else {
                ReadScreenTool(AndroidPhone(applicationContext)).run("{}").lines().take(LOOK).joinToString("\n")
            }
        }

        ReadinessScreen(now, seeing, onOpen = { open(it) }, onCarryOn = onCarryOn)
    }

    /**
     * Take somebody to the screen a row names.
     *
     * The restricted-settings row names a menu inside a page rather than a
     * page, and there is no intent for a menu — so it opens the page and the
     * row's own words say which item to press.
     */
    private fun open(step: Needed) {
        val where = when {
            step.what.contains("screen") -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null),
            )
        }
        runCatching { startActivity(where.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onDestroy() {
        (started as? Startup.Ready)?.core?.close()
        memory?.close()
        memory = null
        started = null
        super.onDestroy()
    }
}

@Composable
private fun Conversation(driver: TurnDriver, handed: MutableState<String?>) {
    val rows by driver.rows.collectAsState()
    val isRunning by driver.isRunning.collectAsState()
    val routing by driver.routing.collectAsState()
    val context = LocalContext.current

    // Cleared before sending, not after. send() starts a turn and returns, so
    // clearing afterwards would still be inside this effect and correct — but
    // clearing first is what makes a recomposition during the turn harmless.
    LaunchedEffect(handed.value) {
        handed.value?.let { shared ->
            handed.value = null
            driver.send(shared)
        }
    }

    // The service follows the driver rather than being started beside a send.
    // Every way a turn can end — answered, failed, interrupted — goes through
    // isRunning, and a notification left behind by one of them is a turn the
    // person believes is still running.
    LaunchedEffect(isRunning) {
        val about = rows.filterIsInstance<Row.Said>().lastOrNull()?.text.orEmpty()
        if (isRunning) {
            TurnService.begin(context, about)
        } else {
            TurnService.end(context)
        }

        // The banner follows the same signal rather than a second lifecycle
        // that can disagree with it. Every way a turn ends goes through
        // isRunning, and an overlay left behind by one of them says the agent
        // is still driving something.
        DrivingService.connected?.let {
            it.onStop = driver::interrupt
            it.showing(if (isRunning) about else null)
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
/** Lines of a reading shown as evidence. Enough to recognise the screen. */
private const val LOOK = 10

internal fun coreLoads(): Boolean = runCatching { Startup.from("") }.isSuccess
