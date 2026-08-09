// ServerSentEvent.kt — one line of a streamed completion.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   ServerSentEvent  What a line of the provider's streamed body means.
//   FinishReason     Why the model stopped, as an open set.
//   ToolCallFragment Part of one call, keyed by the index it continues.
//
// The provider answers a streaming request in text/event-stream: a line-framed
// format carrying JSON, most of whose lines carry nothing. Reading it is a pure
// function of a line, so it is written and tested as one, apart from the client
// that will do the reading — a wire format and a transport fail in different
// ways and are worth debugging separately.
//
// One line is not one event. A delta can carry text *and* several tool call
// fragments, and the choice around it can carry a finish reason at the same
// time; parallel calls are an array. So reading a line yields a list, and there
// is no Ignored case — an ignored line yields an empty list, which says the
// same thing. Returning one event and picking whichever looked most important
// is how a tool call goes missing behind a stray space of content.
//
// The judgement here is what to do with a `data:` line that will not parse. It
// is an error rather than a skip, because the alternative is dropping the
// model's text and reporting success: an answer that silently loses a sentence
// looks like a short answer, and nothing in the stack would say otherwise.
// Lines that are not data — comments, blanks, fields this client does not read
// — yield nothing, because that is the format working as intended.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Why a model stopped producing.
 *
 * A value class over a string rather than an enum, because this is an open set.
 * An enum fails on a value it has not been taught, which would abandon an
 * otherwise good stream over a word almost nothing reads. An unknown reason
 * arrives intact and can be logged.
 */
@JvmInline
value class FinishReason(val wire: String) {
    companion object {
        /** The model finished its answer. */
        val Stop = FinishReason("stop")

        /** The model wants tools run, and is waiting for the results. */
        val ToolCalls = FinishReason("tool_calls")

        /**
         * The answer was cut off at the cap. Not a failure and not a complete
         * answer either, which is the distinction a caller needs and the reason
         * this is read at all.
         */
        val Length = FinishReason("length")
    }
}

/**
 * Part of one tool call.
 *
 * Nothing here is a usable call on its own, and saying so in the type keeps a
 * caller from treating the first fragment as the whole thing.
 */
data class ToolCallFragment(
    /** Which call this belongs to; the only thing tying a fragment to its call. */
    val index: Int,
    /** Present on the first fragment for an index, absent afterwards. */
    val id: String?,
    /** Likewise. */
    val name: String?,
    /** A piece of the JSON arguments. Often empty on the fragment carrying the id. */
    val arguments: String,
)

/** What one line of a streamed completion means. */
sealed interface ServerSentEvent {
    /** Text the model produced, to be handed on as it stands. */
    data class Text(val text: String) : ServerSentEvent

    /** Part of a tool call. Assembling these is [ToolCallAssembly]'s job. */
    data class Call(val fragment: ToolCallFragment) : ServerSentEvent

    /** Why the model stopped. Arrives on its own chunk, before [Done]. */
    data class Finished(val reason: FinishReason) : ServerSentEvent

    /** The provider says the answer is complete. Nothing follows it. */
    data object Done : ServerSentEvent

    companion object {
        /** The field prefix carrying the payload. The space after it is optional. */
        private const val FIELD = "data:"

        /** What the provider sends instead of a final chunk. */
        private const val TERMINATOR = "[DONE]"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read one line, without its newline.
         *
         * @return what it means, in the order it should be handled. Empty for
         *   anything that is not a payload.
         * @throws IllegalArgumentException if a `data:` line is not a chunk this
         *   understands. Skipping it would lose text; see the note above.
         */
        fun decoding(line: String): List<ServerSentEvent> {
            if (!line.startsWith(FIELD)) return emptyList()
            val payload = line.removePrefix(FIELD).trim()
            if (payload == TERMINATOR) return listOf(Done)

            val chunk = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw IllegalArgumentException("not a chunk: $payload", it) }

            val events = mutableListOf<ServerSentEvent>()
            val choices = chunk["choices"]?.jsonArray.orEmpty().map { it.jsonObject }

            // Concatenated rather than taking the first: the field is an array,
            // and a provider that ever returns two would otherwise lose one.
            //
            // An empty delta is not a chunk. The first event of a completion
            // carries the role and no text and the last carries a finish reason
            // and no text; emitting "" for those would commit a chain walk to a
            // model that has not said anything, which is the decision it exists
            // to make.
            val text = choices.joinToString("") { it.delta()?.string("content").orEmpty() }
            if (text.isNotEmpty()) events += Text(text)

            choices.flatMap { it.delta()?.get("tool_calls")?.jsonArray.orEmpty() }
                .forEach { events += Call(it.jsonObject.fragment()) }

            choices.mapNotNull { it.string("finish_reason") }
                .forEach { events += Finished(FinishReason(it)) }

            return events
        }

        private fun JsonObject.delta(): JsonObject? = this["delta"]?.jsonObject

        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull

        private fun JsonObject.fragment(): ToolCallFragment {
            val function = this["function"]?.jsonObject
            return ToolCallFragment(
                index = this["index"]?.jsonPrimitive?.intOrNull ?: 0,
                id = string("id"),
                name = function?.string("name"),
                arguments = function?.string("arguments").orEmpty(),
            )
        }
    }
}
