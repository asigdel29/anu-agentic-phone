// ToolBox.kt: the tools a turn has, and dispatching to one.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Every failure becomes something the model can read. An unknown name lists
// the alternatives; anything thrown becomes a sentence naming the tool. A model
// told only "error" makes the same call again.
//
// definitions() is what the model is shown, and #319 is the standing warning:
// on iOS the equivalent is reached only from tests, so every registered tool is
// invisible. #341 made `tools` a parameter of Inference.complete so this side
// cannot repeat that; this is the other end of the wire.

package com.getlora.wattrouter

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The tools a turn has. */
class ToolBox(tools: List<Tool>) {
    /**
     * In the order given, which is the order the model is shown. A duplicate
     * name keeps the first: two tools answering to one name is a wiring
     * mistake, and picking the later one would depend on assembly order.
     */
    val tools: List<Tool> = tools.distinctBy { it.name }

    private val byName = this.tools.associateBy { it.name }

    operator fun get(name: String): Tool? = byName[name]

    /**
     * Run what the model asked for.
     *
     * # Rely
     * Called one at a time from the turn loop, in the order the model asked.
     *
     * Never throws except [CancellationException]: every other failure is a
     * result the model can read. A dead turn is worse than a sentence.
     */
    suspend fun run(call: ToolCall): ToolResult {
        val tool = byName[call.name] ?: return ToolResult(
            id = call.id,
            // The alternatives, not just the mistake. A model that misremembers
            // a name usually recognises the right one when it sees it.
            content = "there is no tool called ${call.name}. " +
                "Available: ${byName.keys.sorted().joinToString(", ")}",
            isError = true,
        )

        return try {
            // answer rather than run, which is where a tool that captures
            // something puts it. Every tool but that one inherits the default,
            // so this line is the whole of what the change costs here.
            val answered = tool.answer(call.arguments)
            ToolResult(call.id, answered.text, answered.images)
        } catch (e: CancellationException) {
            // The one thing that propagates; see Tool.kt.
            throw e
        } catch (e: Exception) {
            ToolResult(
                id = call.id,
                content = "${call.name} failed: ${e.message ?: e::class.java.simpleName}. " +
                    "Its arguments must match this schema: ${tool.schema}",
                isError = true,
            )
        }
    }

    /**
     * What the model is shown, as the provider's `tools` array.
     *
     * @throws IllegalArgumentException if a tool's schema is not a JSON
     *   object. Loud, rather than dropping it: a tool quietly missing from the
     *   list is one the model never calls and nobody notices.
     */
    fun definitions(): String = buildJsonArray {
        tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.purpose)
                            put("parameters", tool.schema.asSchema(tool.name))
                        },
                    )
                },
            )
        }
    }.toString()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun String.asSchema(tool: String): JsonObject =
            runCatching { json.parseToJsonElement(this) as JsonObject }.getOrElse {
                throw IllegalArgumentException("$tool's schema is not a JSON object: $this")
            }
    }
}
