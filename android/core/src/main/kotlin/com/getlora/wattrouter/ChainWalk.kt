// ChainWalk.kt: trying each model in a tier until one answers.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   TurnEvent  What a turn produces, for a transcript to fold.
//   ChainWalk  One tier's chain, walked until something answers.
//
// A tier names several models in preference order, which is the only reason
// wattrouter_chain_length exists. Without this, a single Unavailable ends the
// turn, and the chain is precisely the thing that says it should not.
//
// The rule is one line and the whole correctness of the file:
//
//     retry only when error.isWorthAnotherModel && !delivered
//
// `delivered` flips on the first event of any kind, and a tool-call fragment
// counts exactly as text does. This is not an optimisation. Nothing can un-emit
// an event, so once anything has reached the transcript, retrying against a
// second model produces a turn whose first half came from one model and second
// half from another, spliced wherever the first failed. Failing a delivered
// turn is better than answering with a chimera.

package com.getlora.wattrouter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What a turn produces.
 *
 * Wider than [StreamEvent] because a transcript needs what a model does not
 * say: which model is answering, what a tool returned, and what was decided.
 */
sealed interface TurnEvent {
    /** A model has been reached and is about to speak. */
    data class Answering(val model: String, val backend: Backend) : TurnEvent

    /** A fragment of the answer. */
    data class Text(val text: String) : TurnEvent

    /** A tool the model wants run. */
    data class Call(val call: ToolCall) : TurnEvent

    /** What a tool answered. Never from a walk; the Agent yields it. */
    data class Result(val result: ToolResult) : TurnEvent

    /**
     * The tier, and the chain behind it. Never from a walk: the Agent yields
     * it before asking, because the panel should show where a turn is going
     * rather than where it went.
     */
    data class Decided(val decision: Decision) : TurnEvent
}

/** One tier's chain, walked until something answers. */
class ChainWalk(private val asking: Inference) {

    /**
     * Try each of [steps] in turn.
     *
     * # Rely
     * Collected once. The flow is cold and each collection walks the chain
     * again, which is a second set of requests rather than a replay.
     *
     * @throws InferenceError.Exhausted if every step failed or there were none.
     *   A single attempt must never throw this; producing it is what a walk is.
     */
    fun complete(
        conversation: Conversation,
        steps: List<Step>,
        tools: String? = null,
        maxTokens: Int? = null,
    ): Flow<TurnEvent> = flow {
        var delivered = false
        var last: InferenceError? = null

        for (step in steps) {
            // Counted as an attempt and skipped. Nothing runs a model in this
            // process yet (#188 is the checklist for when something does) and
            // counting rather than ignoring keeps the exhausted message honest
            // about how many models were considered.
            if (step.backend != Backend.REMOTE) {
                last = InferenceError.Unavailable(step.model, "${step.backend} runs nothing here")
                continue
            }

            try {
                asking.complete(conversation, step.model, tools, maxTokens).collect { event ->
                    if (!delivered) {
                        delivered = true
                        emit(TurnEvent.Answering(step.model, step.backend))
                    }
                    emit(
                        when (event) {
                            is StreamEvent.Text -> TurnEvent.Text(event.text)
                            is StreamEvent.Call -> TurnEvent.Call(event.call)
                        },
                    )
                }
                return@flow
            } catch (e: InferenceError) {
                last = e
                // Both halves matter. A rejection would be rejected identically
                // by every model behind the same API; and a delivered turn
                // cannot be restarted, only finished wrongly.
                if (!e.isWorthAnotherModel || delivered) throw e
            }
        }

        throw InferenceError.Exhausted(steps.size, last?.message ?: "the tier named no models")
    }
}
