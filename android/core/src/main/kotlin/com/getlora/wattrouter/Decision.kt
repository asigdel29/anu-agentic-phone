// Decision.kt: what the core answered, as values rather than a string.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Backend   Where a model runs.
//   Step      One model, and where it runs.
//   Decision  A tier, why it was chosen, the score behind it, and the chain.
//
// Core.decide hands back an envelope and CoreTest checks it by string matching,
// which is enough to say the ABI works and not enough for anything to use it.
// Every caller after this wants values: a chain walk wants the chain in order,
// a routing panel wants the tier and the reason, the turn loop re-decides each
// round and shows what changed.
//
// Decoded by hand for the reason Conversation.kt encodes by hand: there is no
// serialization compiler plugin here, and the two decisions below are ones a
// generated decoder would get wrong quietly.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where a model runs. */
enum class Backend {
    /** In this process. Nothing is, yet: #188 is the checklist for when one is. */
    LOCAL,

    /** Over the network, at the provider. */
    REMOTE,

    /** A backend this build has not been taught. Skipped rather than fatal. */
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): Backend = when (wire) {
            "local" -> LOCAL
            "remote" -> REMOTE
            // An unknown backend is a newer core than this Kotlin, which is a
            // step to skip rather than a decision to throw away.
            else -> UNKNOWN
        }
    }
}

/** One model, and where it runs. */
data class Step(val model: String, val backend: Backend)

/**
 * What the core decided, and what stands behind it.
 *
 * @property score absent rather than zero when there was none. #314 made the
 *   core omit the key for the same reason: a number meaning "no number" is a
 *   number somebody compares against a threshold.
 */
data class Decision(
    val tier: String,
    val reason: String,
    val score: Float?,
    val chain: List<Step>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read the envelope `Core.decide` answers with.
         *
         * @return the decision, or null for `{"error": …}`, for a null answer,
         *   or for anything that does not parse. A caller cannot act on the
         *   difference, since there is no decision either way, and the core already
         *   distinguishes them for anybody debugging it.
         */
        fun from(envelope: String?): Decision? {
            val root = envelope?.let {
                runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
            } ?: return null

            val ok = root["ok"]?.jsonObject ?: return null
            val tier = ok["tier"]?.jsonPrimitive?.contentOrNull ?: return null
            val reason = ok["reason"]?.jsonPrimitive?.contentOrNull ?: return null

            return Decision(
                tier = tier,
                reason = reason,
                score = ok["score"]?.jsonPrimitive?.floatOrNull,
                chain = ok["chain"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val step = element.jsonObject
                    val model = step["model"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Step(
                        model = model,
                        backend = Backend.of(
                            step["backend"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ),
                    )
                },
            )
        }
    }
}
