// ScreenTools.kt — looking at the screen, and the seam that reaches one.
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
//
// The seam is why this is in core/ at all. DrivingService is an app-module
// class holding framework types, and everything here is prose, rendering and
// refusals — none of which needs one. The split every capability in Phase 2
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
     * @property now the screen afterwards, which may still be settling — a tap
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
     * What is on screen now.
     *
     * # Rely
     * Called from the turn loop. Fetches a tree rather than answering from one
     * held, so it costs a round trip into the framework each time.
     *
     * @return null when the screen cannot be read at all — the service off, or
     *   no window focused.
     */
    suspend fun read(): Reading?

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
     * @return null on [read]'s terms — the screen unreadable, which is not the
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
}

/**
 * A button the system owns.
 *
 * Closed, and the refusal lists it. A model writing `go_back` should be told
 * the words that work, the way ToolBox answers an unknown tool with the names
 * that exist — guessing at a near miss is the mistake resolve refuses to make.
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
 *   reason — a generation assembled with a default counter would compare equal
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
            "changes — a handle from an older reading is refused, not guessed at."

    override val schema = """{"type":"object","properties":{}}"""

    /** # Rely
     *  Obtains no capability. The accessibility service is granted once, from
     *  Settings, rather than asked for per turn — so there is no dialog here
     *  and nothing to validate before one. */
    override suspend fun run(arguments: String): String = describe(phone.read())

    companion object {
        /** Most lines shown. Past this a model is reading a layout dump. */
        const val LIMIT = 60

        /** Deepest indent. Beyond it the nesting says nothing a reader uses. */
        private const val DEEPEST = 6

        /** What a reading looks like, or what its absence does. */
        fun describe(reading: Reading?): String {
            // Both reasons, in the order somebody would try them. The second is
            // the one how-the-agent-drives.md calls the failure with no error
            // attached: on a sideloaded build the toggle is greyed until
            // restricted settings are cleared, and nothing says so.
            if (reading == null) {
                return "the screen could not be read. Turn the assistant on in " +
                    "Settings > Accessibility > WattRouter. If the switch there is " +
                    "greyed out, open Settings > Apps > WattRouter, then the menu in " +
                    "the corner, and allow restricted settings first."
            }
            // Distinguishable from the above, for the reason every tool here
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

        return say(phone.tap(handle, seen))
    }

    companion object {
        /** What happened, for the model to read. */
        fun say(done: Done?): String = when (done) {
            null -> ReadScreenTool.describe(null)

            is Done.Did ->
                "tapped. The screen may still be settling; this is it now.\n\n" +
                    ReadScreenTool.describe(done.now)

            // Not a failure of the tap, and worth wording as an instruction
            // rather than an error: the answer already contains what to do.
            is Done.Moved ->
                "the screen changed before that could happen, so nothing was " +
                    "tapped. This is what is there now.\n\n" +
                    ReadScreenTool.describe(done.now)

            is Done.Lost -> lost(done.resolution)

            is Done.Refused ->
                "that is on the screen and could not be tapped: ${done.why}"
        }

        private fun lost(resolution: Resolution): String = when (resolution) {
            is Resolution.Ambiguous ->
                "that handle matches ${resolution.count} things on the screen, so " +
                    "nothing was tapped. Read the screen again and use a handle " +
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

        return TapTool.say(phone.type(handle, seen, text))
    }
}

/** Press one of the system's own buttons. */
class NavigateTool(private val phone: Phone) : Tool {
    override val name = "navigate"

    override val purpose =
        "Press one of the phone's own buttons: back, home, recents, or " +
            "notifications. What back does depends on where you press it — from " +
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

        return TapTool.say(phone.navigate(way))
    }
}
