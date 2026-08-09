// Viewing.kt — reading the screen and aiming at it through one counter.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Reading  What the screen said, and when.
//   Aim      What aiming at a handle found, before anything was done.
//   Viewing  Both, over one Generations.
//
// The piece that makes #407's generation load-bearing rather than decorative:
// an action against a reading that has moved is refused rather than attempted.
//
// Aiming fetches the tree and notes it, which is what advances the counter if
// the shape moved — so the check is not a separate call somebody can forget, and
// there is no window between checking and acting.
//
// And a refusal carries the new screen. A bare "that is stale" costs a round
// trip: the model reads again, gets fresh handles, acts, and on a page with
// anything dynamic the structure can move again in between. Handing back what is
// there now makes it one exchange instead of two, and takes the livelock away.

package com.getlora.wattrouter

/** What the screen said, and which reading it was. */
data class Reading(val generation: Generation, val seen: List<Sighting>)

/** What aiming at a handle found, before anything was done about it. */
sealed interface Aim {
    /** The node, current and unambiguous. */
    data class At(val node: Node) : Aim

    /**
     * The screen moved. Carries what it is now, so the answer is a new set of
     * handles rather than an instruction to go and ask for one.
     */
    data class Moved(val now: Reading) : Aim

    /**
     * This is still the screen, and the handle names nothing on it.
     *
     * Reachable only through a content change: a node disappearing moves the
     * shape, and that is [Moved]. What lands here is a recycled list row —
     * #405's case — or a handle the model invented.
     */
    data class Lost(val resolution: Resolution) : Aim
}

/**
 * The screen, read and aimed at through one counter.
 *
 * One per life of the service, holding one [Generations]. Two would hand out
 * two counters under one epoch, which is the collision the epoch exists to stop.
 */
class Viewing(private val generations: Generations) {

    /**
     * Read the screen.
     *
     * @param root the tree as it is now. Reading the same screen twice answers
     *   the same generation, so a model that looks again without anything
     *   having changed keeps the handles it already has.
     */
    fun read(root: Node): Reading = Reading(generations.reading(root), prune(root))

    /**
     * Aim at a handle, and say what stopped it if anything did.
     *
     * @param root a tree fetched now, never the one [from] came from.
     * @param from the generation the model was holding. Compared after the
     *   fresh tree has been noted, which is what makes a screen that changed in
     *   between a refusal rather than a resolution attempt.
     */
    fun aim(root: Node, at: Handle, from: Generation): Aim {
        val now = read(root)
        if (now.generation != from) return Aim.Moved(now)

        return when (val found = resolve(root, at)) {
            is Resolution.Found -> Aim.At(found.node)
            else -> Aim.Lost(found)
        }
    }
}
