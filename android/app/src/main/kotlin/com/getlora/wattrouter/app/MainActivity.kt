// MainActivity.kt: the launch, and which of three states it lands in.
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
//   2026-08-11  A. Sigdel  Holds the workspace, rather than the git tools
//                          computing it, now that it is about to have a second
//                          reader.
//   2026-08-11  A. Sigdel  Listens when the person asks it to, #659.
//   2026-08-12  A. Sigdel  Runs one command, in the workspace the git tools
//                          already use, #677.
//
// The core and the driver are built once and held for the process. The core
// owns a native pointer and a decision cache, and a second one is a second
// cache that disagrees with the first: the same reasoning WattRouterApp.swift
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
import com.getlora.wattrouter.Confirmed
import com.getlora.wattrouter.CalendarTool
import com.getlora.wattrouter.Capability
import com.getlora.wattrouter.ChainWalk
import com.getlora.wattrouter.ContactsTool
import com.getlora.wattrouter.Credential
import com.getlora.wattrouter.FindOnScreenTool
import com.getlora.wattrouter.GitAddTool
import com.getlora.wattrouter.GitCommitTool
import com.getlora.wattrouter.GitInitTool
import com.getlora.wattrouter.GitStatusTool
import com.getlora.wattrouter.Heard
import com.getlora.wattrouter.LocationTool
import com.getlora.wattrouter.LookTool
import com.getlora.wattrouter.Memory
import com.getlora.wattrouter.Needed
import com.getlora.wattrouter.NavigateTool
import com.getlora.wattrouter.OpenAppTool
import com.getlora.wattrouter.Connections
import com.getlora.wattrouter.HttpRpc
import com.getlora.wattrouter.Permission
import com.getlora.wattrouter.PermissionError
import com.getlora.wattrouter.Planned
import com.getlora.wattrouter.Reached
import com.getlora.wattrouter.connect
import com.getlora.wattrouter.tools
import com.getlora.wattrouter.Tool
import com.getlora.wattrouter.ReadScreenTool
import com.getlora.wattrouter.Recorded
import com.getlora.wattrouter.Replay
import com.getlora.wattrouter.RecallTool
import com.getlora.wattrouter.RememberTool
import com.getlora.wattrouter.Repository
import com.getlora.wattrouter.Shown
import com.getlora.wattrouter.Signed
import com.getlora.wattrouter.NeuralWattInference
import com.getlora.wattrouter.Row
import com.getlora.wattrouter.RunCommandTool
import com.getlora.wattrouter.ScrollTool
import com.getlora.wattrouter.Startup
import com.getlora.wattrouter.SystemShell
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

    /** What the last turn did, for the card the transcript will show. */
    private val replay = Replay()

    /**
     * How involved this person wants to be, read per action by [Confirmed].
     *
     * Built here rather than per composition: a store rebuilt on every
     * recomposition is a file opened on every recomposition, and the mode is
     * read from a turn rather than from the composition anyway.
     */
    private val modes by lazy { Modes(applicationContext) }
    private val signing by lazy { Signing(applicationContext) }
    private val connections by lazy { Connections(applicationContext) }

    /**
     * The microphone, built once for the process like the three above it.
     *
     * The application context rather than this Activity: it outlives a rotation
     * and the recognizer is torn down at the end of every press anyway, so a
     * reference to the Activity here would be one nothing needs.
     */
    private val listening by lazy { AndroidListening(applicationContext) }

    /**
     * Where the tools work, made if it is not there.
     *
     * Here rather than inside the one function that reads it today, because it
     * is about to have more than one reader: #602 requires a terminal to run in
     * the workspace the tools already have rather than invent a second. Two
     * places computing one path is two places that have to agree, and nothing
     * would check that they did.
     */
    private val workspace by lazy { java.io.File(filesDir, "work").apply { mkdirs() } }

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
        // STARTED, and setContent's first composition is not before it, and built
        // there it throws about a lifecycle state rather than about the line.
        permission = Permission(AndroidAsking(this))

        setContent {
            var state by remember { mutableStateOf(started ?: Startup.begin(this)) }
            started = state

            // Shown only when something required is off. Somebody whose phone
            // half is already on never sees it, which is the difference
            // between a checklist and a wizard nobody can dismiss.
            var checking by remember { mutableStateOf(!readiness(this).canDrive) }
            var connecting by remember { mutableStateOf(false) }

            // Null until every saved server has been asked, and the
            // conversation waits for it, because ToolBox is what the model is
            // told at the top of a turn: a set that grew afterwards is a tool
            // it was never offered. With nothing saved this costs a frame.
            var connected by remember { mutableStateOf<List<Reached>?>(null) }
            LaunchedEffect(connecting) {
                if (!connecting) {
                    connected = connect(connections.all) { HttpRpc(it.endpoint) }
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val now = state) {
                        is Startup.Ready ->
                            if (checking) {
                                Checklist(
                                    onCarryOn = { checking = false },
                                    onConnections = { connecting = true },
                                )
                            } else if (connecting) {
                                ConnectionsScreen(
                                    connected = connected.orEmpty(),
                                    onAdd = { label, endpoint ->
                                        connections.add(label, endpoint)
                                    },
                                    onForget = { connections.forget(it) },
                                    // Leaving re-asks, because a server added
                                    // or forgotten while it was open changes
                                    // what the next turn is offered.
                                    onDone = { connecting = false },
                                )
                            } else if (connected != null) {
                                Conversation(
                                    driverFor(now, credential, connected!!),
                                    handed,
                                    modes,
                                    signing,
                                    replay,
                                    ::heard,
                                )
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
    private fun driverFor(
        ready: Startup.Ready,
        credential: Credential,
        connected: List<Reached>,
    ): TurnDriver {
        val scope = rememberCoroutineScope()
        return remember(ready.core, connected) {
            TurnDriver(
                Agent(
                    router = ready.core.routing(),
                    // BuildConfig rather than the client's default, so where a
                    // turn goes is decided when the application is built and
                    // cannot be moved at runtime by anybody, including the
                    // agent, which can drive this application's own screen.
                    walk = ChainWalk(
                        NeuralWattInference(
                            credential.read().orEmpty(),
                            BuildConfig.UPSTREAM_BASE_URL,
                        ),
                    ),
                    // Last, so a server cannot displace a compiled tool:
                    // ToolBox keeps the first of a duplicate name, and prefixed
                    // makes the collision impossible anyway. Both, because one
                    // is a rule and the other is an ordering.
                    tools = ToolBox(
                        remembering() + phone() + working() + driving() + terminal() +
                            connected.tools(),
                    ),
                    budget = budget,
                    // The mode is read here rather than captured, so somebody
                    // who changed it between typing and the model answering
                    // means this turn. Confirmed reads the same setting the
                    // same way, one action at a time, and the two never both
                    // fire: Planned is silent in every mode but Plan.
                    planned = Planned({ modes.now }, AndroidApproval()),
                    replay = replay,
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
     * One press of the microphone.
     *
     * Deliberately not in the list above. It is a person's button rather than
     * a tool, and the difference is the point: a model that could call this
     * could open the microphone in a turn nobody was watching.
     *
     * The same [Permission] as those three, in [CalendarTool]'s order, and the
     * refusal is returned rather than thrown for its reason too. A refusal here
     * has somewhere to be read, because [Heard.Silence] is already what the
     * button shows when a press produces nothing.
     *
     * # Rely
     * Called from the composition's scope, which cancels with the screen. It may
     * put a dialog on screen, and then holds the microphone until whoever
     * pressed it stops speaking.
     */
    private suspend fun heard(): Heard {
        try {
            permission.obtain(Capability.MICROPHONE)
        } catch (e: PermissionError) {
            return Heard.Silence(e.message.orEmpty())
        }
        return listening.listen()
    }

    /**
     * The repository the agent works in.
     *
     * The [workspace]. A directory is still not a repository, so on a fresh
     * install the other three answer that it is not one until [GitInitTool] has
     * been called. That is the model's call to make rather than this function's:
     * the two answers `init` distinguishes are "made you one" and "there already
     * was one", and a repository created here at startup would spend that
     * distinction before anybody could read it.
     */
    private fun working(): List<Tool> {
        // Signed outermost, and reading the setting rather than holding one:
        // this function runs once, where driverFor remembers the driver, so an
        // identity captured here would be whichever was set at launch.
        val repository = Signed(Repository(workspace.absolutePath)) { signing.who }
        return listOf(
            GitStatusTool(repository),
            GitInitTool(repository),
            GitAddTool(repository),
            GitCommitTool(repository),
        )
    }

    /**
     * The shell, in the same directory the repository is in.
     *
     * The [workspace] again rather than a second one, which is what #647 hoisted
     * it for: a formatter the agent runs and a file it staged have to be the
     * same file.
     *
     * [Shown] outermost, and reading the mode rather than holding one, for the
     * reason [working] gives about identity. It is also the reason this is not
     * a [Confirmed]: that seam is a [Phone], and it treats Plan as Auto because
     * a round's tool names were approved once at the top of the turn. The name
     * approved here is `run_command`, which is the same name for `git status`
     * and for `rm -rf .`, so Plan asks.
     */
    private fun terminal(): List<Tool> = listOf(
        RunCommandTool(
            Shown(SystemShell(workspace.absolutePath), { modes.now }, AndroidConsent()),
        ),
    )

    /**
     * A share arriving while the app is already open.
     *
     * `setIntent` as well, because `getIntent` keeps answering with whatever
     * started the Activity: without it a later recreation would read this same
     * intent again. [take] then empties it, so the pair is what makes a share
     * happen once: Inbox.drain's "a read removes", in the shape Android has.
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
     * trap behind it, which is the only place somebody learns about either.
     */
    private fun driving(): List<Tool> {
        // Confirmed outside Budgeted, which #553 argues: the other way round
        // spends a budgeted action on a prompt somebody then declines, so a
        // turn refused twenty times has nothing left for the one they would
        // have allowed.
        // Recorded innermost, so a step the budget refused or a person
        // declined is not in the replay: those did not happen, and a card
        // showing one would show a picture of a screen nothing changed.
        val screen = Confirmed(
            Budgeted(Recorded(AndroidPhone(applicationContext), replay), budget),
            { modes.now },
            AndroidConsent(),
        )
        return listOf(
            ReadScreenTool(screen),
            // Beside read_screen rather than instead of it. A picture has no
            // handles in it, so every action still goes through a reading;
            // look is for the layout a tree describes badly.
            LookTool(screen),
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
    private fun Checklist(onCarryOn: () -> Unit, onConnections: () -> Unit) {
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

        ReadinessScreen(
            now,
            seeing,
            onOpen = { open(it) },
            onCarryOn = onCarryOn,
            onConnections = onConnections,
        )
    }

    /**
     * Take somebody to the screen a row names.
     *
     * The restricted-settings row names a menu inside a page rather than a
     * page, and there is no intent for a menu, so it opens the page and the
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
private fun Conversation(
    driver: TurnDriver,
    handed: MutableState<String?>,
    modes: Modes,
    signing: Signing,
    replay: Replay,
    /** One press of the microphone, permission and all. */
    listen: suspend () -> Heard,
) {
    // Held in composition as well as in the store, because a chip has to
    // repaint when it is tapped and the store is not observable. The store
    // stays the truth: it is what the turn loop reads.
    var mode by remember { mutableStateOf(modes.now) }
    var who by remember { mutableStateOf(signing.who) }
    val rows by driver.rows.collectAsState()
    val isRunning by driver.isRunning.collectAsState()
    val routing by driver.routing.collectAsState()
    val context = LocalContext.current

    // Cleared before sending, not after. send() starts a turn and returns, so
    // clearing afterwards would still be inside this effect and correct, but
    // clearing first is what makes a recomposition during the turn harmless.
    LaunchedEffect(handed.value) {
        handed.value?.let { shared ->
            handed.value = null
            driver.send(shared)
        }
    }

    // The service follows the driver rather than being started beside a send.
    // Every way a turn can end (answered, failed, interrupted) goes through
    // isRunning, and a notification left behind by one of them is a turn the
    // person believes is still running.
    LaunchedEffect(isRunning) {
        val about = rows.filterIsInstance<Row.Said>().lastOrNull()?.text.orEmpty()
        if (isRunning) {
            // Before the service, not after: the notification carries a Stop
            // button and the system may deliver a press the moment it is
            // posted. Set second, that press finds no callback and does what
            // #470 was about.
            TurnService.onStop = driver::interrupt
            TurnService.begin(context, about)
        } else {
            TurnService.end(context)
            TurnService.onStop = null
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
        // Read at composition rather than collected. It changes only while a
        // turn is running, and the card is drawn only while one is not, so a
        // flow here would be a subscription that never fires when it is read.
        replay = if (isRunning) emptyList() else replay.steps,
        isRunning = isRunning,
        routing = routing,
        mode = mode,
        who = who,
        onMode = {
            mode = it
            modes.now = it
        },
        // Held in composition as well as in the store, for the reason the mode
        // is: the line has to repaint when it is saved and the store is not
        // observable. The store stays the truth, and Signed is what reads it.
        onWho = {
            who = it
            signing.who = it
        },
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
        onListen = listen,
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
