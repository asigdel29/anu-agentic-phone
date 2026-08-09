// ScreenTools.kt — looking at the screen, and the seam that reaches one.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Phone           The screen, as a tool reaches it.
//   encodeSeen      A generation as the model reads one.
//   decodeSeen      One back, or nothing.
//   ReadScreenTool  Looking.
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
     * Aim at a handle, against the screen as it is now.
     *
     * # Rely
     * As [read], and answers on the same terms.
     */
    suspend fun aim(at: Handle, from: Generation): Aim?
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
            else -> "        "
        }
    }
}
