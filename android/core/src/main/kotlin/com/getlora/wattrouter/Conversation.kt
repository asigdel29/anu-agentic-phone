// Conversation.kt: the state a turn accumulates, and the request it becomes.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The same ideas Conversation.swift arrived at, which #233 asks for: the Android
// side should reach the same ones rather than parallel ones that agree until
// they do not.
//
// The core takes an OpenAI-shaped body as a string. Written by hand at each call
// site that is a second copy of a format, free to drift, with escaping at every
// site, and a message containing a quote stops being text and becomes a
// malformed request. Built here, once, from state already kept.
//
// Hand-written encoding rather than @Serializable, and not only to avoid a
// compiler plugin. What a message leaves out is a decision: "tool_calls": [] on
// a user message and "tool_call_id": null on an assistant one are both things a
// provider may refuse. An annotation makes that a default somebody changes by
// accident.

package com.getlora.wattrouter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Who produced a message. */
enum class Role(val wire: String) {
    /** Standing instructions, not part of the exchange. */
    SYSTEM("system"),

    /** The person. */
    USER("user"),

    /** The model. */
    ASSISTANT("assistant"),

    /** The result of a tool the model asked for. */
    TOOL("tool"),
}

/** Something the model asked to run. */
data class ToolCall(
    /** The provider's identifier, carried untouched: a reply has to name the call
     *  it answers, and a turn may have several in flight. */
    val id: String,
    val name: String,
    /** The arguments as the model wrote them: JSON, as text, and not necessarily
     *  valid. A tool decodes its own, and failing to is an ordinary outcome. */
    val arguments: String,
)

/**
 * One message: a role, its text, and the two shapes a tool exchange needs.
 */
data class Message(
    val role: Role,
    /** What it says. Empty on an assistant turn that only asked for tools, which
     *  is ordinary rather than a fault. */
    val content: String,
    /** What the model asked to run. Only on an assistant message. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Which call this answers. Only on a tool message. */
    val toolCallId: String? = null,
) {
    companion object {
        fun system(content: String) = Message(Role.SYSTEM, content)

        fun user(content: String) = Message(Role.USER, content)

        /**
         * Something the model said, and anything it asked to have run.
         *
         * A turn that asked for tools must be appended before any result is, and
         * with its calls intact: a tool message names a call id, and a provider
         * that was never sent the message announcing that id rejects the whole
         * request, naming, in the way of these things, nothing in particular.
         */
        fun assistant(content: String, toolCalls: List<ToolCall> = emptyList()) =
            Message(Role.ASSISTANT, content, toolCalls)

        /** What a tool produced, answering the call that asked for it. */
        fun tool(content: String, answering: String) =
            Message(Role.TOOL, content, toolCallId = answering)
    }

    /** As the provider expects it. Absent keys rather than empty ones. */
    fun asJson(): JsonObject = buildJsonObject {
        put("role", role.wire)
        put("content", content)
        if (toolCalls.isNotEmpty()) put("tool_calls", callsAsJson())
        toolCallId?.let { put("tool_call_id", it) }
    }

    private fun callsAsJson(): JsonArray = buildJsonArray {
        toolCalls.forEach { call ->
            add(
                buildJsonObject {
                    put("id", call.id)
                    // The only value the field takes, and required: a provider
                    // reading a call without it treats the message as malformed
                    // rather than guessing.
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        },
                    )
                }
            )
        }
    }
}

/**
 * The messages so far, and the request body the routing core reads off them.
 *
 * Not thread-safe and not meant to be: a turn is one sequence of appends, and a
 * conversation two turns write to at once is a transcript that never happened.
 */
class Conversation(system: String? = null) {
    private val backing = mutableListOf<Message>()

    init {
        system?.let { backing.add(Message.system(it)) }
    }

    /** Every message, oldest first. */
    val messages: List<Message> get() = backing.toList()

    fun append(message: Message) {
        backing.add(message)
    }

    /**
     * The body the core classifies, and the provider answers.
     *
     * @param model which model to ask, or null when the caller is only asking the
     *   core to route: the core reads the messages and ignores the rest, and
     *   sending a model name it will not use invites somebody to trust it.
     */
    fun body(model: String? = null): String = buildJsonObject {
        model?.let { put("model", it) }
        put("messages", buildJsonArray { backing.forEach { add(it.asJson()) } })
    }.toString()
}
