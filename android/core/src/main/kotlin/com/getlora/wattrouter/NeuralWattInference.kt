// NeuralWattInference.kt: the provider, over the wire.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// HttpURLConnection rather than OkHttp: on Android the former is the latter
// underneath, so this is the platform client and adds nothing: the same call
// URLSession is on iOS.
//
// The contract that costs most to get wrong: nothing is emitted before the
// status is known. A caller that has seen one event treats the attempt as
// delivered and will not retry it, so emitting early forecloses the retry that
// would have worked. Status first, mapped, then the body.
//
// It sends `tools`. #319 records what happens otherwise: on iOS the array is
// built, tested, and reached only from tests, so every tool is invisible.

package com.getlora.wattrouter

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** The provider this stack routes to. */
class NeuralWattInference(
    private val credential: String,
    private val baseUrl: String = "https://api.neuralwatt.com/v1",
) : Inference {

    override fun complete(
        conversation: Conversation,
        model: String,
        tools: String?,
        maxTokens: Int?,
    ): Flow<StreamEvent> = flow {
        val connection = open()

        val status = runCatching {
            connection.outputStream.use { it.write(body(conversation, model, tools, maxTokens)) }
            connection.responseCode
        }.getOrElse {
            // No response at all: DNS, no route, a reset before headers.
            connection.disconnect()
            throw InferenceError.Unavailable(model, it.message ?: it::class.java.simpleName)
        }

        if (status !in 200..299) {
            val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw refusal(model, status, detail.take(DETAIL))
        }

        // Dispatchers.IO below: every call here blocks, and a Flow runs on
        // whatever collects it, which in this app is the main thread.
        try {
            connection.inputStream.bufferedReader().use { emitFrom(it) }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Read the body, line by line, into the events a caller wants. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.emitFrom(
        body: BufferedReader,
    ) {
        val assembly = ToolCallAssembly()

        for (line in body.lineSequence()) {
            // Per line rather than relying on emit: a stream that is all
            // tool-call fragments emits nothing for a long stretch, and
            // cancellation would go unnoticed until it ended.
            currentCoroutineContext().ensureActive()

            for (event in ServerSentEvent.decoding(line)) {
                when (event) {
                    is ServerSentEvent.Text -> emit(StreamEvent.Text(event.text))
                    is ServerSentEvent.Call -> assembly.add(event.fragment)
                    // Both mean no more fragments; isEmpty stops the second
                    // from running every tool again.
                    is ServerSentEvent.Finished, ServerSentEvent.Done ->
                        if (!assembly.isEmpty) assembly.take().forEach { emit(StreamEvent.Call(it)) }
                }
            }
        }

        // A body that ended without [DONE] or a finish reason still has calls
        // worth running: otherwise a connection closed a line early is a turn
        // that silently does nothing.
        if (!assembly.isEmpty) assembly.take().forEach { emit(StreamEvent.Call(it)) }
    }

    private fun open(): HttpURLConnection =
        (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_MS
            // No read timeout: a model thinking is a socket saying nothing, and
            // a turn is cancelled by its collector rather than by a clock.
            readTimeout = 0
            setRequestProperty("Authorization", "Bearer $credential")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }

    private fun body(
        conversation: Conversation,
        model: String,
        tools: String?,
        maxTokens: Int?,
    ): ByteArray {
        // Spliced rather than re-encoded: Conversation.body already writes the
        // messages as the provider wants them, and a second encoder here is a
        // second thing to keep in step.
        val fields = buildList {
            add(""""stream":true""")
            tools?.let { add(""""tools":$it""") }
            maxTokens?.let { add(""""max_tokens":$it""") }
        }
        val base = conversation.body(model)
        return (base.dropLast(1) + "," + fields.joinToString(",") + "}").toByteArray()
    }

    private fun refusal(model: String, status: Int, detail: String) =
        // 5xx is the provider's problem; 4xx is this request's, and every
        // model behind the same API rejects the same body. 429 goes with the
        // 5xx: a wait, not a bad request, and the next model has its own
        // budget.
        if (status >= 500 || status == 429) {
            InferenceError.Unavailable(model, "HTTP $status: $detail")
        } else {
            InferenceError.Rejected(model, status, detail)
        }

    private companion object {
        const val CONNECT_MS = 15_000

        /** How much of an error body to carry. It reaches a person's screen. */
        const val DETAIL = 400
    }
}
