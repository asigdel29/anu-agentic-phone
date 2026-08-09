// Screen.kt — what is on screen, in a shape a test can hold.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Node    One thing on screen, as much of one as the agent needs.
//   Handle  A recipe for finding that thing again.
//
// AccessibilityNodeInfo is final, has no public constructor, and comes from a
// service. Written against it, every rule in Phase 3 — the handle, the pruning,
// the resolution, the refusals — would be checkable only on a device. This is
// the seam Conversation.kt took for the same reason, and the stakes are higher:
// this is the largest body of rules in the milestone.
//
// A handle is a recipe rather than a coordinate or an index, and
// how-the-agent-drives.md argues why: a remembered coordinate breaks silently
// and taps whatever moved into the spot, while a remembered recipe fails loudly.

package com.getlora.wattrouter

/**
 * One thing on screen.
 *
 * Deliberately smaller than what the framework offers. Everything here is
 * either part of a handle, part of a pruning decision, or a refusal — and a
 * field carried without a use is a field the conformance has to keep correct
 * for nobody.
 */
interface Node {
    /**
     * The resource id, stripped of its package. See [nameOf]: the same node
     * carries a different prefix in a different app's flow.
     */
    val viewId: String?

    /** The class, coarsely: `button`, `field`, `list`, `text`. */
    val role: String

    /** What it says. */
    val text: String?

    /** The content description, where a node has one and no text. */
    val description: String?

    val isClickable: Boolean

    val isEditable: Boolean

    /**
     * Whether the framework marks this a password field. Never read, and the
     * safety layer refuses it rather than the renderer omitting it — a rule
     * that lives where the value is used is one the next reader moves.
     */
    val isPassword: Boolean

    /** Whether it is actually on screen, rather than merely in the tree. */
    val isVisible: Boolean

    val children: List<Node>
}

/**
 * A resource id as a handle records it.
 *
 * `com.example.app:id/send_button` and `send_button` are the same node, and the
 * prefix changes when a screen appears inside another app's flow — a handle
 * keeping the whole thing fails to match the node it describes.
 *
 * @return the name after the last `/`, or null when there was no id. Blank is
 *   null too: some nodes carry an empty id, and an empty string that matches
 *   every other empty string is worse than no field at all.
 */
fun nameOf(viewId: String?): String? =
    viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

/**
 * A recipe for finding a node again.
 *
 * Ordered by durability in [how-the-agent-drives.md]: the id survives text
 * changes, translations and font scaling; text survives a layout change; the
 * sibling index survives almost nothing and is last for that reason.
 */
data class Handle(
    val viewId: String? = null,
    val role: String = "",
    val text: String? = null,
    val description: String? = null,
    /**
     * Where it sat among its siblings.
     *
     * Recorded whether or not it is needed. It costs nothing and it is the only
     * field separating two identical rows in a list — which is what a list is,
     * so this is the common case rather than the corner one.
     */
    val siblingIndex: Int = 0,
) {
    /**
     * Whether this handle says anything a search could use.
     *
     * A handle of nothing but a sibling index describes a position, which is
     * the thing this design exists to not hand the model. It is refused where
     * it is made rather than where it is resolved, so the refusal names the
     * node it came from.
     */
    val isFindable: Boolean
        get() = !viewId.isNullOrBlank() ||
            !text.isNullOrBlank() ||
            !description.isNullOrBlank()
}

/**
 * The handle for a node.
 *
 * @param node what was seen.
 * @param siblingIndex where it sat among its parent's children. Passed in
 *   rather than read off the node, because a [Node] does not know its parent
 *   and giving it one would make every conformance keep a back-reference
 *   correct for this one field.
 */
fun handleFor(node: Node, siblingIndex: Int = 0): Handle = Handle(
    viewId = nameOf(node.viewId),
    role = node.role,
    // Taken as it reads rather than normalised. A handle describes what was
    // seen, and a model that read "Send" should be able to write "Send".
    text = node.text?.takeIf { it.isNotBlank() },
    description = node.description?.takeIf { it.isNotBlank() },
    siblingIndex = siblingIndex,
)
