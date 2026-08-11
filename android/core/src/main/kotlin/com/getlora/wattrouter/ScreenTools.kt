// ScreenTools.kt: looking at the screen, and the seam that reaches one.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  The seam takes actions rather than answering nodes,
//                          because a Node is a copy and cannot be clicked.
//
// Contents
//   Done            What doing something produced.
//   Phone           The screen, as a tool reaches it.
//   encodeSeen      A generation as the model reads one.
//   decodeSeen      One back, or nothing.
//   ReadScreenTool  Looking.
//   TapTool         Touching.
//   TypeTextTool    Filling something in.
//   Way             A button the system owns.
//   NavigateTool    Pressing one.
//   Onward          Which way through a list.
//   ScrollTool      Moving one.
//   Launchable      An app that can be started.
//   matching        Which one a name means.
//   OpenAppTool     Starting it.
//   WaitForChangeTool  Letting the screen finish moving.
//   FindOnScreenTool   Asking about the part that was not printed.
//
// The seam is why this is in core/ at all. DrivingService is an app-module
// class holding framework types, and everything here is prose, rendering and
// refusals, none of which needs one. The split every capability in Phase 2
// took, for the reason Conversation.kt took it first.
//
// A line is an action and a token. The token already says the role and the
// label, so printing those beside it would be the same text twice on every line
// of a screen that can be a hundred lines long. What the first column adds is
// the one thing a token does not carry: whether the node can be acted on.

package com.getlora.wattrouter

/**
 * What doing something produced.
 *
 * Three of the four are not failures of the action. A model told "failed" for
 * all of them learns to retry, which is right for one and wrong for three.
 */
sealed interface Done {
    /**
     * It happened.
     *
     * @property now the screen afterwards, which may still be settling: a tap
     *   opening a page is answered before the page has finished arriving.
     */
    data class Did(val now: Reading?) : Done

    /** The screen moved before it could happen. Carries what is there instead. */
    data class Moved(val now: Reading) : Done

    /** This is still the screen and the handle names nothing on it. */
    data class Lost(val resolution: Resolution) : Done

    /** Found, and the framework would not do it. The only one about the action. */
    data class Refused(val why: String) : Done
}

/** The screen, as a tool reaches it. */
interface Phone {
    /**
     * Why nothing on the screen may be acted on or read, or null.
     *
     * Separate from [read] because a refusal needs words and a reading has
     * nowhere to put them: answering null from [read] would be reported as the
     * service being off, which is a different problem with a different fix.
     *
     * # Rely
     * As [read]. Cheap: it compares a package and an activity against a list.
     */
    suspend fun barredNow(): String?

    /**
     * Whether the service that reads screens is attached at all.
     *
     * Separate from [read] for the reason [barredNow] is: null has more than
     * one cause and the answer needs different words for each. #517 is what
     * happens without it: a window that had not arrived yet was reported as a
     * permission that was never granted, and the model was told to go and turn
     * on a switch that was already on.
     *
     * # Rely
     * As [read], and cheaper: it asks whether a service is bound and reads
     * nothing.
     */
    suspend fun attached(): Boolean

    /**
     * What is on screen now.
     *
     * # Rely
     * Called from the turn loop. Fetches a tree rather than answering from one
     * held, so it costs a round trip into the framework each time.
     *
     * @return null when the screen cannot be read: the service off, or no
     *   window focused. [attached] tells the two apart, and a caller answering
     *   null without asking it will blame the wrong one.
     */
    suspend fun read(): Reading?

    /**
     * A picture of what is on screen.
     *
     * Separate from [read] rather than a field on a [Reading], because the two
     * cost different things: a reading is a tree walk and this is a frame
     * grab, compressed and encoded, and a caller that wanted lines should not
     * pay for pixels it never asked for.
     *
     * Encoded here rather than answered as bytes, so no framework type and no
     * PNG crosses the seam. Conversation.Image is a data URL for the same
     * reason: what it holds is what goes on the wire.
     *
     * # Rely
     * As [read], and dearer: a frame grab plus a PNG compress plus a base64 of
     * the result. Called when a tool asks, never per turn.
     *
     * @return null when there is nothing to capture, on [read]'s terms, and
     *   also when the framework refuses. Those are one answer here because a
     *   caller can do nothing different about either: #610 measured that a
     *   service without android:canTakeScreenshot is refused, and that is a
     *   manifest that shipped wrong rather than a state to recover from.
     *
     * Defaulted to null so a Phone that cannot capture says so by saying
     * nothing, which is every test double and would be an iOS one. A decorator
     * must still override it: Budgeted and Confirmed wrap the real phone, and
     * inheriting this would make capture unavailable through the only path a
     * turn ever takes.
     */
    suspend fun capture(): Image? = null

    /**
     * Tap what a handle names.
     *
     * The seam takes the action rather than answering the node, because a
     * [Node] is a copy: plain values with no way back to the framework object
     * that has to be clicked. It also keeps every framework type on the far
     * side, which is why there is a seam at all.
     *
     * # Rely
     * As [read]. Suspends for as long as the framework takes to dispatch, and
     * reads the screen again afterwards.
     *
     * @return null on [read]'s terms: the screen unreadable, which is not the
     *   same as a tap that did not land.
     */
    suspend fun tap(at: Handle, from: Generation): Done?

    /**
     * Put text in what a handle names, replacing what is there.
     *
     * # Rely
     * As [tap]. A password field is refused on the far side of this seam rather
     * than here, so a later tool that also types cannot route around it.
     *
     * @param text what the field should say afterwards. Empty clears it, which
     *   is a thing somebody asks for.
     */
    suspend fun type(at: Handle, from: Generation, text: String): Done?

    /**
     * Press one of the system's own buttons.
     *
     * # Rely
     * As [tap]. Takes no generation: a global action names no node, so there
     * is nothing about it that can go stale.
     */
    suspend fun navigate(way: Way): Done?

    /**
     * Move a list through its content.
     *
     * # Rely
     * As [tap]. A node action rather than a gesture, so no coordinate is
     * involved anywhere on this path.
     */
    suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done?

    /**
     * Every app that can be started from the launcher.
     *
     * # Rely
     * Called from the turn loop. Reads the package manager, which is a list of
     * a few hundred on a real phone.
     *
     * @return null when it cannot be read at all.
     */
    suspend fun apps(): List<Launchable>?

    /**
     * Start one, by package name.
     *
     * Package name rather than label, because choosing which app a label means
     * is the part with decisions in it and it lives on this side of the seam
     * where a test can reach it.
     *
     * # Rely
     * As [tap]. Reads the screen afterwards, which will often catch the app
     * mid-launch.
     */
    suspend fun open(packageName: String): Done?
}

/** An app that can be started. */
data class Launchable(val label: String, val packageName: String)

/** A name as a comparison sees it: no case, no spacing. */
private fun plain(name: String) = name.lowercase().filterNot { it.isWhitespace() }

/**
 * Which apps a name means, best first.
 *
 * Three tiers, because models write app names the way people say them. Exact
 * wins outright. Otherwise a prefix ("Maps" for "Maps Go") and only then a
 * substring, so "mail" finding Gmail is a last resort rather than a first
 * guess. Case and spacing are ignored throughout: "WhatsApp", "whatsapp" and
 * "Whats App" are one app to everybody except a string comparison.
 *
 * @return the best tier that matched anything, or empty. More than one is for
 *   the caller to refuse rather than choose between: two apps called Calendar
 *   is ordinary on a phone with a work profile, and picking the first is
 *   picking somebody's employer at random.
 */
internal fun matching(name: String, apps: List<Launchable>): List<Launchable> {
    val wanted = plain(name)
    if (wanted.isEmpty()) return emptyList()

    val exact = apps.filter { plain(it.label) == wanted }
    if (exact.isNotEmpty()) return exact

    val prefixed = apps.filter { plain(it.label).startsWith(wanted) }
    if (prefixed.isNotEmpty()) return prefixed

    return apps.filter { plain(it.label).contains(wanted) }
}

/**
 * Which way through a list.
 *
 * Forward and back rather than down and up. A horizontal carousel scrolls
 * sideways and a vertical list scrolls down, and both are the same action.
 * naming it "down" would be right for most lists and wrong for the rest, and a
 * model told "down" on a carousel would be surprised by what moved.
 */
enum class Onward(val word: String) {
    FORWARD("forward"),
    BACK("back"),
    ;

    companion object {
        fun of(word: String?): Onward? = entries.firstOrNull { it.word == word?.trim() }

        val words: String get() = entries.joinToString(", ") { it.word }
    }
}

/**
 * A button the system owns.
 *
 * Closed, and the refusal lists it. A model writing `go_back` should be told
 * the words that work, the way ToolBox answers an unknown tool with the names
 * that exist. Guessing at a near miss is the mistake resolve refuses to make.
 */
enum class Way(val word: String) {
    /**
     * Back. What it does depends on where it is pressed: from an app's first
     * screen it leaves the app, over a keyboard it closes that instead. The
     * tool cannot know which, so the answer is the screen afterwards.
     */
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
    ;

    companion object {
        fun of(word: String?): Way? = entries.firstOrNull { it.word == word?.trim() }

        /** The words that work, for a refusal to name. */
        val words: String get() = entries.joinToString(", ") { it.word }
    }
}

/** A generation as the model reads one: `k3f9.4`. */
fun encodeSeen(generation: Generation): String =
    "${generation.epoch}.${generation.counter}"

/**
 * One back.
 *
 * @return null for anything this build did not write. Strict for [decode]'s
 *   reason: a generation assembled with a default counter would compare equal
 *   to a real reading and let a stale handle through.
 */
fun decodeSeen(token: String?): Generation? {
    val text = token?.trim() ?: return null
    val dot = text.lastIndexOf('.')
    if (dot <= 0 || dot == text.length - 1) return null

    val counter = text.substring(dot + 1).toLongOrNull() ?: return null
    if (counter < 0) return null

    return Generation(text.substring(0, dot), counter)
}

/** Look at the screen. */
class ReadScreenTool(private val phone: Phone) : Tool {
    override val name = "read_screen"

    override val purpose =
        "See what is on the phone's screen right now. Every line is one thing: " +
            "what you can do with it, then a handle naming it. Pass a handle and " +
            "the screen id back to act on something. Read again after anything " +
            "changes: a handle from an older reading is refused, not guessed at."

    override val schema = """{"type":"object","properties":{}}"""

    /** # Rely
     *  Obtains no capability. The accessibility service is granted once, from
     *  Settings, rather than asked for per turn, so there is no dialog here
     *  and nothing to validate before one. */
    override suspend fun run(arguments: String): String {
        // Before the read, not after. read_screen on the permissions page tells
        // the model exactly which button says Allow, and the refusal it would
        // then get from tap is a refusal it can plan around.
        phone.barredNow()?.let { return it }
        val reading = phone.read() ?: return unreadable(phone.attached())
        return describe(reading)
    }

    companion object {
        /** Most lines shown. Past this a model is reading a layout dump. */
        const val LIMIT = 60

        /** Deepest indent. Beyond it the nesting says nothing a reader uses. */
        private const val DEEPEST = 6

        /**
         * Why the screen could not be read, in the caller's terms.
         *
         * The argument is the whole point of the function. A screen reads as
         * unreadable both when nothing may read it and when there is nothing
         * to read *yet*, and those want opposite advice: one is a switch to
         * turn on, the other is to try again in a moment. #517 is the second
         * being answered with the first, on the one sequence this application
         * exists for: open an app, then read it.
         *
         * @param attached whether the service is bound, from [Phone.attached].
         */
        fun unreadable(attached: Boolean): String =
            if (attached) {
                // No mention of Settings. A model told about a permission it
                // cannot grant itself stops and reports one, and stopping is
                // exactly wrong here: the next call would have worked.
                "the screen could not be read just now, most often a window " +
                    "that has not finished arriving. Read it again."
            } else {
                // The failure how-the-agent-drives.md calls the one with no
                // error attached: on a sideloaded build the toggle is greyed
                // until restricted settings are cleared, and nothing says so.
                "the screen could not be read. Turn the assistant on in " +
                    "Settings > Accessibility > WattRouter. If the switch there is " +
                    "greyed out, open Settings > Apps > WattRouter, then the menu in " +
                    "the corner, and allow restricted settings first."
            }

        /** What a reading looks like. */
        fun describe(reading: Reading): String {
            // Distinguishable from an unreadable screen, for the reason every tool here
            // has one: a model told "nothing" cannot tell an empty page from a
            // locked door.
            if (reading.seen.isEmpty()) return "the screen is readable and has nothing on it"

            val lines = reading.seen.take(LIMIT).map { sighting ->
                val indent = "  ".repeat(minOf(sighting.depth, DEEPEST))
                "$indent${doing(sighting)}  ${encode(sighting.handle)}"
            }

            val rest = reading.seen.size - LIMIT
            val more = if (rest > 0) "\nand $rest more not shown" else ""
            return "screen ${encodeSeen(reading.generation)}\n" + lines.joinToString("\n") + more
        }

        /**
         * What can be done with it.
         *
         * A password field is named as one and not as somewhere to type: its
         * value never left prune, and telling a model to fill it in is telling
         * it to invent one.
         */
        private fun doing(sighting: Sighting): String = when {
            sighting.isPassword -> "password"
            sighting.isEditable -> "type    "
            sighting.isClickable -> "tap     "
            // After tap: a node that is both is rarely a list, and tapping is
            // the one that does something irreversible.
            sighting.isScrollable -> "scroll  "
            else -> "        "
        }
    }
}

/** Tap what a handle names. */
class TapTool(private val phone: Phone) : Tool {
    override val name = "tap"

    override val purpose =
        "Tap something on the screen. Give the handle from a read_screen line " +
            "and the screen id it was printed under. If the screen has changed " +
            "since, the tap is refused and you are shown what is there now."

    override val schema = """
        {"type":"object","properties":{
        "handle":{"type":"string","description":"A handle exactly as read_screen printed it."},
        "screen":{"type":"string","description":"The screen id it was printed under."}},
        "required":["handle","screen"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains no capability: the accessibility service is granted once from
     *  Settings rather than asked for per turn. May take as long as the
     *  framework takes to dispatch a click. */
    override suspend fun run(arguments: String): String {
        // Refused here rather than resolved. A handle this build did not write
        // would otherwise be assembled with defaults and act on something.
        val handle = decode(Tools.field(arguments, "handle"))
            ?: return "that is not a handle. Use one exactly as read_screen printed it."
        val seen = decodeSeen(Tools.field(arguments, "screen"))
            ?: return "that is not a screen id. Use the one at the top of a read_screen answer."

        return say(phone, "tapped", phone.tap(handle, seen))
    }

    companion object {
        /**
         * What happened, for the model to read.
         *
         * Takes the phone rather than a reading because an unreadable screen
         * has two causes needing opposite advice, and only the phone knows
         * which; see [ReadScreenTool.unreadable]. Asked for lazily: the common
         * path never reaches it.
         *
         * # Rely
         * Called from the turn loop, straight after the action it describes.
         * Suspends only on the paths with no reading to show, where it asks
         * [Phone.attached], which reads nothing and touches no tree.
         *
         * @param did what the tool actually did, as a past participle, so the
         *   same word reads in all three sentences below. Not defaulted: five
         *   tools share this function and a default is how four of them came to
         *   report that they had tapped something (#518). A caller that has to
         *   supply the word cannot forget to.
         */
        suspend fun say(phone: Phone, did: String, done: Done?): String = when (done) {
            null -> ReadScreenTool.unreadable(phone.attached())

            is Done.Did ->
                "$did. The screen may still be settling; this is it now.\n\n" +
                    after(phone, done.now)

            // Not a failure of the action, and worth wording as an instruction
            // rather than an error: the answer already contains what to do.
            is Done.Moved ->
                "the screen changed before that could happen, so nothing was " +
                    "$did. This is what is there now.\n\n" +
                    after(phone, done.now)

            is Done.Lost -> lost(did, done.resolution)

            is Done.Refused ->
                "that is on the screen and could not be $did: ${done.why}"
        }

        /**
         * The screen an action left behind, or why it could not be seen.
         *
         * The action itself succeeded in both cases, which is why this is not
         * an error: what failed is the look afterwards, and a model that is
         * told to read again will get one.
         */
        private suspend fun after(phone: Phone, now: Reading?): String =
            now?.let { ReadScreenTool.describe(it) }
                ?: ReadScreenTool.unreadable(phone.attached())

        private fun lost(did: String, resolution: Resolution): String = when (resolution) {
            is Resolution.Ambiguous ->
                "that handle matches ${resolution.count} things on the screen, so " +
                    "nothing was $did. Read the screen again and use a handle " +
                    "that names one of them."

            Resolution.Unusable ->
                "that handle does not describe anything to look for. Use one " +
                    "exactly as read_screen printed it."

            // Missing, and Found cannot reach here.
            else ->
                "that is still the screen and the thing that handle names is not " +
                    "on it any more. Read it again."
        }
    }
}

/** Fill something in. */
class TypeTextTool(private val phone: Phone) : Tool {
    override val name = "type_text"

    override val purpose =
        "Put text into a field on the screen. Give the handle from a read_screen " +
            "line marked `type` and the screen id it was printed under. This " +
            "REPLACES whatever the field already contains rather than adding to " +
            "the end, so to change part of it, write out the whole new value."

    override val schema = """
        {"type":"object","properties":{
        "handle":{"type":"string","description":"A handle from a line marked `type`."},
        "screen":{"type":"string","description":"The screen id it was printed under."},
        "text":{"type":"string","description":"What the field should say afterwards."}},
        "required":["handle","screen","text"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains no capability, as [TapTool]. May take as long as the framework
     *  takes to set text and read the screen again. */
    override suspend fun run(arguments: String): String {
        val handle = decode(Tools.field(arguments, "handle"))
            ?: return "that is not a handle. Use one exactly as read_screen printed it."
        val seen = decodeSeen(Tools.field(arguments, "screen"))
            ?: return "that is not a screen id. Use the one at the top of a read_screen answer."

        // Absent and empty are told apart. Empty is somebody clearing a field;
        // absent is a call that forgot half of itself, and answering it by
        // clearing the field would be doing something nobody asked for.
        val text = Tools.field(arguments, "text")
        if (text.isEmpty() && !arguments.contains("\"text\"")) {
            return "no text was given. To empty the field, pass an empty string."
        }

        return TapTool.say(phone, "typed", phone.type(handle, seen, text))
    }
}

/** Press one of the system's own buttons. */
class NavigateTool(private val phone: Phone) : Tool {
    override val name = "navigate"

    override val purpose =
        "Press one of the phone's own buttons: back, home, recents, or " +
            "notifications. What back does depends on where you press it: from " +
            "an app's first screen it leaves the app, and over a keyboard it " +
            "closes the keyboard. Read the answer to see where you ended up."

    override val schema = """
        {"type":"object","properties":{"where":{"type":"string",
        "enum":["back","home","recents","notifications"],
        "description":"Which button to press."}},"required":["where"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains no capability, as [TapTool]. */
    override suspend fun run(arguments: String): String {
        val way = Way.of(Tools.field(arguments, "where"))
            ?: return "there is no button called that. Try one of: ${Way.words}."

        return TapTool.say(phone, "pressed", phone.navigate(way))
    }
}

/** Move a list through its content. */
class ScrollTool(private val phone: Phone) : Tool {
    override val name = "scroll"

    override val purpose =
        "Scroll a list, page or carousel. Give the handle from a read_screen " +
            "line marked `scroll`. Forward means onward through the content, " +
            "down a list, or sideways through a carousel. If it is already at " +
            "the end you are told so rather than it failing."

    override val schema = """
        {"type":"object","properties":{
        "handle":{"type":"string","description":"A handle from a line marked `scroll`."},
        "screen":{"type":"string","description":"The screen id it was printed under."},
        "direction":{"type":"string","enum":["forward","back"],
        "description":"Onward through the content, or back towards the start."}},
        "required":["handle","screen","direction"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains no capability, as [TapTool]. */
    override suspend fun run(arguments: String): String {
        val handle = decode(Tools.field(arguments, "handle"))
            ?: return "that is not a handle. Use one exactly as read_screen printed it."
        val seen = decodeSeen(Tools.field(arguments, "screen"))
            ?: return "that is not a screen id. Use the one at the top of a read_screen answer."
        val onward = Onward.of(Tools.field(arguments, "direction"))
            ?: return "there is no direction called that. Try one of: ${Onward.words}."

        return TapTool.say(phone, "scrolled", phone.scroll(handle, seen, onward))
    }
}

/** Start an app by name. */
class OpenAppTool(private val phone: Phone) : Tool {
    override val name = "open_app"

    override val purpose =
        "Open an app by its name, as it appears under its icon. Answers with the " +
            "screen afterwards, which will often catch the app still starting, so " +
            "read it again if it looks unfinished."

    override val schema = """
        {"type":"object","properties":{"name":{"type":"string",
        "description":"The app's name, as a person would say it."}},"required":["name"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains no capability, as [TapTool]. */
    override suspend fun run(arguments: String): String {
        val wanted = Tools.field(arguments, "name").trim()
        if (wanted.isEmpty()) return "no app was named, so nothing was opened"

        val installed = phone.apps()
            ?: return "the list of apps could not be read"

        return when (val found = matching(wanted, installed)) {
            // Without listing the phone: a hundred installed apps is not an
            // error message, and somebody looking at their own home screen can
            // supply the exact name.
            emptyList<Launchable>() ->
                "there is no app called \"$wanted\" on this phone. Try its exact " +
                    "name as it appears under its icon."

            else -> if (found.size > 1) {
                "more than one app matches \"$wanted\": " +
                    found.joinToString(", ") { it.label } +
                    ". Name one of those exactly."
            } else {
                TapTool.say(phone, "opened", phone.open(found.single().packageName))
            }
        }
    }
}

/**
 * Wait until the screen changes.
 *
 * @property pause how to wait between looks. Injected so a test drives the loop
 *   without spending the time: the thing worth testing is how many times it
 *   looks and when it stops, not that a delay delays.
 */
class WaitForChangeTool(
    private val phone: Phone,
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : Tool {
    override val name = "wait_for_change"

    override val purpose =
        "Wait for the screen to change, after something you did that takes a " +
            "moment: a page loading, an app starting. Give the screen id you " +
            "are waiting to move away from. If it has not changed by the time " +
            "the wait is up you are told that, which is an answer in itself."

    override val schema = """
        {"type":"object","properties":{
        "screen":{"type":"string","description":"The screen id to wait for a change from."},
        "seconds":{"type":"integer",
        "description":"How long to wait at most, 1 to $LONGEST. Default $USUAL."}},
        "required":["screen"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains no capability. Suspends for up to the requested wait, which is
     *  a person waiting, so the ceiling is low on purpose. */
    override suspend fun run(arguments: String): String {
        val from = decodeSeen(Tools.field(arguments, "screen"))
            ?: return "that is not a screen id. Use the one at the top of a read_screen answer."

        val asked = Tools.field(arguments, "seconds").toIntOrNull() ?: USUAL
        if (asked < 1 || asked > LONGEST) {
            return "seconds must be between 1 and $LONGEST; $asked is outside it"
        }

        var last: Reading? = null
        repeat(asked * 1000 / INTERVAL) {
            val now = phone.read() ?: return ReadScreenTool.unreadable(phone.attached())
            if (now.generation != from) {
                return "the screen changed.\n\n" + ReadScreenTool.describe(now)
            }
            last = now
            pause(INTERVAL.toLong())
        }

        // Not a failure. "Still the same screen" is what somebody asks when
        // they want to know whether a tap did anything, and reported as an
        // error a model retries the tap instead of looking for another reason.
        // Non-null by construction: the loop above runs at least four times and
        // returns early on the first unreadable look, so reaching here means one
        // succeeded. Written as a fallback rather than an assertion because the
        // fallback costs a line and a wrong assertion costs a turn.
        val seen = last ?: return ReadScreenTool.unreadable(phone.attached())
        return "the screen has not changed in $asked seconds; it is still this " +
            "one.\n\n" + ReadScreenTool.describe(seen)
    }

    companion object {
        /** Between looks. Short enough to feel immediate, long enough not to spin. */
        const val INTERVAL = 250

        /** When nobody says. A few seconds covers a page load. */
        const val USUAL = 5

        /** The ceiling. Past this somebody would rather be told nothing happened. */
        const val LONGEST = 15
    }
}

/** Search the screen for something by what it says. */
class FindOnScreenTool(private val phone: Phone) : Tool {
    override val name = "find_on_screen"

    override val purpose =
        "Search the screen for something by the words on it. Use it when " +
            "read_screen said it left lines out, or when the screen is long. " +
            "this searches all of it rather than the part that was printed."

    override val schema = """
        {"type":"object","properties":{"text":{"type":"string",
        "description":"Words to look for, as they appear on screen."}},"required":["text"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains no capability. Reads the screen once. */
    override suspend fun run(arguments: String): String {
        val wanted = Tools.field(arguments, "text").trim()
        // A blank search matching every line is read_screen with extra steps,
        // and on a long screen it is read_screen without the limit.
        if (wanted.isEmpty()) return "no words were given, so nothing was looked for"

        phone.barredNow()?.let { return it }
        val reading = phone.read() ?: return ReadScreenTool.unreadable(phone.attached())

        // The whole reading, not the printed part. The handles past
        // read_screen's limit exist; they are only not on the page.
        val found = reading.seen.filter {
            it.label?.contains(wanted, ignoreCase = true) == true
        }

        // Not the same as a screen that could not be read, which the line
        // above already answers differently.
        if (found.isEmpty()) {
            return "nothing on this screen says \"$wanted\". It has " +
                "${reading.seen.size} things on it; read_screen shows the first " +
                "${ReadScreenTool.LIMIT}."
        }

        // With the screen id: a handle without one cannot be acted on, and a
        // list to look at rather than use would be a worse answer than none.
        return ReadScreenTool.describe(Reading(reading.generation, found))
    }
}
