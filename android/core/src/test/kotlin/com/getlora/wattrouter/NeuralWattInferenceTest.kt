// NeuralWattInferenceTest.kt — the client, against a socket that answers.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// On the JVM over a real loopback server rather than a stubbed transport.
// Nothing in the client is Android-only, and what is worth checking is what a
// stub would have to be told to imitate: which status codes mean retry, what
// reaches the wire, and that nothing is emitted before the status is known.

package com.getlora.wattrouter

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NeuralWattInferenceTest {
    private lateinit var server: HttpServer
    private var seen: String = ""
    private var headers: Map<String, List<String>> = emptyMap()

    private fun serve(status: Int, body: String) {
        server.createContext("/v1/chat/completions") { exchange: HttpExchange ->
            seen = exchange.requestBody.readBytes().decodeToString()
            headers = exchange.requestHeaders.toMap()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun asking() = NeuralWattInference(
        credential = "nw-test",
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
    )

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun anAnswerArrivesInThePiecesItWasSentIn() = runTest {
        serve(
            200,
            """
            data: {"choices":[{"delta":{"content":"the bins "}}]}
            data: {"choices":[{"delta":{"content":"go out Tuesday"}}]}
            data: [DONE]
            """.trimIndent(),
        )

        val got = asking().complete(Conversation(), model = "cheap").toList()

        assertEquals(
            listOf(StreamEvent.Text("the bins "), StreamEvent.Text("go out Tuesday")),
            got,
        )
    }

    @Test
    fun aToolCallArrivesWholeOrNotAtAll() = runTest {
        // Split across three chunks, as a provider sends it. Nothing may be
        // emitted until the finish reason: a yielded event commits the chain.
        serve(
            200,
            """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"recall","arguments":""}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\":"}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}
            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
            data: [DONE]
            """.trimIndent(),
        )

        val got = asking().complete(Conversation(), model = "mid").toList()

        assertEquals(listOf(StreamEvent.Call(ToolCall("c1", "recall", """{"q":1}"""))), got)
    }

    @Test
    fun theRequestCarriesTheToolsAndTheCredential() = runTest {
        // #319: on iOS the tools array does not reach the wire, so every
        // registered tool is invisible. This is what would have caught it.
        serve(200, "data: [DONE]")
        val tools = """[{"type":"function","function":{"name":"recall"}}]"""

        asking().complete(Conversation(), model = "mid", tools = tools).toList()

        assertTrue(seen, seen.contains(""""tools":$tools"""))
        assertTrue(seen, seen.contains(""""stream":true"""))
        assertTrue(seen, seen.contains(""""model":"mid""""))
        assertEquals(listOf("Bearer nw-test"), headers["Authorization"])
        assertEquals(listOf("text/event-stream"), headers["Accept"])
    }

    @Test
    fun aServerErrorOrARateLimitIsWorthAnotherModel() = runTest {
        // 429 counted with the 5xx deliberately: the body is fine, it is a
        // wait, and the next model in the chain has its own budget.
        for (status in listOf(500, 503, 429)) {
            serve(status, "upstream busy")
            val thrown = runCatching { asking().complete(Conversation(), "heavy").toList() }
                .exceptionOrNull()

            assertTrue("$status gave $thrown", thrown is InferenceError.Unavailable)
            assertTrue("$status", (thrown as InferenceError).isWorthAnotherModel)
            server.removeContext("/v1/chat/completions")
        }
    }

    @Test
    fun aRejectionStopsTheChain() = runTest {
        // Every model behind the same API rejects the same body, so retrying
        // turns one bad request into six.
        serve(400, "tool schema is not an object")

        val thrown = runCatching { asking().complete(Conversation(), model = "mid").toList() }
            .exceptionOrNull() as InferenceError

        assertTrue("$thrown", thrown is InferenceError.Rejected)
        assertTrue(thrown.isWorthAnotherModel.not())
        assertTrue(thrown.message.orEmpty(), thrown.message.orEmpty().contains("tool schema"))
    }

    @Test
    fun nothingIsEmittedBeforeTheStatusIsKnown() = runTest {
        // The contract that matters most: a caller that has seen one event will
        // not retry, so emitting before a 503 forecloses the retry.
        serve(503, """data: {"choices":[{"delta":{"content":"half an answer"}}]}""")

        val got = mutableListOf<StreamEvent>()
        runCatching { asking().complete(Conversation(), model = "heavy").collect { got += it } }

        assertEquals(emptyList<StreamEvent>(), got)
    }
}
