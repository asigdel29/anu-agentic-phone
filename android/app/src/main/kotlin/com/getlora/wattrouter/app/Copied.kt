// Copied.kt — the framework's tree, as the rules read it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  Can keep the framework node beside its copy, because
//                          a copy is not something performAction can be called
//                          on and acting needs one.
//
// Contents
//   roleOf    A class name as a role a model can act on.
//   Copied    A Node holding values rather than a framework object.
//   snapshot  One screen, copied out.
//
// It copies rather than wraps, and that is the decision here. A Node backed by a
// live AccessibilityNodeInfo would read the tree lazily, which is wrong twice:
// the screen can change under a walk that takes several calls, so prune, resolve
// and shapeOf would each see a different one; and a node from getChild is the
// caller's to release, which a lazy reader has nowhere to do.
//
// Copying once means one consistent screen and a clear place to release.

package com.getlora.wattrouter.app

import android.view.accessibility.AccessibilityNodeInfo
import com.getlora.wattrouter.Node

/**
 * A class name as a role.
 *
 * By suffix rather than by exact name: every toolkit subclasses the framework
 * widgets, so `android.widget.Button` and `androidx.appcompat.widget.AppCompatButton`
 * are both buttons and neither is worth showing a model verbatim.
 *
 * @return one of a small closed set, or `view` for anything unrecognised —
 *   which is honest rather than a guess, and still lets a node be named.
 */
internal fun roleOf(className: CharSequence?): String {
    val name = className?.toString()?.substringAfterLast('.').orEmpty()
    return when {
        name.isEmpty() -> "view"
        name.endsWith("EditText") -> "field"
        // Before Button, and this is not tidiness: RadioButton and
        // ToggleButton both end in it, so a Button branch above these two
        // swallows them and the model is told to press what it should set.
        name.endsWith("RadioButton") -> "choice"
        name.endsWith("ToggleButton") -> "toggle"
        name.endsWith("CheckBox") || name.endsWith("Switch") -> "toggle"
        // ImageButton ends in Button, so it is here rather than beside it.
        name.endsWith("Button") -> "button"
        name.endsWith("TextView") -> "text"
        name.endsWith("ImageView") -> "image"
        name.endsWith("RecyclerView") || name.endsWith("ListView") -> "list"
        name.endsWith("ScrollView") -> "scroll"
        name.endsWith("WebView") -> "web"
        // Anything ending in Layout, and everything else. prune drops the ones
        // that say nothing, so naming them precisely buys nothing.
        else -> "view"
    }
}

/**
 * A node, holding what was read rather than what can be read.
 *
 * @property source the framework node this came from, when the walk was asked
 *   to keep it. Null otherwise, which is every read: holding one obliges the
 *   caller to release it, and reading has nowhere sensible to do that.
 */
internal data class Copied(
    val source: AccessibilityNodeInfo?,
    override val viewId: String?,
    override val role: String,
    override val text: String?,
    override val description: String?,
    override val isClickable: Boolean,
    override val isEditable: Boolean,
    override val isScrollable: Boolean,
    override val isPassword: Boolean,
    override val isVisible: Boolean,
    override val children: List<Node>,
) : Node

/**
 * One screen, copied out of the framework.
 *
 * @param info the root. It stays the caller's — what this releases is only what
 *   it obtained itself.
 * @param retain whether to keep each framework node beside its copy. Off for
 *   every read; on only to act, which is one call long. The caller then owes
 *   the tree a [release]. A second Node implementation reading lazily would
 *   avoid the flag and reintroduce what this file exists to prevent: prune,
 *   resolve and shapeOf each seeing a different screen.
 * @return the tree, or null when there was nothing to read, which is what the
 *   framework answers while no window is focused.
 */
internal fun snapshot(info: AccessibilityNodeInfo?, retain: Boolean = false): Node? =
    copy(info, depth = 0, retain = retain)

/** Give back every framework node a retaining [snapshot] kept. */
internal fun release(node: Node?) {
    val pending = ArrayDeque(listOfNotNull(node))
    while (pending.isNotEmpty()) {
        val here = pending.removeFirst()
        pending.addAll(here.children)
        @Suppress("DEPRECATION")
        (here as? Copied)?.source?.recycle()
    }
}

private fun copy(info: AccessibilityNodeInfo?, depth: Int, retain: Boolean): Node? {
    if (info == null || depth > DEEPEST) return null

    val children = mutableListOf<Node>()
    for (at in 0 until info.childCount) {
        val child = info.getChild(at) ?: continue
        copy(child, depth + 1, retain)?.let { children += it }
        // Released as the copy is made, unless the caller asked to keep it.
        // recycle() is a no-op from API 33 and is not one on 29 through 32,
        // which this app supports; a walk of a real screen obtains hundreds.
        if (!retain) {
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }

    return Copied(
        source = if (retain) info else null,
        viewId = info.viewIdResourceName,
        role = roleOf(info.className),
        text = info.text?.toString(),
        description = info.contentDescription?.toString(),
        isClickable = info.isClickable,
        isEditable = info.isEditable,
        isScrollable = info.isScrollable,
        isPassword = info.isPassword,
        isVisible = info.isVisibleToUser,
        children = children,
    )
}

/**
 * How deep a screen is allowed to be.
 *
 * This walk is recursive where `prune` and `resolve` are not, and for a reason
 * they do not have: children must be built before a parent can hold them, and
 * the alternative is a two-pass build for a hierarchy that is never legitimately
 * this deep. Past it there is a loop or a fault rather than a page, and stopping
 * beats a StackOverflowError inside a service the system restarts silently.
 */
private const val DEEPEST = 100
