// Scrollback.kt: what the terminal ran, kept so somebody can look back.
//
// History
//   2026-09-17  A. Sigdel  Created with #713.
//
// Contents
//   KeptCommand  One command, and what came back of it.
//   Scrollback   The commands, bounded, and never cleared per turn.
//
// Replay.kt's shape with the cost argument taken out, because the thing kept
// here is text. A replay is bounded at six because a capture is a
// full-resolution PNG as base64; a kept command is at most OUTPUT_LIMIT
// characters and Terminal already bounds it, so the bound here is loose where
// Replay's is tight. History is the point: somebody reading a scrollback wants
// what was run over the session, not the end of the last turn.
//
// Which is also why there is no beginTurn to call, ever. Replay clears at the
// top of a turn because a resumed turn showing the previous turn's screens is
// a replay of the wrong thing, and because each screenshot is tens of
// megabytes. A shell history wiped the moment the person stops watching is a
// history of the wrong thing, and text is cheap enough to keep.
//
// Bounded all the same, because unbounded is the failure mode Replay exists to
// prevent and a phone has no swap.

package com.getlora.wattrouter

/**
 * One command the terminal ran, and what came back of it.
 *
 * @property command what was run, as the model wrote it. Whole, and never a
 *   front of it, for [Shown]'s reason: an elision is where the second half of
 *   `ls; rm -rf .` hides.
 * @property ran what the terminal answered, [Ran.Refused] included: a command
 *   the shell would not start is exactly what somebody looking back wants to
 *   find. A command a gate refused does not arrive here at all, which is
 *   [Kept]'s position rather than this type's rule.
 */
data class KeptCommand(val command: String, val ran: Ran)

/**
 * What the terminal has run, most recent last, across turns.
 *
 * # Atomic
 * Not synchronised, and it does not need to be: [Agent] runs tools one at a
 * time and in the order the model asked, which is the rely [Terminal.run]
 * already states and the argument [Replay] makes for the same shape.
 */
class Scrollback(private val most: Int = MOST) {
    private val backing = ArrayDeque<KeptCommand>()

    /** The commands, oldest first, with what each came back. */
    val commands: List<KeptCommand> get() = backing.toList()

    /**
     * Keep one, dropping the oldest if the bound is reached.
     *
     * The oldest goes rather than the newest being refused, which is
     * [Replay.add]'s reason: what somebody looks for is the end.
     */
    fun add(kept: KeptCommand) {
        backing.addLast(kept)
        while (backing.size > most) backing.removeFirst()
    }

    companion object {
        /**
         * Commands kept.
         *
         * Loose where [Replay.MOST] is tight, and the difference is the unit.
         * A capture is megabytes; a command is at most [OUTPUT_LIMIT]
         * characters, so a hundred of them is under half a megabyte, and the
         * budget of twenty-five steps a turn is covered four times over. The
         * exact number is a guess said as one; that it sits above a turn is
         * not, since a history that forgets a turn the moment it ends is a
         * history cleared per turn by other means.
         */
        const val MOST = 100
    }
}
