// Resolve.kt — finding again the node a handle describes.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  Narrowing that empties refuses instead of skipping.
//                          Skipping tapped the wrong row of a recycled list.
//
// Contents
//   Resolution  What looking found.
//   resolve     Looking, against a tree fetched now.
//
// This is the half that decides whether a recipe is safer than a coordinate or
// only more complicated. Two readings of "match a handle" are both wrong.
//
// Requiring every field produces false refusals: a button relabelled from "Send"
// to "Send now" is the same button, and a handle carrying its id should find it.
// Taking the best partial match produces a tap on the wrong thing, which is what
// this design exists to prevent.
//
// So the most durable field the handle has is a hard requirement, and the rest
// narrow only while more than one candidate is left. The relabelled button is
// answered by *stopping* at one candidate rather than by tolerating a field that
// disagrees: once there is one, nothing weaker is consulted.
//
// Where more than one survives and the field that would separate them matches
// none, that is a refusal. It was a skip, and the skip tapped the wrong row of a
// recycled list — see the history line above and #405.

package com.getlora.wattrouter

/** What looking for a handle found. */
sealed interface Resolution {
    /** Exactly one node. */
    data class Found(val node: Node) : Resolution

    /** Nothing on screen matches what the handle is most sure of. */
    data object Missing : Resolution

    /**
     * More than one, which is a refusal rather than a choice. The model can
     * recover from being told it was ambiguous, and cannot recover from a tap
     * on the wrong row.
     */
    data class Ambiguous(val count: Int) : Resolution

    /**
     * The handle described nothing to look for. Its own answer rather than
     * [Missing]: it did not fail to find something, it never named one.
     */
    data object Unusable : Resolution
}

/** A node and where it sat among its parent's children. */
private data class Placed(val node: Node, val siblingIndex: Int)

/**
 * Find the node a handle describes, in a tree fetched now.
 *
 * @param root the tree as it is at this moment, never the one the handle came
 *   from — between reading a screen and acting on it the screen has had time to
 *   change, and noticing is the point.
 */
fun resolve(root: Node, handle: Handle): Resolution {
    if (!handle.isFindable) return Resolution.Unusable

    // Invisible nodes are not candidates. A tree carries what is laid out
    // rather than what is on screen — a collapsed drawer, the page behind a
    // dialog — and acting on one silently does nothing, which is the failure
    // furthest from a refusal.
    val onScreen = descend(root).filter { it.node.isVisible }

    // The most durable field the handle has, as a hard requirement. Nothing
    // matching it means the node named is not here.
    var candidates = when {
        !handle.viewId.isNullOrBlank() -> onScreen.filter { nameOf(it.node.viewId) == handle.viewId }
        !handle.text.isNullOrBlank() -> onScreen.filter { it.node.text == handle.text }
        else -> onScreen.filter { it.node.description == handle.description }
    }
    if (candidates.isEmpty()) return Resolution.Missing

    // Then the rest, in the same order, while there is anything to settle.
    for (narrow in narrowing(handle)) {
        // Stopping here is what forgives a relabelled button: with one
        // candidate left, nothing weaker is consulted and a changed label
        // cannot lose it.
        if (candidates.size <= 1) break

        val fewer = candidates.filter(narrow)
        // But with several left, a field matching none of them is not a field
        // to ignore — it is the evidence that would have told them apart,
        // saying none of them is the one. A recycled list keeps its ids and
        // its shape and replaces its text, so ignoring this and falling
        // through to the sibling index taps whatever scrolled into that row.
        if (fewer.isEmpty()) return Resolution.Missing
        candidates = fewer
    }

    return when (candidates.size) {
        1 -> Resolution.Found(candidates.first().node)
        else -> Resolution.Ambiguous(candidates.size)
    }
}

/**
 * The filters that narrow, in the order they decay.
 *
 * Text before description before position, and the role among them: it changes
 * only when a screen is rebuilt, but it separates nothing on its own, which is
 * why it narrows here and is not something to search on.
 */
private fun narrowing(handle: Handle): List<(Placed) -> Boolean> = buildList {
    if (!handle.text.isNullOrBlank()) add { it.node.text == handle.text }
    if (!handle.description.isNullOrBlank()) add { it.node.description == handle.description }
    if (handle.role.isNotBlank()) add { it.node.role == handle.role }
    add { it.siblingIndex == handle.siblingIndex }
}

/**
 * Every node in the tree, each with the index it sat at.
 *
 * Iterative rather than recursive: a view hierarchy is shallow in practice and
 * deep in the one case that matters, which is a page built by nesting layouts,
 * and a stack overflow while reading a screen is a crash in a service the system
 * restarts silently.
 */
private fun descend(root: Node): List<Placed> {
    val found = mutableListOf<Placed>()
    val pending = ArrayDeque(listOf(Placed(root, 0)))
    while (pending.isNotEmpty()) {
        val here = pending.removeFirst()
        found += here
        here.node.children.forEachIndexed { at, child -> pending.addLast(Placed(child, at)) }
    }
    return found
}
