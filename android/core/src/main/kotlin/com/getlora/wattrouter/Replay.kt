// Replay.kt: what a turn did to the phone, kept so somebody can look back.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// Contents
//   Acted   One action, and the screen it left behind.
//   Replay  The steps of a turn, bounded.
//
// The transcript carries tool results as prose. A turn that tapped six things
// leaves six lines and no way to see the six screens, which is the difference
// between reviewing what an agent did and reading its account of it. #598 calls
// that the strongest after-the-fact answer and the most expensive, and it is
// the second half of that issue now that #629 has built the capture path.
//
// The cost is the design rather than an afterthought. A capture is a
// full-resolution PNG as base64, and a turn may act twenty-five times, so
// keeping one per action is tens of megabytes held in a process that is also
// running a model conversation. Two bounds answer that and both are stated
// rather than tuned: only actions are captured, never reads, and only the most
// recent MOST are kept.
//
// This is the store alone. Recorded, the Phone that fills it, is the next
// change, and the two are separate because what is worth keeping and how much
// of it is a different question from which calls count as doing something.

package com.getlora.wattrouter

/**
 * One thing the agent did, and the screen it left behind.
 *
 * Named for the doing rather than for its place in a list, because `Step` is
 * already a model in a routing chain, and two of those in one package is a name
 * a reader has to disambiguate every time they meet it.
 *
 * @property did what happened, in the past tense: "tapped send". [Confirmed]
 *   asks with the same words in the present tense, which is deliberate: a
 *   person who approved "tap send" and later reads "tapped send" is reading one
 *   thing twice rather than two things that have to be matched up.
 * @property screen the display after it, or null when there was nothing to
 *   capture. Null is ordinary rather than a failure: the service can be off and
 *   a window may not have arrived, and what was done is worth listing without a
 *   picture to show for it.
 */
data class Acted(val did: String, val screen: Image? = null)

/**
 * What a turn did, most recent last.
 *
 * # Atomic
 * Not synchronised, and it does not need to be: [Agent] runs tools one at a
 * time and in the order the model asked, which is the rely [Tool.run] already
 * states and the reason a write then a read of one path is a sequence rather
 * than a race.
 */
class Replay(private val most: Int = MOST) {
    private val backing = ArrayDeque<Acted>()

    /** The steps, oldest first. */
    val steps: List<Acted> get() = backing.toList()

    /**
     * Start again.
     *
     * Called by [Agent] at the top of a turn, where the budget is reset and for
     * the same reason: a resumed turn showing the previous turn's screens is a
     * replay of the wrong thing.
     */
    fun beginTurn() = backing.clear()

    /**
     * Keep one, dropping the oldest if the bound is reached.
     *
     * The oldest goes rather than the newest being refused, because a replay is
     * read backwards from what just happened: somebody looking at one wants the
     * end of the turn far more often than its beginning.
     */
    fun add(step: Acted) {
        backing.addLast(step)
        while (backing.size > most) backing.removeFirst()
    }

    companion object {
        /**
         * Steps kept.
         *
         * Six rather than the budget's twenty-five, and the difference is the
         * whole of the memory argument. A capture is a full-resolution PNG as
         * base64, so twenty-five of them is tens of megabytes held beside a
         * conversation. Six covers what a person actually looks back over,
         * which is the end of a turn that went wrong.
         */
        const val MOST = 6
    }
}
