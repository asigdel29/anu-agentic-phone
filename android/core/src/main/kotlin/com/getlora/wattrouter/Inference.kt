// Inference.kt: asking a model, and reading its answer as it is produced.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   InferenceError     Why an attempt failed, and whether another model helps.
//   StreamEvent        What a model produces, in the order it produces it.
//   Inference          Ask one model; receive the answer in pieces.
//   ScriptedInference  A fixed answer in pieces. For tests and previews.
//
// Streaming rather than a single return, and that decides the shape of the
// file. A turn that shows nothing for twenty seconds is a turn people kill, and
// a `suspend fun ask(): String` would force every implementation to buffer.
// Retrofitting it means changing every caller, so it goes in before there is
// one.
//
// The other half is the error type. A chain exists so a failed attempt can be
// retried against the next model, and the walk doing that sits above this seam
// rather than inside it, so the distinction upstream.rs makes in a match arm
// has to be a value here. A server error is worth another model; a client error
// is not, because the next model would reject the same body identically.
//
// A Flow rather than a channel or a callback. It is cold, so nothing is
// requested until something collects; it cancels with its collector, which is
// what makes interrupting a turn a matter of cancelling a coroutine; and a
// failure arrives as an exception at the collection point rather than as a
// second parameter every caller has to remember to read.

package com.getlora.wattrouter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Why an attempt failed.
 *
 * The cases exist to be told apart by a caller walking a chain, which is why
 * this is not one class with a string in it.
 */
sealed class InferenceError(message: String) : Exception(message) {

    /**
     * The model could not be reached, or failed on its own account: a dropped
     * connection, a timeout, a 5xx. Another model may answer the same request.
     */
    class Unavailable(val model: String, val detail: String) :
        InferenceError("$model is unavailable: $detail")

    /**
     * The request was rejected on its merits, a 4xx. Every model behind the
     * same API would reject it identically, so a chain stops here rather than
     * spending its remaining attempts proving that.
     */
    class Rejected(val model: String, val status: Int, val detail: String) :
        InferenceError("$model rejected the request ($status): $detail")

    /**
     * Every model in the chain failed.
     *
     * Produced by the walk over a chain, never by a single attempt; an
     * [Inference] must not throw it.
     */
    class Exhausted(val tried: Int, val last: String) :
        InferenceError("all $tried models failed; the last said: $last")

    /**
     * Whether the next model in a chain is worth trying.
     *
     * The walk reads this rather than matching, so the rule is written once and
     * a case added later cannot silently default to retrying.
     */
    val isWorthAnotherModel: Boolean
        get() = this is Unavailable
}

/**
 * What a model produces, in the order it produces it.
 *
 * Two cases, and it will grow. There is deliberately no `Finished(reason)`: a
 * finish reason describes the stream that just ended, and every caller here
 * either has the pieces already or is about to be told by the stream closing.
 */
sealed interface StreamEvent {
    /** A fragment of the answer. Not a token, not a line: whatever arrived. */
    data class Text(val text: String) : StreamEvent

    /** A tool the model wants run, assembled from however many fragments. */
    data class Call(val call: ToolCall) : StreamEvent
}

/**
 * One model, asked one question.
 *
 * Not a `fun interface`: `maxTokens` has a default, and a functional interface
 * may not give its single method one. The default is worth more than the SAM
 * conversion, because every call site but one leaves it out.
 */
interface Inference {
    /**
     * Ask [model] to continue [conversation].
     *
     * The flow is cold: nothing is sent until it is collected, and cancelling
     * the collector cancels the request. Failure arrives as an [InferenceError]
     * thrown at the collection point.
     *
     * # Rely
     * The caller decides retries. This asks one model once, and must not throw
     * [InferenceError.Exhausted]; that belongs to whatever walks a chain.
     *
     * Nothing may be emitted before the response status is known. A caller that
     * has seen one event treats the attempt as delivered and will not retry it
     * against another model, so an event emitted early forecloses the retry
     * that would have worked.
     *
     * @param tools the provider's `tools` array, already JSON, or null for
     *   none. A parameter rather than something the conversation carries: #319
     *   records what happens when sending it is left to a later change.
     */
    fun complete(
        conversation: Conversation,
        model: String,
        tools: String? = null,
        maxTokens: Int? = null,
    ): Flow<StreamEvent>
}

/**
 * A fixed answer, delivered in pieces.
 *
 * In the library rather than the test source set so that a Compose preview and
 * a test share one, and so a screen can be looked at without a network.
 */
class ScriptedInference(
    private val events: List<StreamEvent>,
    private val failWith: InferenceError? = null,
) : Inference {

    /** Every model this was asked for, in order. What a chain walk is judged by. */
    val asked: List<String> get() = _asked
    private val _asked = mutableListOf<String>()

    constructor(says: String) : this(listOf(StreamEvent.Text(says)))

    override fun complete(
        conversation: Conversation,
        model: String,
        tools: String?,
        maxTokens: Int?,
    ): Flow<StreamEvent> = flow {
        // Recorded inside the flow, not outside it, because the flow is cold:
        // recording at construction would count a model nobody collected.
        _asked += model
        failWith?.let { throw it }
        events.forEach { emit(it) }
    }
}
