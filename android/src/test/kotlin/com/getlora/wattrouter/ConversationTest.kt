// ConversationTest.kt — what the core and the provider actually receive.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// JVM tests rather than instrumented ones: none of this touches Android, and a
// test needing an emulator to check JSON is a test nobody runs.
//
// The cases are the two failures hand-written JSON produces — a quote that
// escapes into a malformed request, and a key present and empty where the
// provider wanted it absent. Both fail at the provider rather than here, with a
// message about the whole request.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private fun parsed(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `awkward characters stay inside the message`() {
        // The reason the body is built rather than written. Concatenated, the
        // quote ends the string early and the provider refuses the whole request
        // with a message about the request. The newline and backslash are here
        // too, because they fail the same way and separately.
        val awkward = "he said \"no\"\nand\\left"
        val chat = Conversation()
        chat.append(Message.user(awkward))

        val read = parsed(chat.body())
        assertEquals(
            awkward,
            read["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a user message carries no tool_calls key at all`() {
        // Present and empty is a thing a provider may refuse, and refusing a
        // whole request over a key that means nothing is expensive to diagnose.
        val chat = Conversation()
        chat.append(Message.user("hello"))

        val message = parsed(chat.body())["messages"]!!.jsonArray[0].jsonObject
        assertNull(message["tool_calls"])
        assertNull(message["tool_call_id"])
    }

    @Test
    fun `an assistant message that asked for tools carries them`() {
        val chat = Conversation()
        chat.append(
            Message.assistant("", listOf(ToolCall("call-1", "read_file", """{"path":"a.txt"}""")))
        )

        val call =
            parsed(chat.body())["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!
                .jsonArray[0]
                .jsonObject
        assertEquals("call-1", call["id"]!!.jsonPrimitive.content)
        // Required, and the only value it takes: a provider reading a call
        // without it treats the message as malformed rather than guessing.
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        val function = call["function"]!!.jsonObject
        assertEquals("read_file", function["name"]!!.jsonPrimitive.content)
        // As text, not as an object. What arrives from a provider is a string
        // that is usually JSON and sometimes is not, which is why a tool decodes
        // its own rather than being handed a parsed value.
        assertEquals("""{"path":"a.txt"}""", function["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool result names the call it answers`() {
        val chat = Conversation()
        chat.append(Message.tool("four lines", answering = "call-1"))

        val message = parsed(chat.body())["messages"]!!.jsonArray[0].jsonObject
        assertEquals("call-1", message["tool_call_id"]!!.jsonPrimitive.content)
        assertNull(message["tool_calls"])
    }

    @Test
    fun `standing instructions come first and are a message like any other`() {
        val chat = Conversation(system = "be brief")
        chat.append(Message.user("hello"))

        val messages = parsed(chat.body())["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no model is named unless one was asked for`() {
        // The core reads the messages and ignores the rest. A model name it will
        // not use is one somebody downstream trusts.
        val chat = Conversation()
        chat.append(Message.user("hello"))

        assertFalse(parsed(chat.body()).containsKey("model"))
        assertEquals("kimi-k2.7", parsed(chat.body("kimi-k2.7"))["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the message list is a copy rather than the one being appended to`() {
        // A caller holding `messages` while a turn continues would otherwise see
        // it grow underneath, which is the transcript bug in a different shape.
        val chat = Conversation()
        chat.append(Message.user("first"))
        val held = chat.messages
        chat.append(Message.user("second"))

        assertEquals(1, held.size)
        assertEquals(2, chat.messages.size)
        assertTrue(held[0].content == "first")
    }
}

// TEMPORARY — proves the android job can turn `Required` red. Reverted in the
// next commit; see the pull request body for the run this produced.
class DeliberateFailureTest {
    @org.junit.Test
    fun theAggregatorNoticesWhenThisFails() {
        org.junit.Assert.assertEquals("this must fail", 1, 2)
    }
}
