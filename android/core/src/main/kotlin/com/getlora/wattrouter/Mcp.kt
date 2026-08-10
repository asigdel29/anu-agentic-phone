// Mcp.kt — tools this build does not contain.
//
// History
//   2026-08-09  A. Sigdel  Created with #529.
//
// Contents
//   Rpc        One request to a server, and what it answered.
//   McpFault   Why a server could not be used.
//   McpTool    A remote tool, as this stack's Tool.
//   McpServer  A connection, and the tools it offers.
//   prefixed   The name a remote tool is offered under.
//
// A function of a request and a reply, with the transport behind a seam.
// ServerSentEvent.kt took that split first and gives the reason: a wire format
// and a transport fail in different ways. Here it also puts the whole protocol
// in the JVM suite, leaving one class that only posts.
//
// HTTP, because the reference transport speaks over pipes to a subprocess and
// an Android application cannot run arbitrary binaries. Not a limitation to
// work around; the reason stdio does not appear here.
//
// The names are the security decision. A server that could register `tap` would
// shadow the compiled tool, and the model would call it believing it had
// touched the phone. ToolBox keeps the first of a duplicate, so the built-ins
// win today by being assembled first — an ordering accident rather than a rule.
// `prefixed` makes it structural, and McpTest asserts it.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One request to a server, and what it answered.
 *
 * A seam so the protocol above it is a JVM test rather than a network. The
 * implementation that speaks HTTP lives in the app module beside the other
 * things that need a platform.
 *
 * # Errors
 * Throws whatever the transport throws. Callers here turn that into [McpFault]
 * rather than letting it reach a turn: a server being unreachable is an
 * ordinary outcome and a model can be told about it.
 */
fun interface Rpc {
    /**
     * # Rely
     * Called from the turn loop. Blocks for as long as the server takes, and
     * is cancelled by cancelling the coroutine.
     */
    suspend fun ask(body: String): String
}

/** Why a server could not be used, in words a model can act on. */
class McpFault(val why: String) : Exception(why)

/**
 * The name a remote tool is offered to the model under.
 *
 * Prefixed rather than namespaced with a separator alone, and the prefix is not
 * the server's to choose. `mcp_` collides with nothing compiled into this
 * build, so no server can name a tool such that it shadows `tap`, `read_screen`
 * or anything else the phone actually does.
 *
 * Everything outside the character set a provider accepts becomes an
 * underscore. A name the provider rejects is a turn that fails before the model
 * sees any tools at all, which reads as the model being broken.
 */
internal fun prefixed(label: String, tool: String): String =
    ("mcp_${label}_$tool").map { if (it.isLetterOrDigit() || it == '_') it else '_' }
        .joinToString("")
        .take(NAME_LIMIT)

/** Longest a provider will accept. Truncation beats a rejected request. */
private const val NAME_LIMIT = 64

/**
 * A tool on a server somewhere, offered as though it were compiled in.
 *
 * Nothing downstream can tell one of these from a real tool, which is the
 * point: the moment something can, there are two code paths where one would do.
 */
class McpTool(
    override val name: String,
    override val purpose: String,
    override val schema: String,
    private val remote: String,
    private val rpc: Rpc,
) : Tool {

    /** # Rely
     *  As [Tool.run]. Costs a network round trip, and answers rather than
     *  throws when the server does not. */
    override suspend fun run(arguments: String): String =
        runCatching {
            val body = request("tools/call") {
                put("name", remote)
                put("arguments", Json.parseToJsonElement(arguments.ifBlank { "{}" }))
            }
            answered(rpc.ask(body))
        }.getOrElse { why ->
            // A tool result rather than an exception, for ToolBox's reason: a
            // dead turn is worse than a sentence, and an unreachable server is
            // something a model can plan around.
            "$name could not be reached: ${why.message ?: why::class.java.simpleName}"
        }

    private companion object {
        /** What a server said, flattened to what a model reads. */
        fun answered(reply: String): String {
            val result = decoded(reply)
            val content = result["content"]?.jsonArray.orEmpty()
            val text = content.mapNotNull {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }
            // An error the server reports is still an answer. Saying so keeps
            // the distinction the rest of this layer keeps: a refusal a model
            // can act on, rather than a failure it should retry.
            val trouble = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
            val said = text.joinToString("\n").ifBlank { "the server answered nothing" }
            return if (trouble) "the server refused: $said" else said
        }
    }
}

/**
 * A connection to one server, and the tools it offers.
 *
 * @param label what the person called it. It becomes part of every tool name,
 *   so it is theirs rather than the server's — a server that named itself
 *   would be choosing how it appears in a list somebody uses to decide whether
 *   to trust it.
 */
class McpServer(private val label: String, private val rpc: Rpc) {

    /**
     * Say hello, then ask what it has.
     *
     * Both in one call because neither is useful alone: a handshake with no
     * tools tells nobody anything, and a list without a handshake is a protocol
     * violation some servers refuse and others quietly allow.
     *
     * # Rely
     * Called when a connection is made or refreshed, not per turn. Two network
     * round trips.
     *
     * # Errors
     * [McpFault] when the server is unreachable, answers something that is not
     * JSON-RPC, or reports an error to either call.
     */
    suspend fun tools(): List<Tool> {
        greet()
        val listed = runCatching { rpc.ask(request("tools/list") {}) }
            .getOrElse { throw McpFault("$label could not be reached: ${it.message}") }

        return decoded(listed)["tools"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val tool = entry.jsonObject
            val remote = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpTool(
                name = prefixed(label, remote),
                // A description is model input written by somebody else, which
                // is why SECURITY.md gains a section with this. Passed through
                // rather than sanitised: editing it would be pretending the
                // risk is textual when it is that the server is trusted at all.
                purpose = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                schema = tool["inputSchema"]?.jsonObject?.toString() ?: EMPTY_SCHEMA,
                remote = remote,
                rpc = rpc,
            )
        }
    }

    private suspend fun greet() {
        val hello = request("initialize") {
            put("protocolVersion", VERSION)
            put("capabilities", buildJsonObject { })
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", "wattrouter")
                    put("version", "0.1")
                },
            )
        }
        runCatching { decoded(rpc.ask(hello)) }
            .getOrElse { throw McpFault("$label refused the handshake: ${it.message}") }
    }

    private companion object {
        /** What this client speaks. Sent, and not negotiated down. */
        const val VERSION = "2024-11-05"

        /** For a tool that declares none. An absent schema is not no arguments. */
        const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
    }
}

/** One JSON-RPC request, with the id every reply is matched by. */
private fun request(method: String, params: JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        // Fixed, because this client makes one request per connection and waits
        // for it. A counter would imply a pipeline there is no code for, and a
        // reader would look for the matching that does not happen.
        put("id", 1)
        put("method", method)
        put("params", buildJsonObject(params))
    }.toString()

/**
 * The result of a reply, or the reason there is none.
 *
 * A JSON-RPC error is raised rather than returned: it means the call did not
 * happen, which is different from a call that happened and went badly. The
 * second is `isError` inside a result and is a tool answer.
 */
private fun decoded(reply: String): JsonObject {
    val body = runCatching { Json.parseToJsonElement(reply).jsonObject }
        .getOrElse { throw McpFault("the server did not answer JSON-RPC") }

    body["error"]?.jsonObject?.let {
        val said = it["message"]?.jsonPrimitive?.contentOrNull ?: it.toString()
        throw McpFault(said)
    }
    return body["result"]?.jsonObject ?: throw McpFault("the server answered no result")
}

private fun buildJsonObject(build: JsonObjectBuilder.() -> Unit): JsonObject =
    kotlinx.serialization.json.buildJsonObject(build)
