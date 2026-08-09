// Agent.kt — the turn loop: decide, ask, run tools, ask again.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Four properties, each a way this goes wrong quietly.
//
// A round is committed atomically: the assistant message and every tool result
// are appended together, so a round failing partway leaves the conversation as
// it was. Otherwise a call carries no answering tool message — a body the
// provider refuses on the *next* request, one turn after the cause.
//
// Tools run in order: a write then a read of one path is a sequence, or a race.
//
// The tier is re-decided every round, so a lookup that becomes a refactor does
// not stay on cheap because its first message was short.
//
// Eight rounds, and exceeding it throws. A model calling tools without ever
// answering is in a loop, and a quiet return at the cap reads as an answer.

package com.getlora.wattrouter

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Why a turn stopped without answering. */
sealed class AgentError(message: String) : Exception(message) {
    /** The core could not route the request at all. */
    class CannotDecide : AgentError("the router could not decide where to send this")

    /** The model kept asking for tools and never answered. */
    class TooManyRounds(val rounds: Int) :
        AgentError("gave up after $rounds rounds of tools")
}

/**
 * Where a request should go.
 *
 * A seam rather than [Core] itself, so the loop below runs without the native
 * library: this is the most decision-dense code here, and the emulator is the
 * slowest place to learn one of them is wrong. [Core.routing] is the real one.
 */
fun interface Routing {
    fun decide(body: String, session: String): Decision?
}

/** This core, as the loop sees it. */
fun Core.routing() = Routing { body, session -> Decision.from(decide(body, session)) }

/** One conversation, and the loop that advances it. */
class Agent(
    private val router: Routing,
    private val walk: ChainWalk,
    private val tools: ToolBox,
    val conversation: Conversation = Conversation(),
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    /**
     * What a turn may do to the phone, reset at the top of each one.
     *
     * Null when there is nothing to bound: an agent with no phone tools cannot
     * act on anything, and a budget it never spends is a number to keep in step
     * for no reason.
     */
    private val budget: Budget? = null,
    private val session: String = java.util.UUID.randomUUID().toString(),
) {
    /**
     * Say something, and run the turn it starts.
     *
     * # Rely
     * One turn at a time: the conversation is not synchronised and two would
     * interleave a round.
     */
    fun send(text: String): Flow<TurnEvent> = flow {
        conversation.append(Message.user(text))
        loop()
    }

    /**
     * Run a turn without saying anything first.
     *
     * For one interrupted before it committed: the message is already in the
     * conversation, and appending it again would ask twice.
     *
     * # Rely
     * As [send].
     */
    fun resume(): Flow<TurnEvent> = flow { loop() }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<TurnEvent>.loop() {
        // Here rather than in send(), so a resumed turn gets a fresh allowance
        // rather than inheriting a spent one — which is the case an interrupt
        // produces, and the one where somebody has just said carry on.
        budget?.beginTurn()

        repeat(maxRounds) {
            val round = ask()

            // Built aside and appended together: never a call without its
            // answer, whatever fails in between.
            val committed = mutableListOf(Message.assistant(round.text, round.calls))
            for (call in round.calls) {
                val result = tools.run(call)
                emit(TurnEvent.Result(result))
                committed += Message.tool(result.content, answering = call.id)
            }
            committed.forEach(conversation::append)

            if (round.calls.isEmpty()) return
        }
        throw AgentError.TooManyRounds(maxRounds)
    }

    /** One exchange: decide, walk the chain, gather what came back. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<TurnEvent>.ask(): Round {
        val decision = router.decide(conversation.body(), session)
            ?: throw AgentError.CannotDecide()
        emit(TurnEvent.Decided(decision))

        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()

        walk.complete(conversation, decision.chain, tools.definitions()).collect { event ->
            when (event) {
                is TurnEvent.Text -> text.append(event.text)
                is TurnEvent.Call -> calls += event.call
                else -> Unit
            }
            emit(event)
        }

        // A cancelled walk finishes without throwing, and committing what it
        // produced would append a truncated answer as though it were whole.
        currentCoroutineContext().ensureActive()
        return Round(text.toString(), calls)
    }

    private data class Round(val text: String, val calls: List<ToolCall>)

    companion object {
        /**
         * Rounds of tools before a turn is abandoned. Eight is what iOS settled
         * on: enough for read-think-write-check, short enough that a loop costs
         * a few requests rather than a bill.
         */
        const val DEFAULT_MAX_ROUNDS = 8
    }
}
