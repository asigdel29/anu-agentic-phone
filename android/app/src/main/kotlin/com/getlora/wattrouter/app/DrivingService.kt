// DrivingService.kt: the only thing that can read another app's screen.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  Acts as well as reads.
//   2026-08-09  A. Sigdel  Types, and refuses a password field here rather than
//                          in the tool, so a later one cannot route around it.
//   2026-08-09  A. Sigdel  Refuses to act where #440 says not to, which is why
//                          it now consumes exactly one event.
//   2026-08-09  A. Sigdel  Shows a banner while a turn is driving.
//   2026-08-09  A. Sigdel  Answers the accessibility button, which is the summon.
//
// It keeps no model of the screen, which is the decision worth reading twice.
// The obvious shape for an accessibility service is event-driven: track
// TYPE_WINDOW_CONTENT_CHANGED, keep a model, answer from it. That is what
// how-the-agent-drives.md rules out. Events arrive faster than a screen changes
// meaningfully and out of order with the tree they describe, and a cached model
// is the "remembered rather than re-read" failure #233 opens by warning about.
// So every read fetches the tree.
//
// It does consume one event, and this header used to say it consumed none. The
// activity's class name is what tells the accessibility settings page from the
// display settings page, #440 bars the first and not the second, and that name
// arrives only on TYPE_WINDOW_STATE_CHANGED, since rootInActiveWindow gives a package
// and no more. So one string is recorded, compared against a deny list, and used
// for nothing else. The argument above is about keeping a model of the screen in
// step; this is not that, and leaving the header claiming otherwise would be
// worse than amending it.
//
// One Viewing per connection, built in onServiceConnected. A service the system
// restarts gets a fresh epoch, which is the whole mechanism by which a handle
// cannot survive a restart. Building it lazily, or holding it in the companion,
// would give two lives one epoch and put the hole back.

package com.getlora.wattrouter.app

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.getlora.wattrouter.Aim
import com.getlora.wattrouter.Barred
import com.getlora.wattrouter.barred
import com.getlora.wattrouter.Done
import com.getlora.wattrouter.Generation
import com.getlora.wattrouter.Generations
import com.getlora.wattrouter.Handle
import com.getlora.wattrouter.Image
import com.getlora.wattrouter.Node
import com.getlora.wattrouter.Onward
import com.getlora.wattrouter.Reading
import com.getlora.wattrouter.Viewing
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import com.getlora.wattrouter.Way

/** The screen, when somebody has allowed it to be read. */
class DrivingService : AccessibilityService() {

    /**
     * Built here rather than lazily. This runs once per life of the service, so
     * the epoch inside it is once per life too.
     */
    private var viewing: Viewing? = null

    /**
     * The foreground activity's class, or null before the first window change.
     *
     * Volatile for [connected]'s reason: written on the service's thread and
     * read from a turn on another.
     */
    @Volatile
    private var inFront: String? = null

    /** The banner, while a turn is driving. */
    private var banner: Banner? = null

    /** The frame around the display, and whether it is on it. */
    private var border: Border? = null

    /**
     * The bubble, while a turn is not.
     *
     * Never up at the same time as [banner], which is the rule #522 records
     * from the design review rather than a preference about clutter.
     */
    private var head: ChatHead? = null
    private var shown = false
    private var bordered = false

    /**
     * The question, while one is being asked.
     *
     * Private, with only [asking] outside it: a caller wants to know whether
     * one is up, never which one.
     */
    private var pending: Asked? = null

    /** What the stop button does. Set by whoever started the turn. */
    var onStop: (() -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        viewing = Viewing(Generations.fresh())
        connected = this
        accessibilityButtonController.registerAccessibilityButtonCallback(summon)
        attachHead()
    }

    /**
     * The accessibility button, which is how the agent is reached from inside
     * another app.
     *
     * It opens the conversation rather than starting anything. An expanded
     * surface *is* the foreground app, so a summon that put the agent in front
     * of the screen it was about to read would be summoning it onto the thing
     * it wanted to look at: the design review's finding, and the reason the
     * bubble is a surface for before and after a task rather than during one.
     */
    private val summon = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            startActivity(
                Intent(this@DrivingService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Which window is in front, and nothing else.
     *
     * Not a model of the screen, but one string, compared against #440's deny
     * list. Everything about what is *on* the screen is still read when it is
     * needed, for the reason the header gives.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        inFront = event.className?.toString()
    }

    /** Nothing to abandon: no work is held between calls. */
    override fun onInterrupt() = Unit

    /**
     * Show what the agent is doing, over whatever it is doing it to.
     *
     * @param what the person's own words. Null takes the banner away, which is
     *   what the end of a turn does, however it ended.
     */
    fun showing(what: String?) {
        if (what == null) {
            hide()
            // Back once the task is over, which is the half of "before and
            // after" that is easy to forget: a bubble that leaves and does not
            // return is a bubble somebody stops relying on.
            attachHead()
            return
        }
        // Away for the duration. The banner already says what is happening and
        // carries the stop, and two overlays over one moment is two things to
        // read while watching a third. #522 records the design review finding
        // this follows.
        detachHead()
        val showing = banner ?: Banner(this) { onStop?.invoke() }.also { banner = it }
        showing.say(what)
        if (!shown) attach(showing)
        attachBorder()
    }

    /**
     * Put the bubble up, unless it is up.
     *
     * Silent on failure, as [attach]: a display that will not take an overlay
     * is a phone with no bubble on it, not a service that should refuse to
     * drive anything.
     */
    private fun attachHead() {
        if (head != null) return
        val bubble = ChatHead(this) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val windows = getSystemService(WindowManager::class.java)
        val put = runCatching {
            windows.addView(bubble.view, bubble.params)
            bubble.follow(windows)
        }.isSuccess
        if (put) head = bubble
    }

    /**
     * Put a question up, over whatever is about to be touched.
     *
     * Modal, unlike the banner, and [Asked] says why that is safe. One at a
     * time is the caller's guarantee rather than this one's: the turn loop runs
     * tools in order, so a second question cannot be asked while a first is up.
     *
     * # Rely
     * The main thread, as every WindowManager call is.
     *
     * @param onAnswer called once, with what was chosen. Not called at all if
     *   the overlay is refused a place on the display or if [stopAsking] takes
     *   it away first. [AndroidConsent] is what turns either into an answer.
     */
    fun ask(question: String, onAnswer: (Boolean) -> Unit) {
        val put = Asked(this, question) { yes ->
            stopAsking()
            onAnswer(yes)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Neither of the banner's two flags, which is the whole difference:
            // this takes the touches that land on it rather than passing them
            // to the app underneath.
            0,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        // Silent on failure, as attach: a phone that will not take an overlay
        // has nowhere to show a question, and AndroidConsent reads `asking`
        // afterwards as nobody having been asked.
        if (runCatching {
                getSystemService(WindowManager::class.java).addView(put.view, params)
            }.isSuccess
        ) {
            pending = put
        }
    }

    /** Take the question away, however it ended. */
    fun stopAsking() {
        val up = pending ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(up.view) }
        pending = null
    }

    /** Whether a question is on the display. */
    val asking: Boolean get() = pending != null

    /** Take it away, and forget where it was. */
    private fun detachHead() {
        val bubble = head ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(bubble.view) }
        head = null
    }

    /**
     * Put it on the display.
     *
     * TYPE_ACCESSIBILITY_OVERLAY, which needs no permission on any release
     * this app runs on: it is available to an accessibility service and to
     * nothing else, which is exactly what this is. #446 planned for
     * SYSTEM_ALERT_WINDOW below API 34 on the belief that the free route was
     * attachAccessibilityOverlayToDisplay and so new; that call takes a
     * SurfaceControl there is no public way to build, and this window type has
     * been here since API 22. So the permission is gone and so is the choice.
     */
    /**
     * Put the frame up, unless it is up.
     *
     * A second window rather than a thicker banner, because it is a different
     * shape: the banner is a strip at the top and this is the edge of the
     * display. Silent on failure, as [attach] is, and for the same reason: a
     * turn that cannot draw a frame is still a turn.
     *
     * MATCH_PARENT both ways with no gravity, so it is the display. Every touch
     * flag the banner sets, and one more: FLAG_NOT_TOUCHABLE, because unlike
     * the banner there is nothing on this to press. Without it a frame around
     * the screen would be a frame that ate the edge of every gesture, which is
     * where the back swipe lives.
     */
    private fun attachBorder() {
        if (bordered) return

        val edge = border ?: Border(this).also { border = it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )

        bordered = runCatching {
            getSystemService(WindowManager::class.java).addView(edge.view, params)
            true
        }.getOrDefault(false)
    }

    /** Take it away. Called wherever the banner is taken away, and with it. */
    private fun detachBorder() {
        if (!bordered) return
        border?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it.view) }
        }
        bordered = false
    }

    private fun attach(showing: Banner) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE so the app underneath keeps the keyboard, and
            // NOT_TOUCH_MODAL so a tap outside the banner reaches the app the
            // agent is aiming at. An overlay that swallows a tap makes the
            // agent's own actions fail as though the app refused them.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        // Answering false rather than throwing: a turn that cannot show a
        // banner is still a turn, and the service is not the place to decide
        // that a missing overlay ends one.
        shown = runCatching {
            getSystemService(WindowManager::class.java).addView(showing.view, params)
            true
        }.getOrDefault(false)
    }

    private fun hide() {
        // The frame first and unconditionally. It goes up with the banner and
        // has to come down with it, and an early return on a null banner would
        // leave a frame around the display of a phone nothing is driving.
        detachBorder()
        border = null

        val showing = banner ?: return
        if (shown) {
            runCatching { getSystemService(WindowManager::class.java).removeView(showing.view) }
        }
        banner = null
        shown = false
    }

    override fun onDestroy() {
        hide()
        detachHead()
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(summon) }
        connected = null
        viewing = null
        super.onDestroy()
    }

    /**
     * What is on screen now.
     *
     * @return null when the service is not connected, or while no window has
     *   focus, which the framework answers for a moment after a launch and
     *   whenever the screen is off.
     */
    fun read(): Reading? {
        val tree = screen() ?: return null
        return viewing?.read(tree)
    }

    /**
     * A picture of what is on screen, as a data URL.
     *
     * Three conversions, and each is why this is not a field on a reading. The
     * framework answers a hardware buffer; a bitmap wraps it; a PNG compresses
     * it; base64 encodes that. A caller wanting lines should not pay for any of
     * it.
     *
     * #610 measured what happens without `android:canTakeScreenshot`: the
     * binder throws SecurityException before the callback is reached at all,
     * saying "Services don't have the capability of taking the screenshot".
     * That is why the attribute is in driving.xml now, and it arrives with this
     * because a capability declared ahead of its caller is one nobody can weigh.
     *
     * # Rely
     * Called from a tool, off the main thread. Suspends until the framework
     * answers, which it does on the executor given.
     *
     * @return null when the framework refuses or answers nothing. One answer
     *   for both, because a caller can do nothing different about either.
     */
    suspend fun capture(): Image? = suspendCancellableCoroutine { waiting ->
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)

        // Guarded because a callback that fired twice would resume a
        // continuation twice, which throws rather than being ignored.
        //
        // Nothing to undo on cancellation: the value is a string, and the
        // hardware buffer it came from was closed before this was built.
        fun answer(image: Image?) {
            if (answered.compareAndSet(false, true)) {
                waiting.resume(image) { _, _, _ -> }
            }
        }

        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                { it.run() },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        answer(encoded(screenshot))
                    }

                    override fun onFailure(errorCode: Int) = answer(null)
                },
            )
        }.onFailure {
            // Thrown from the binder rather than delivered to the callback,
            // which #610 found and is why this is caught rather than trusted to
            // arrive as onFailure.
            answer(null)
        }
    }

    /**
     * A screenshot as a data URL, or null if any step of it will not.
     *
     * PNG rather than JPEG. A screen is text and flat colour, which is what PNG
     * is for and what JPEG is worst at: a compression artefact around a letter
     * is a letter the model reads wrong, and this picture exists to be read.
     *
     * The buffer is closed whatever happens. It is a hardware allocation and
     * leaving one to a finaliser is leaving it until the process is under
     * memory pressure, which is exactly when a screenshot was expensive.
     */
    private fun encoded(screenshot: ScreenshotResult): Image? = try {
        screenshot.hardwareBuffer.use { buffer ->
            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.let { bitmap ->
                ByteArrayOutputStream().use { bytes ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    Image("data:image/png;base64,$encoded")
                }
            }
        }
    } catch (refused: RuntimeException) {
        // A buffer the platform will not wrap, or an allocation that failed.
        // Null for the reason capture states: a caller can do nothing different.
        null
    }

    /**
     * Why the agent may not act on what is showing, or null.
     *
     * Reading goes through this too, which is where the tempting exception is:
     * looking at a screen sounds harmless, and read_screen on the permissions
     * page tells the model exactly which button says Allow. The refusal it
     * would then get from tap is one it can plan around.
     *
     * The keyguard is read now rather than remembered, because a phone locks while a
     * turn is running, which is the case this is for.
     */
    /**
     * Which application's window is in front, or null if none can be read.
     *
     * The same value [barredNow] already reads and does not surface. It goes to
     * the model and nowhere else: how-the-agent-drives.md's rule is that what is
     * on somebody's screen is not logged, and a package name is a thing about a
     * person as much as a line of text is.
     */
    fun inFront(): String? = rootInActiveWindow?.packageName?.toString()

    fun barredNow(): Barred? = barred(
        packageName = rootInActiveWindow?.packageName?.toString(),
        activity = inFront,
        own = packageName,
        locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
    )

    /**
     * Aim at a handle, against the screen as it is now.
     *
     * @return null on the terms [read] states. Everything else is an [Aim],
     *   including every way of missing.
     */
    fun aim(at: Handle, from: Generation): Aim? {
        val tree = screen() ?: return null
        return viewing?.aim(tree, at, from)
    }

    /**
     * Tap what a handle names.
     *
     * The tree is retained for this call and released before it returns, which
     * is the only place in the app that owes the framework anything back. A
     * copy is not something performAction can be called on, so acting needs the
     * node the copy was made from, and needs it only until the click lands.
     */
    fun tap(at: Handle, from: Generation): Done? {
        barredNow()?.let { return Done.Refused(it.why) }
        val viewing = viewing ?: return null
        val live = snapshot(rootInActiveWindow, retain = true) ?: return null

        return try {
            when (val aim = viewing.aim(live, at, from)) {
                is Aim.Moved -> Done.Moved(aim.now)
                is Aim.Lost -> Done.Lost(aim.resolution)
                is Aim.At -> click(aim.node)
            }
        } finally {
            release(live)
        }
    }

    /**
     * Move a list through its content.
     *
     * The same retain-and-release as [tap]. A node action rather than a
     * gesture, so canPerformGestures stays out of driving.xml and no
     * coordinate is involved anywhere on this path, which is stronger than
     * how-the-agent-drives.md promised, and worth keeping.
     */
    fun scroll(at: Handle, from: Generation, onward: Onward): Done? {
        barredNow()?.let { return Done.Refused(it.why) }
        val viewing = viewing ?: return null
        val live = snapshot(rootInActiveWindow, retain = true) ?: return null

        return try {
            when (val aim = viewing.aim(live, at, from)) {
                is Aim.Moved -> Done.Moved(aim.now)
                is Aim.Lost -> Done.Lost(aim.resolution)
                is Aim.At -> shift(aim.node, onward)
            }
        } finally {
            release(live)
        }
    }

    /**
     * Scroll a node, or say why not.
     *
     * A list already at its end answers false, and that is the answer to "is
     * there any more" rather than a failure. Told it failed, a model retries;
     * told the list is at its end, it stops.
     */
    private fun shift(node: Node, onward: Onward): Done {
        if (!node.isScrollable) {
            return Done.Refused(
                "that does not scroll. read_screen marks the lines that do",
            )
        }
        val source = (node as? Copied)?.source
            ?: return Done.Refused("it could not be reached on the screen any more")

        val action = when (onward) {
            Onward.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Onward.BACK -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return if (source.performAction(action)) {
            Done.Did(read())
        } else {
            Done.Refused(
                "it is already at the ${if (onward == Onward.FORWARD) "end" else "start"}, " +
                    "so nothing moved",
            )
        }
    }

    /**
     * Press one of the system's own buttons.
     *
     * No tree is fetched first and none is retained: a global action names no
     * node, so there is nothing to resolve and nothing to give back.
     */
    fun navigate(way: Way): Done? {
        // Barred here too, and the locked case is why: back and home on a
        // locked phone are somebody else's presses.
        barredNow()?.let { return Done.Refused(it.why) }
        if (viewing == null) return null

        val pressed = performGlobalAction(
            when (way) {
                Way.BACK -> GLOBAL_ACTION_BACK
                Way.HOME -> GLOBAL_ACTION_HOME
                Way.RECENTS -> GLOBAL_ACTION_RECENTS
                Way.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            },
        )

        // The screen is read after the press rather than before, and it is the
        // only way to say where that ended up: what back does depends on where
        // it was pressed.
        return if (pressed) Done.Did(read()) else Done.Refused("the system would not do that here")
    }

    /**
     * Put text in what a handle names, replacing what is there.
     *
     * The same retain-and-release as [tap]: setting text is a node action, so
     * it needs the node rather than the copy of it.
     */
    fun type(at: Handle, from: Generation, text: String): Done? {
        barredNow()?.let { return Done.Refused(it.why) }
        val viewing = viewing ?: return null
        val live = snapshot(rootInActiveWindow, retain = true) ?: return null

        return try {
            when (val aim = viewing.aim(live, at, from)) {
                is Aim.Moved -> Done.Moved(aim.now)
                is Aim.Lost -> Done.Lost(aim.resolution)
                is Aim.At -> write(aim.node, text)
            }
        } finally {
            release(live)
        }
    }

    /**
     * Set a node's text, or say why not.
     *
     * The password refusal is here rather than in the tool, which is
     * how-the-agent-drives.md's rule about where safety lives: a second tool
     * that also types would otherwise have to remember this one, and the ninth
     * one would not. prune never carries a password's value out, so a model
     * asked to fill one in is typing something it invented or something the
     * person said out loud, and the person can type their own password.
     */
    private fun write(node: Node, text: String): Done {
        if (node.isPassword) {
            return Done.Refused(
                "that is a password field and the assistant does not type into " +
                    "those. Ask the person to fill it in",
            )
        }
        if (!node.isEditable) {
            return Done.Refused(
                "there is nowhere to type there. read_screen marks the lines that " +
                    "take text, and this is not one of them",
            )
        }

        val source = (node as? Copied)?.source
            ?: return Done.Refused("it could not be reached on the screen any more")

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            Done.Did(read())
        } else {
            Done.Refused("the app would not take text there")
        }
    }

    /**
     * Click a node, or say why not.
     *
     * A node that will not take a click is refused rather than escalated to
     * whichever ancestor would. Walking up is what a person tapping a row's
     * label effectively does, and it is also tapping something the model did
     * not name, and read_screen already marks which lines can be tapped, so
     * the refusal points at that column instead.
     */
    private fun click(node: Node): Done {
        val source = (node as? Copied)?.source
            ?: return Done.Refused("it could not be reached on the screen any more")

        return if (source.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Done.Did(read())
        } else {
            Done.Refused(
                "the app would not take a tap there. read_screen marks the lines " +
                    "that can be tapped, and this is not one of them",
            )
        }
    }

    /**
     * The tree, copied.
     *
     * `rootInActiveWindow` is the active window rather than every window, which
     * is what a person is looking at. A dialog over an app is the active one; a
     * notification shade pulled down is too.
     */
    private fun screen(): Node? = snapshot(rootInActiveWindow)

    companion object {
        /**
         * The connected service, or null.
         *
         * Volatile because it is written on the service's own thread and read
         * from a turn on another. A tool holding a stale reference would call
         * into a service the system has already torn down.
         */
        @Volatile
        var connected: DrivingService? = null
            private set
    }
}
