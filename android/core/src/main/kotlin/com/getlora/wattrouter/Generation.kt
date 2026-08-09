// Generation.kt — which reading of the screen something came from.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Generation   A reading, named.
//   shapeOf      What counts as a different screen.
//   Generations  Handing out readings, and saying which is current.
//
// A pair rather than a counter, and how-the-agent-drives.md argues why: the
// system kills and restarts an accessibility service routinely, and a counter
// restarting at zero lets a handle read before the kill match a tree read after
// it. That is the one hole here producing a wrong tap rather than a refusal, and
// the coincidence is likeliest early in a session, at low numbers.
//
// The counter follows the tree's shape and not its content. A clock, a progress
// bar and an unread badge change several times a second, and a generation moving
// with them would make every handle stale before the model could use one — a
// system that refuses everything is indistinguishable from a broken one.
//
// That is only safe because of #405. A scrolled list keeps its ids and its shape
// and replaces its text, so it keeps its generation too; what catches a handle
// pointing into it is resolution refusing when the field that would tell the
// rows apart matches none of them. Neither decision is safe on its own.

package com.getlora.wattrouter

import kotlin.random.Random

/**
 * A reading of the screen.
 *
 * @property epoch which life of the service read it. Fixed at start and never
 *   reused, so a handle cannot survive a restart by arithmetic.
 * @property counter which reading within that life. Zero is before any, which
 *   no handle can carry.
 */
data class Generation(val epoch: String, val counter: Long)

/**
 * What counts as a different screen.
 *
 * Shape rather than content: which nodes exist, where they sit, and what they
 * are. Invisible nodes are left out, consistently with [prune] and [resolve] —
 * a drawer opening is a new screen, and one that was always shut is not part of
 * the old one.
 */
fun shapeOf(root: Node): Int {
    var shape = 1
    val pending = ArrayDeque(listOf(root to 0))

    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        if (!node.isVisible) continue

        shape = shape * 31 + markOf(node) + depth
        for (at in node.children.indices.reversed()) {
            pending.addLast(node.children[at] to depth + 1)
        }
    }
    return shape
}

/**
 * One node's contribution.
 *
 * The child count is in it deliberately. A row inserted at the top of a list
 * changes no node's own description and shifts every sibling index below it,
 * which is exactly when the handles pointing there should stop being current.
 */
private fun markOf(node: Node): Int {
    var mark = nameOf(node.viewId).hashCode()
    mark = mark * 31 + node.role.hashCode()
    mark = mark * 31 + node.children.size
    mark = mark * 31 + flagsOf(node)
    return mark
}

private fun flagsOf(node: Node): Int =
    (if (node.isClickable) 1 else 0) or
        (if (node.isEditable) 2 else 0) or
        (if (node.isPassword) 4 else 0)

/**
 * Handing out readings, and saying which is current.
 *
 * One per life of the service. Two of these in a process would hand out two
 * counters under one epoch, which is the collision the epoch exists to prevent.
 */
class Generations(private val epoch: String) {
    private var counter = 0L
    private var shape: Int? = null

    /** The reading a handle would have to carry to be acted on now. */
    val current: Generation
        get() = Generation(epoch, counter)

    /**
     * Note a tree, and answer the reading it belongs to.
     *
     * The same screen read twice is one reading: the counter moves when the
     * shape does, so a model that reads the screen again without anything
     * having changed keeps the handles it already has.
     */
    fun reading(root: Node): Generation {
        val now = shapeOf(root)
        if (now != shape) {
            shape = now
            counter++
        }
        return current
    }

    /**
     * Whether something read then may be acted on now.
     *
     * A generation from another life of the service fails on the epoch, however
     * its counter compares — which is the whole reason there are two fields.
     */
    fun isCurrent(generation: Generation): Boolean = generation == current

    companion object {
        /**
         * A fresh life of the service.
         *
         * Random rather than a clock. A phone's clock moves backwards on a
         * time-zone change and on an NTP correction, and two lives sharing an
         * epoch is the one case here that ends in a wrong tap rather than a
         * refusal.
         */
        fun fresh(random: Random = Random.Default): Generations =
            Generations(random.nextLong().toULong().toString(RADIX))

        /** Base 36, so an epoch is short enough to sit in a handle. */
        private const val RADIX = 36
    }
}
