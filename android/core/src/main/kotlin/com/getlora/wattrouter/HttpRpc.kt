// HttpRpc.kt: the transport Mcp.kt leaves behind a seam.
//
// History
//   2026-08-10  A. Sigdel  Created with #596.
//
// Mcp.kt puts the whole protocol behind Rpc so it can be a JVM test rather than
// a network, and says the implementation "lives in the app module beside the
// other things that need a platform". It does not need one. HttpURLConnection
// is java.net, the core already speaks it in NeuralWattInference, and a class
// here is a class the core's own suite can reach.
//
// HttpURLConnection rather than OkHttp for NeuralWattInference's reason: on
// Android the former is the latter underneath, so this is the platform client
// and adds nothing.
//
// It differs from that file in the two places the traffic differs.
//
// There is a read timeout. A model thinking is a socket saying nothing for a
// minute and that file sets none; a tool server that has said nothing for
// thirty seconds is a server that is not going to answer, and a turn waiting on
// one is a turn nobody can tell from a hang.
//
// A failing status is read rather than thrown past. A server that answers 500
// with a JSON-RPC error in the body has said something a model can act on, and
// treating the status alone as the outcome would throw that away. What is not
// JSON-RPC becomes an McpFault upstream, which is where that decision lives.

package com.getlora.wattrouter

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One MCP server, over HTTP.
 *
 * @param endpoint where the server listens, WHERE it is an absolute http or
 *   https URL. Not validated in the constructor: whatever a person saved is
 *   worth reporting with the server's name on it, and a URL that does not parse
 *   throws from [ask] where [McpServer] is already catching.
 * @param headers what to send besides the two this sets. A server behind a
 *   token takes one here, so the token is the caller's to hold and never this
 *   object's to log.
 */
class HttpRpc(
    private val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
) : Rpc {

    /**
     * # Rely
     * Runs on [Dispatchers.IO] rather than the caller's thread. Every call here
     * blocks, and the turn loop reaches this from wherever its collector runs,
     * which on a phone is the main thread. NeuralWattInference makes the same
     * move at the same seam and #474 has the measurement.
     *
     * # Errors
     * [IOException] IF the server cannot be reached, and whatever `URL` throws
     * for an endpoint that does not parse. Both are caught by [McpServer] and
     * [McpTool], which turn them into words rather than a dead turn.
     */
    override suspend fun ask(body: String): String = withContext(Dispatchers.IO) {
        val connection = open()
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }

            // errorStream on a failing status, inputStream otherwise, and both
            // are read. A JSON-RPC error can arrive under either.
            val stream = if (connection.responseCode in OK) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw IOException("HTTP ${connection.responseCode}")
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private companion object {
        /** Anything 2xx. A server may answer 202 and mean it. */
        val OK = 200..299

        const val CONNECT_MS = 10_000

        /**
         * Thirty seconds of silence is an answer.
         *
         * A tool call is a lookup or a write somewhere, not a model thinking,
         * so unlike the provider connection this has a clock. Without one a
         * server that accepted the socket and said nothing holds a turn until
         * somebody presses stop, and a turn that looks like a hang is worse
         * than one that says the server did not answer.
         */
        const val READ_MS = 30_000
    }
}
