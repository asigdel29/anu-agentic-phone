// Prune.kt: the nodes worth telling a model about.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  A scroll container says something after all: without
//                          it the list is dropped and only its rows survive.
//
// Contents
//   Sighting  A node as it may be shown.
//   prune     Which nodes those are.
//
// A real tree is layout: nested frames, scroll containers, wrappers around
// wrappers. Whole, it is mostly noise, and the noise costs more than tokens:
// the node that matters is harder to find in three hundred lines than in twelve.
//
// Sighting carries what may be shown rather than the node it came from, and
// that is the safety rule in how-the-agent-drives.md taking its strongest form.
// A password field's value does not leave this file: a renderer written later
// cannot reach for it, because there is nothing there to reach for.

package com.getlora.wattrouter

/**
 * A node as it may be shown.
 *
 * @property handle how to find it again, which is the only thing the model is
 *   given to act with.
 * @property label what it says: its text, or its description when it has none.
 *   Null for a password field, and for a control that is only an icon with
 *   neither.
 * @property depth how far down it sat, for a rendering that wants to indent.
 *   Depth in the *pruned* tree rather than the real one: a node six containers
 *   deep and one meaningful parent deep is one level in, and indenting it six
 *   would describe the layout rather than the page.
 */
data class Sighting(
    val handle: Handle,
    val role: String,
    val label: String?,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isPassword: Boolean = false,
    val depth: Int = 0,
)

/**
 * Whether a node says or does anything.
 *
 * Everything else is a container: dropped, while its children carry on being
 * walked. Dropping one without flattening would take the page with it.
 */
private fun Node.saysSomething(): Boolean =
    isClickable || isEditable || isScrollable ||
        !text.isNullOrBlank() || !description.isNullOrBlank()

/** Where the walk is: a node, and what it inherited from above. */
private data class Descent(
    val node: Node,
    val siblingIndex: Int,
    val depth: Int,
    val keptParent: Node?,
)

/**
 * The nodes worth showing, in the order they appear on the page.
 *
 * Iterative, as `resolve` is and for the same reason: a hierarchy is shallow in
 * practice and deep in the case that matters, and a stack overflow while
 * reading a screen is a crash in a service the system restarts silently.
 */
fun prune(root: Node): List<Sighting> {
    val found = mutableListOf<Sighting>()
    val pending = ArrayDeque(listOf(Descent(root, 0, 0, null)))

    while (pending.isNotEmpty()) {
        val (node, siblingIndex, depth, keptParent) = pending.removeLast()

        // Invisible goes, and everything under it: a subtree beneath something
        // not on screen is not on screen either, and resolve() already refuses
        // to find its way into one.
        if (!node.isVisible) continue

        val keep = node.saysSomething() && !wrappedBy(keptParent, node)
        if (keep) {
            found += Sighting(
                handle = handleFor(node, siblingIndex),
                role = node.role,
                // The value never leaves. A model that cannot see there is a
                // password field cannot ask the person to fill it in, so the
                // field is reported; what it holds is not.
                label = if (node.isPassword) {
                    null
                } else {
                    node.text?.takeIf { it.isNotBlank() }
                        ?: node.description?.takeIf { it.isNotBlank() }
                },
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isPassword = node.isPassword,
                depth = depth,
            )
        }

        // Reversed onto a stack, so they come back off in the order somebody
        // reading the page would meet them.
        val below = if (keep) depth + 1 else depth
        val parent = if (keep) node else keptParent
        for (at in node.children.indices.reversed()) {
            pending.addLast(Descent(node.children[at], at, below, parent))
        }
    }
    return found
}

/**
 * Whether a node is the label inside the control that was already kept.
 *
 * The common shape in every toolkit: a clickable Button whose only content is a
 * TextView saying the same thing. Both say something, so both survive on their
 * own, and the model is shown two nodes with identical text of which only one
 * can be tapped. It taps the other, nothing happens, and nothing says why.
 *
 * The outer one wins when it is the one that can be acted on. Where the inner
 * is clickable and the outer is not, the inner is the control and the outer was
 * the wrapper, so the test is on the action rather than on the position.
 */
private fun wrappedBy(keptParent: Node?, node: Node): Boolean {
    val parent = keptParent ?: return false
    if (node.isClickable || node.isEditable) return false

    val mine = node.text?.takeIf { it.isNotBlank() } ?: node.description
    val theirs = parent.text?.takeIf { it.isNotBlank() } ?: parent.description
    return mine != null && mine == theirs
}
