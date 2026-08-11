// HttpRpcTest.kt: the transport, against a server that is really listening.
//
// History
//   2026-08-10  A. Sigdel  Created with #596.
//
// On the JVM against com.sun.net.httpserver on a loopback port. A fake Rpc
// tests the protocol above it, which McpTest already does; what is worth asking
// here is what HttpURLConnection actually does with the bytes, and only a
// socket answers that.
//
// The case to read first is the failing status. A server that answers 500 with
// a JSON-RPC error in the body has said something a model can act on, and
// HttpURLConnection puts that body on errorStream rather than inputStream. Read
// only the happy stream and the sentence a server wrote to explain itself is
// replaced by a status code, which is the failure this file exists to prevent.
//
// runBlocking rather than runTest, in `serving` and nowhere else. There is no
// virtual time to advance here: every case waits on a real socket, and a second
// coroutine builder inside the first would be two ways of saying the same wait.

package com.getlora.wattrouter

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRpcTest {

    /** What one request was, so a case can read it back. */
    private class Heard {
        var body: String? = null
        var method: String? = null
        var contentType: String? = null
        var extra: String? = null
    }

    /**
     * A server on a loopback port, for the duration of the block.
     *
     * Port zero, so the operating system picks one that is free. A fixed port
     * is a test that fails on a machine already running something.
     */
    private fun serving(
        status: Int = 200,
        answer: String,
        block: suspend (String, Heard) -> Unit,
    ) = runBlocking {
        val heard = Heard()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange: HttpExchange ->
            heard.method = exchange.requestMethod
            heard.contentType = exchange.requestHeaders.getFirst("Content-Type")
            heard.extra = exchange.requestHeaders.getFirst("X-Token")
            heard.body = exchange.requestBody.bufferedReader().readText()

            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/mcp", heard)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aReplyComesBackWhole() {
        val reply = """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""

        serving(answer = reply) { url, _ ->
            assertEquals(reply, HttpRpc(url).ask("""{"method":"x"}"""))
        }
    }

    @Test
    fun theRequestIsAJsonPostCarryingWhatItWasGiven() {
        serving(answer = """{"result":{}}""") { url, heard ->
            HttpRpc(url).ask("""{"method":"tools/list"}""")

            assertEquals("POST", heard.method)
            assertEquals("application/json", heard.contentType)
            assertEquals("""{"method":"tools/list"}""", heard.body)
        }
    }

    @Test
    fun aFailingStatusStillGivesUpItsBody() {
        // The case this file is for. A JSON-RPC error under a 500 is a sentence
        // the server wrote to explain itself, and it arrives on errorStream.
        // Reading only the happy stream would replace it with the status code,
        // and #596's whole point is that a server refusing is an ordinary
        // outcome a model can be told about.
        val refusal = """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"no tool"}}"""

        serving(status = 500, answer = refusal) { url, _ ->
            assertEquals(refusal, HttpRpc(url).ask("{}"))
        }
    }

    @Test
    fun aHeaderTheCallerSuppliedIsSent() {
        // A server behind a token takes one here, so the token stays the
        // caller's and is never this object's to hold or log.
        serving(answer = """{"result":{}}""") { url, heard ->
            HttpRpc(url, mapOf("X-Token" to "shibboleth")).ask("{}")

            assertEquals("shibboleth", heard.extra)
        }
    }

    @Test
    fun aServerThatIsNotThereThrowsRatherThanAnswering() = runBlocking {
        // Left to McpServer and McpTool to turn into words. What matters here
        // is that it throws rather than answering an empty string, which would
        // reach `decoded` and be reported as a protocol violation the server
        // never committed.
        val nowhere = HttpRpc("http://127.0.0.1:1/mcp")

        assertTrue(runCatching { nowhere.ask("{}") }.isFailure)
    }

    @Test
    fun anEndpointThatDoesNotParseFailsWhereItIsCaught() = runBlocking {
        // From ask rather than the constructor. Whatever somebody saved is
        // worth reporting with the server's name on it, and McpServer is
        // already catching there.
        val bad = HttpRpc("not a url")

        assertTrue(runCatching { bad.ask("{}") }.isFailure)
    }

    @Test
    fun theProtocolAboveItWorksOverARealSocket() {
        // McpTest asks this against a fake Rpc. Once, end to end, over a socket
        // is what says the two halves fit: a fake answering the same string
        // would pass whatever HttpURLConnection did with the bytes.
        val listed = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"lookup","description":"look something up",
               "inputSchema":{"type":"object","properties":{}}}
            ]}}
        """.trimIndent()

        serving(answer = listed) { url, _ ->
            val tools = McpServer("desk", HttpRpc(url)).tools()

            assertEquals(1, tools.size)
            assertEquals("mcp_desk_lookup", tools.single().name)
            assertEquals("look something up", tools.single().purpose)
        }
    }
}
