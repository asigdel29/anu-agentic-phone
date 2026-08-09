// MemoryTools.kt — remembering something, and asking what was remembered.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   RememberTool  Putting something in memory.
//   RecallTool    Asking what is in it.
//
// One file because the pair is the decision: the model decides what is worth
// remembering, rather than the loop ingesting everything. Automatic ingest is
// worse for a reason that is not about cost — a transcript is mostly the shape
// of a conversation, "yes", "do that", "thanks", and a store full of that
// recalls the shape rather than the facts.
//
// The rendering keeps the role. #296 kept main apart from graphBridge and
// localNeighbor so a tool could tell evidence from context, and this is that
// tool: a turn dragged in across the graph, rendered like one that matched, is
// a fact the model states as though somebody said it.

package com.getlora.wattrouter

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Put something in memory. */
class RememberTool(
    private val memory: Memory,
    private val session: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : Tool {
    override val name = "remember"

    override val purpose =
        "Remember something for later turns and later conversations. Use it for " +
            "facts about the person and their world that would be tedious to be told " +
            "again — where things are, who people are, how they like things done. Not " +
            "for the conversation itself, which is already in front of you."

    override val schema = """
        {"type":"object","properties":{"text":{"type":"string",
        "description":"The fact, written as a sentence that will still make sense alone."}},
        "required":["text"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Nothing. There is no capability to obtain: this is the app's own store. */
    override suspend fun run(arguments: String): String {
        val text = Tools.field(arguments, "text").trim()
        if (text.isEmpty()) return "there was nothing to remember, so nothing was stored"

        memory.remember(text, speaker = "assistant", session = session, at = now())
        // Said back rather than "done". A model that cannot see what landed
        // writes it again next turn.
        return "remembered: $text"
    }
}

/** Ask what is in memory. */
class RecallTool(private val memory: Memory) : Tool {
    override val name = "recall"

    override val purpose =
        "Search everything remembered from earlier conversations. Ask it whenever " +
            "something depends on what the person told you before — it is the only " +
            "way to reach anything outside this conversation. Some results are marked " +
            "context: those are turns that sit near a match rather than answering it, " +
            "so do not state them as fact."

    override val schema = """
        {"type":"object","properties":{"query":{"type":"string",
        "description":"What you are trying to find out."}},"required":["query"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Nothing. */
    override suspend fun run(arguments: String): String {
        val query = Tools.field(arguments, "query").trim()
        if (query.isEmpty()) return "no question was given, so nothing was looked up"

        val found = Recollection.from(memory.recall(query, most = LIMIT))
            ?: return "the store could not be searched"
        return describe(found)
    }

    companion object {
        /**
         * Most pieces of evidence shown. Past a handful the model is reading a
         * transcript rather than an answer.
         */
        const val LIMIT = 8

        private val day: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        /**
         * One recollection, as lines. Separate from [run] so the rendering —
         * which is all of the decisions — is exercised without a store.
         */
        fun describe(found: Recollection): String {
            // Distinguishable from a failure, and from a store never written
            // to, which a model would otherwise keep querying.
            if (found.isEmpty) return "nothing remembered about that"

            return found.evidence.take(LIMIT).joinToString("\n") { piece ->
                // Marked, not omitted: context is worth showing and worth not
                // stating. Omitting it would lose the thread a match hangs on.
                val mark = if (piece.role == Remembered.Role.MAIN) "" else " (context)"
                "${day.format(Instant.ofEpochSecond(piece.ts))}$mark  ${piece.text}"
            }
        }
    }
}

/** Reading one field out of what the model wrote. */
internal object Tools {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return the field, or empty if the arguments were not an object or it was
     *   absent. Empty rather than a throw: a tool answers a malformed call in
     *   words, and both callers already say something about an empty one.
     */
    fun field(arguments: String, name: String): String =
        runCatching {
            json.parseToJsonElement(arguments).jsonObject[name]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
}
