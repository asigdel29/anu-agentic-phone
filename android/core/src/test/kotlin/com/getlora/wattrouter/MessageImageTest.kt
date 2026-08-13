// MessageImageTest.kt: what a message looks like once it can carry a picture.
//
// History
//   2026-08-11  A. Sigdel  Created with #439.
//
// On the JVM against the encoder, which is where the whole of this change is.
// #439 says the risk plainly: doing it badly means a fourth shape for a message
// that only one tool uses.
//
// So the first case is the one that matters most. A message with no images
// encodes exactly as it always did, byte for byte, because a provider that
// dislikes an array where it expected a string should never see one on a turn
// that has no picture in it.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageImageTest {
    private val shot = Image("data:image/png;base64,aGVsbG8=")

    private fun contentOf(message: Message) = message.asJson()["content"]!!

    @Test
    fun aMessageWithNoImageEncodesAsItAlwaysDid() {
        // The case the whole design is for. A string, not an array of one part.
        val json = Message.user("what is on my calendar").asJson()

        assertEquals(JsonPrimitive("what is on my calendar"), json["content"])
    }

    @Test
    fun soDoesEveryOtherKindOfMessage() {
        // Assistant, tool and system messages never carry one, and a change
        // here that reshaped them would break a turn that has nothing to do
        // with looking at anything.
        listOf(
            Message.system("be brief"),
            Message.assistant("done"),
            Message.tool("ok", answering = "c1"),
        ).forEach { message ->
            assertTrue("$message", contentOf(message) is JsonPrimitive)
        }
    }

    @Test
    fun animageMakesTheContentAnArrayOfParts() {
        val json = Message.user("what is this", listOf(shot)).asJson()

        val parts = json["content"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is this", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun theUrlIsNestedWhereTheProviderLooksForIt() {
        val json = Message.user("", listOf(shot)).asJson()

        val part = json["content"]!!.jsonArray.single().jsonObject
        assertEquals(shot.url, part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPictureWithNothingSaidCarriesNoEmptyTextPart() {
        // A part holding an empty string is one a provider may refuse, and
        // where it does not, it is a message that says nothing followed by a
        // picture, which is not what saying nothing means.
        val parts = contentOf(Message.user("", listOf(shot))).jsonArray

        assertEquals(1, parts.size)
        assertEquals("image_url", parts.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun severalKeepTheirOrder() {
        val first = Image("data:image/png;base64,b25l")
        val second = Image("data:image/png;base64,dHdv")

        val parts = contentOf(Message.user("", listOf(first, second))).jsonArray

        assertEquals(first.url, parts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(second.url, parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun aBodyCarryingOneIsStillOneRequest() {
        // The shape the core reads. A change here that produced two messages,
        // or a body the router could not parse, would fail at routing rather
        // than at the provider, which is a long way from the cause.
        val conversation = Conversation()
        conversation.append(Message.user("look", listOf(shot)))

        val body = Json.parseToJsonElement(conversation.body("mid")).jsonObject
        val messages = body["messages"]!!.jsonArray

        assertEquals(1, messages.size)
        assertTrue(messages.single().jsonObject["content"] is JsonArray)
    }
}
