// Tool.kt: something the model can do, and what it answered.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   ToolResult  What a tool answered, and whether it went wrong.
//   Tool        Something the model can ask for.
//
// The contract shaping every tool written after this: a tool throws only for
// what the model cannot act on. A missing file, an argument out of range, a
// permission refused: those are returned strings the model reads and acts on.
// Throwing turns an ordinary outcome into a dead turn.
//
// Cancellation is the exception and the only thing that propagates: reported
// as a result, "cancelled" is something a model answers by trying again.

package com.getlora.wattrouter

/**
 * What a tool answered, before it becomes a message.
 *
 * Separate from [ToolResult] because a tool does not know its own call id and
 * should not have to be handed one to say what it produced. [ToolBox] joins the
 * two.
 *
 * @property text what the model reads. Prose, not a status code.
 * @property images anything to be looked at rather than read. Empty for every
 *   tool but the one that captures a screen, which is why [Tool.answer] has a
 *   default and seventeen tools did not have to be edited to gain a field none
 *   of them fills.
 */
data class Answer(val text: String, val images: List<Image> = emptyList())

/** What a tool answered. */
data class ToolResult(
    /** The call this answers. A turn may have several in flight. */
    val id: String,
    /** What the model reads. Prose, not a status code. */
    val content: String,
    /** Anything to be looked at. Where it lands in a message is #439's third
     *  layer; nothing reads this yet. */
    val images: List<Image> = emptyList(),
    /**
     * Whether it went wrong. Advisory, since the content says what happened either
     * way, and this is for a transcript rendering a failure differently.
     */
    val isError: Boolean = false,
)

/** Something the model can ask for. */
interface Tool {
    /** What the model calls it. Must be unique in a [ToolBox]. */
    val name: String

    /** What it is for, written for the model rather than a reader. */
    val purpose: String

    /**
     * A JSON Schema object, as text: written once, read by a person, and passed
     * through untouched. A builder would invite constructing it per call.
     */
    val schema: String

    /**
     * Do it.
     *
     * # Rely
     * Called from the turn loop, one at a time and in the order the model asked:
     * a write then a read of the same path is a correct sequence and a race
     * if they overlap. May suspend as long as the work takes; the caller
     * cancels by cancelling the coroutine.
     *
     * @param arguments JSON as the model wrote it, and not necessarily valid.
     *   Decoding is the tool's own, and failing to is an ordinary outcome.
     * @return what happened, for the model to read. Throw only what it cannot
     *   act on.
     */
    suspend fun run(arguments: String): String

    /**
     * Do it, and answer with anything to be looked at as well as read.
     *
     * This is what [ToolBox] calls. It defaults to [run], so a tool whose
     * answer is prose implements nothing: seventeen of them do that today and
     * none had to be edited to gain a field none of them fills.
     *
     * A tool that captures something overrides this instead, and narrows [run]
     * to the prose half rather than leaving it unimplemented. Both members stay
     * true of it, which is what keeps a caller that only wants text from having
     * to know which kind of tool it holds.
     *
     * # Rely
     * As [run], and called instead of it rather than beside it: a tool that did
     * its work in both would do it twice.
     */
    suspend fun answer(arguments: String): Answer = Answer(run(arguments))
}
