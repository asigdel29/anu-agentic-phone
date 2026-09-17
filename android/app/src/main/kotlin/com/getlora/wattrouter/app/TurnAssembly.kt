// TurnAssembly.kt: the tools a turn gets that need no Activity to build.
//
// History
//   2026-09-17  A. Sigdel  Created, from MainActivity's four builders, #714.

package com.getlora.wattrouter.app

import android.content.Context
import com.getlora.wattrouter.Budget
import com.getlora.wattrouter.Budgeted
import com.getlora.wattrouter.Confirmed
import com.getlora.wattrouter.FetchTool
import com.getlora.wattrouter.FindOnScreenTool
import com.getlora.wattrouter.GitAddTool
import com.getlora.wattrouter.GitCommitTool
import com.getlora.wattrouter.GitInitTool
import com.getlora.wattrouter.GitStatusTool
import com.getlora.wattrouter.LookTool
import com.getlora.wattrouter.Memory
import com.getlora.wattrouter.NavigateTool
import com.getlora.wattrouter.OpenAppTool
import com.getlora.wattrouter.PullTool
import com.getlora.wattrouter.PushTool
import com.getlora.wattrouter.Reach
import com.getlora.wattrouter.Reaching
import com.getlora.wattrouter.ReadScreenTool
import com.getlora.wattrouter.RecallTool
import com.getlora.wattrouter.Recorded
import com.getlora.wattrouter.RememberTool
import com.getlora.wattrouter.Replay
import com.getlora.wattrouter.Repository
import com.getlora.wattrouter.RunCommandTool
import com.getlora.wattrouter.ScrollTool
import com.getlora.wattrouter.SetRemoteTool
import com.getlora.wattrouter.Shown
import com.getlora.wattrouter.Signed
import com.getlora.wattrouter.SystemShell
import com.getlora.wattrouter.TapTool
import com.getlora.wattrouter.Tool
import com.getlora.wattrouter.TypeTextTool
import com.getlora.wattrouter.WaitForChangeTool

/**
 * The half of a turn's assembly that needs no screen: the four builders
 * MainActivity delegates to, and the list a scheduled turn will call, #600's
 * shape. Nothing here takes an Activity, so a service can stand where
 * MainActivity stands. The phone-reading tools are the exception, and
 * [screenless] is where that is written down.
 */
class TurnAssembly(
    private val context: Context,
    private val filesDir: java.io.File,
    private val modes: Modes,
    private val signing: Signing,
    private val reaching: Reaching,
    private val replay: Replay,
    private val budget: Budget,
) {
    private val workspace by lazy { java.io.File(filesDir, "work").apply { mkdirs() } }

    /** The hosts `git::trust` has pinned, one per line. */
    val pins by lazy { java.io.File(filesDir, "known-hosts") }
    private var memory: Memory? = null

    /** The memory tools, if a store opened; an empty list when it did not. */
    fun remembering(): List<Tool> {
        val where = java.io.File(filesDir, "memory").apply { mkdirs() }
        val store = Memory.open(java.io.File(where, "memory.db").absolutePath)
            ?: return emptyList()
        memory = store
        return listOf(RememberTool(store, session = "phone"), RecallTool(store))
    }

    /** The repository the agent works in, and the git tools over it. */
    fun working(): List<Tool> {
        // Signed outermost, and reading the setting rather than holding one:
        // this runs once per assembly, and the driver that remembers its result
        // is built from it, so an identity captured here would be whichever was
        // set at launch.
        val repository = Signed(
            // Asked per call rather than held, as the identity below it and for
            // a sharper reason: a key can stop existing between two calls, since
            // the keystore drops its entries when the screen lock is removed.
            // Null until somebody has made one, which is a repository that can
            // reach a path remote and nothing else.
            Repository(workspace.absolutePath) {
                reaching.secret()?.let { Reach(it, pins.absolutePath) }
            },
        ) { signing.who }
        return listOf(
            GitStatusTool(repository),
            GitInitTool(repository),
            GitAddTool(repository),
            GitCommitTool(repository),
            SetRemoteTool(repository),
            FetchTool(repository),
            PushTool(repository),
            PullTool(repository),
        )
    }

    /** The shell, in the same directory the repository is in. */
    fun terminal(): List<Tool> = listOf(
        RunCommandTool(
            Shown(SystemShell(workspace.absolutePath), { modes.now }, AndroidConsent()),
        ),
    )

    /** The tools that drive the phone rather than read its data. */
    fun driving(): List<Tool> {
        // Confirmed outside Budgeted, which #553 argues: the other way round
        // spends a budgeted action on a prompt somebody then declines, so a
        // turn refused twenty times has nothing left for the one they would
        // have allowed.
        // Recorded innermost, so a step the budget refused or a person
        // declined is not in the replay: those did not happen, and a card
        // showing one would show a picture of a screen nothing changed.
        val screen = Confirmed(
            Budgeted(Recorded(AndroidPhone(context), replay), budget),
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
     * The tools a turn without an Activity may use.
     *
     * The exclusion is written rather than implied: no CalendarTool,
     * ContactsTool or LocationTool joins this list. Each is built on a
     * `Permission` that raises its dialog through an Activity, and a dialog a
     * scheduled turn raises is a dialog nobody is there to answer.
     */
    fun screenless(): List<Tool> = remembering() + working() + driving() + terminal()

    /** Close the memory store, if [remembering] opened one. */
    fun close() {
        memory?.close()
        memory = null
    }
}
