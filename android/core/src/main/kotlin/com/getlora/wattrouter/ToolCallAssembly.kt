// ToolCallAssembly.kt — putting a tool call back together.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The reader is a pure function of one line, which is what makes it testable
// without a network. Assembling a call is not: the id and name arrive on the
// first fragment for an index, and the arguments arrive a few characters at a
// time across many lines. The state lives here, in the one type whose job it is.
//
// Nothing is emitted as it arrives. Inference promises that a yielded event
// commits the chain — a second model cannot un-deliver one — and a half-built
// call commits to something that does not exist yet. Nothing on the wire marks
// the end of an individual call either; only the finish reason marks the end of
// all of them, so the caller decides when to ask.

package com.getlora.wattrouter

/**
 * Fragments in, whole calls out.
 *
 * Internal, like the reader it consumes: this is how the client puts a stream
 * back together, and nothing outside the module has a fragment to give it.
 */
internal class ToolCallAssembly {

    /**
     * Keyed by the index the provider used, which is the only thing tying a
     * fragment to the call it continues. A map rather than a list because
     * indices need not arrive in order and need not start at zero.
     */
    private val byIndex = mutableMapOf<Int, Partial>()

    /** Whether anything has been collected, for a caller flushing on two signals. */
    val isEmpty: Boolean get() = byIndex.isEmpty()

    /**
     * Take in a fragment.
     *
     * The id and name are written once and never overwritten with nothing: a
     * later fragment for the same index carries neither, and letting it blank
     * them is how a call loses the name it was going to be dispatched by.
     */
    fun add(fragment: ToolCallFragment) {
        val partial = byIndex.getOrPut(fragment.index) { Partial() }
        partial.id = fragment.id ?: partial.id
        partial.name = fragment.name ?: partial.name
        partial.arguments.append(fragment.arguments)
    }

    /**
     * Everything collected so far, in index order, and forget it.
     *
     * Index order rather than arrival order: the provider numbers parallel
     * calls, and the numbering is the only statement it makes about sequence.
     * The Agent runs them in the order it is given, so that order has to be the
     * provider's rather than whichever fragment happened to arrive first.
     *
     * A call with no name is dropped. It cannot be dispatched, and passing it on
     * turns a truncated stream into a tool-not-found the model then apologises
     * for.
     */
    fun take(): List<ToolCall> {
        val calls = byIndex.entries
            .sortedBy { it.key }
            .mapNotNull { (_, partial) ->
                partial.name?.let {
                    ToolCall(
                        id = partial.id.orEmpty(),
                        name = it,
                        arguments = partial.arguments.toString(),
                    )
                }
            }
        byIndex.clear()
        return calls
    }

    private class Partial {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
