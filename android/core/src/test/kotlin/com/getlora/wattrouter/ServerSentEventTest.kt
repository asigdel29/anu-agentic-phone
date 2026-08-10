// ServerSentEventTest.kt: what a line of the provider's body means.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// A pure function of a line, so these are lines. No network, no coroutine, no
// device: the point of separating the reader from the client is that the wire
// format can be got wrong on its own and found out on its own.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSentEventTest {

    @Test
    fun textArrivesAsText() {
        val read = ServerSentEvent.decoding(
            """data: {"choices":[{"delta":{"content":"the bins"}}]}""",
        )

        assertEquals(listOf(ServerSentEvent.Text("the bins")), read)
    }

    @Test
    fun aLineThatIsNotDataMeansNothing() {
        // Comments, blanks and fields this client does not read. The format
        // working as intended, so there is nothing to report.
        assertEquals(emptyList<ServerSentEvent>(), ServerSentEvent.decoding(""))
        assertEquals(emptyList<ServerSentEvent>(), ServerSentEvent.decoding(": keep-alive"))
        assertEquals(emptyList<ServerSentEvent>(), ServerSentEvent.decoding("event: message"))
    }

    @Test
    fun theSpaceAfterTheColonIsOptional() {
        // Both spellings are seen in the wild, and a client that reads only one
        // reads nothing at all from the provider that uses the other.
        assertEquals(listOf(ServerSentEvent.Done), ServerSentEvent.decoding("data: [DONE]"))
        assertEquals(listOf(ServerSentEvent.Done), ServerSentEvent.decoding("data:[DONE]"))
    }

    @Test
    fun anEmptyDeltaIsNotAChunk() {
        // The first event of a completion carries the role and no text. Emitting
        // "" for it would commit a chain walk to a model that has not said
        // anything, which is exactly the decision the walk exists to make.
        val read = ServerSentEvent.decoding(
            """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
        )

        assertEquals(emptyList<ServerSentEvent>(), read)
    }

    @Test
    fun oneLineCanMeanSeveralThings() {
        // The reason this returns a list. A delta carrying text and a tool call
        // in one chunk, read as a single event, loses whichever came second.
        val read = ServerSentEvent.decoding(
            """
            data: {"choices":[{"delta":{"content":"looking","tool_calls":[
              {"index":0,"id":"call_1","function":{"name":"recall","arguments":"{\"q\""}}]},
              "finish_reason":"tool_calls"}]}
            """.trimIndent().replace("\n", ""),
        )

        assertEquals(3, read.size)
        assertTrue(read[0] is ServerSentEvent.Text)
        assertTrue(read[1] is ServerSentEvent.Call)
        assertEquals(ServerSentEvent.Finished(FinishReason.ToolCalls), read[2])
    }

    @Test
    fun twoCallsInOneChunkBothSurvive() {
        // Parallel tool calls are an array, and taking the first is how the
        // second silently never runs.
        val read = ServerSentEvent.decoding(
            """data: {"choices":[{"delta":{"tool_calls":[""" +
                """{"index":0,"id":"a","function":{"name":"recall","arguments":""}},""" +
                """{"index":1,"id":"b","function":{"name":"remember","arguments":""}}]}}]}""",
        )

        assertEquals(2, read.size)
        assertEquals("a", (read[0] as ServerSentEvent.Call).fragment.id)
        assertEquals(1, (read[1] as ServerSentEvent.Call).fragment.index)
    }

    @Test
    fun aFragmentWithoutAnIdIsAContinuation() {
        // Which is most of them: the arguments arrive a few characters at a
        // time, and only the first fragment for an index carries id and name.
        val read = ServerSentEvent.decoding(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]}}]}""",
        )

        val fragment = (read.single() as ServerSentEvent.Call).fragment
        assertEquals(null, fragment.id)
        assertEquals(null, fragment.name)
        assertEquals(":1}", fragment.arguments)
    }

    @Test
    fun aReasonNobodyTaughtItArrivesIntact() {
        // An enum would fail the stream here. The set is the provider's to
        // extend and almost nothing reads this value.
        val read = ServerSentEvent.decoding(
            """data: {"choices":[{"delta":{},"finish_reason":"content_filter"}]}""",
        )

        assertEquals(listOf(ServerSentEvent.Finished(FinishReason("content_filter"))), read)
    }

    @Test
    fun aDataLineThatWillNotParseIsAnError() {
        // Not a skip. Skipping drops the model's text and reports success, and
        // an answer that quietly loses a sentence looks like a short answer.
        try {
            ServerSentEvent.decoding("data: {not json")
            throw AssertionError("read it anyway")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("not a chunk"))
        }
    }
}
