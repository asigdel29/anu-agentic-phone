// Recollection.kt: what the store answered, as values.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Remembered    One turn the store thought relevant.
//   Recollection  A route, and the evidence behind it.
//
// The role is closed and the route is not, and the asymmetry is the point. A
// turn that matched and a turn standing next to one differ as evidence differs
// from context, so a sixth role read as one of these is the wrong one of two.
// A new route only changes how an answer was found.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One turn the store thought relevant. */
data class Remembered(
    /** What was said. The answer is here; the rest is about trusting it. */
    val text: String,
    val speaker: String,
    /** When, as seconds since the epoch. */
    val ts: Long,
    /** How well it matched, on the store's own scale: comparable within one
     *  recollection and meaningless across two. */
    val score: Float,
    val role: Role,
) {
    /** Why this turn is here. */
    enum class Role(val wire: String) {
        /** It matched the question. */
        MAIN("Main"),

        /** Reached across the entity graph from something that matched. */
        GRAPH_BRIDGE("GraphBridge"),

        /** It sits beside a match in the same conversation. */
        LOCAL_NEIGHBOR("LocalNeighbor"),
        ;

        companion object {
            /**
             * A role this build has not been taught is [GRAPH_BRIDGE], not
             * [MAIN]. Guessing towards evidence makes the model state something
             * nobody said; towards context, it hedges about something true.
             * Only one of those is a lie.
             */
            fun of(wire: String?): Role =
                entries.firstOrNull { it.wire == wire } ?: GRAPH_BRIDGE
        }
    }
}

/** A route, and the evidence behind it. */
data class Recollection(val route: String, val evidence: List<Remembered>) {
    val isEmpty: Boolean get() = evidence.isEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read the envelope `Memory.recall` answers with.
         *
         * @return what was found, or null for an error, a null answer, or
         *   anything unparseable. An empty recollection is a different thing
         *   from a failed one, so this does not flatten one into the other.
         */
        fun from(envelope: String?): Recollection? {
            val ok = envelope
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?.get("ok")?.jsonObject
                ?: return null

            return Recollection(
                route = ok["route"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                evidence = ok["evidence"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val piece = element.jsonObject
                    val text = piece["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Remembered(
                        text = text,
                        speaker = piece["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ts = piece["ts"]?.jsonPrimitive?.longOrNull ?: 0,
                        score = piece["score"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        role = Remembered.Role.of(
                            piece["role"]?.jsonPrimitive?.contentOrNull,
                        ),
                    )
                },
            )
        }
    }
}
