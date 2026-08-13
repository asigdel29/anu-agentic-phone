// Connected.kt: turning saved servers into tools a turn can run.
//
// History
//   2026-08-11  A. Sigdel  Created with #596.
//
// Contents
//   Reached  One server, and what came back from it.
//   connect  Every saved server, asked what it has.
//
// Connections holds addresses and Mcp.kt speaks the protocol. Nothing joined
// them, which is why McpServer has been merged and tested since #529 and
// reached by nothing.
//
// The decision this file exists for is what a server that is down does to a
// turn. It does nothing: a connection that fails contributes no tools and the
// rest are unaffected. One server behind a laptop that is shut is the ordinary
// case, and a phone that could not run a turn because of it would be a phone
// somebody disconnects the server from and never reconnects.
//
// So a failure is kept rather than thrown away. It is what a connections screen
// shows beside a server, and without it the screen can only say a server has no
// tools, which reads as a server with nothing on it rather than one that could
// not be asked.
//
// Asked here rather than per turn. tools() is two round trips, and doing that
// on every turn would put a network wait in front of a model that has not been
// asked anything yet. It also keeps the promise ToolBox relies on: the model is
// told the tools at the top of a turn, and a set that changes underneath it
// produces a call for a tool that is no longer there.

package com.getlora.wattrouter

import kotlinx.coroutines.CancellationException

/**
 * One server, and what came back from it.
 *
 * @property connection what was saved.
 * @property tools what it offered, already prefixed. Empty when it could not be
 *   asked, which [why] tells apart from a server offering nothing.
 * @property why the reason there are none, or null. Words from [McpFault],
 *   which is where a server's own explanation is turned into a sentence.
 */
data class Reached(
    val connection: Connection,
    val tools: List<Tool>,
    val why: String? = null,
)

/**
 * Ask every saved server what it has.
 *
 * # Arguments
 * * `connections`: what to ask, in the order they were saved, WHERE that order
 *   is the order their tools reach [ToolBox] and the first of a duplicate name
 *   wins.
 * * `rpc`: how to reach one. A seam so this is a JVM test rather than a
 *   network, which is the split [Rpc] exists for.
 *
 * # Returns
 * One [Reached] per connection, in the same order, including the ones that
 * failed. A caller wanting only the tools flattens it; a screen wanting to say
 * what happened reads `why`.
 *
 * # Rely
 * Called when the application starts or a server is added, not per turn. Two
 * round trips per connection, in order rather than at once: a phone on a slow
 * connection opening six sockets at once is a phone that opens six sockets at
 * once, and the list is short.
 *
 * Never throws except [CancellationException]. A server that is unreachable is
 * an ordinary outcome, and the caller is a screen that has to draw either way.
 */
suspend fun connect(
    connections: List<Connection>,
    rpc: (Connection) -> Rpc,
): List<Reached> = connections.map { connection ->
    try {
        Reached(connection, McpServer(connection.label, rpc(connection)).tools())
    } catch (stopped: CancellationException) {
        // Rethrown rather than reported. The caller's scope is being cancelled
        // and a Reached saying so would be drawn on a screen going away.
        throw stopped
    } catch (fault: Exception) {
        // Everything else, including whatever the transport threw. McpServer
        // turns its own failures into McpFault; a URL that will not parse
        // arrives from HttpRpc as something else, and both are the same thing
        // to somebody reading a list of servers.
        Reached(connection, emptyList(), fault.message ?: "could not be reached")
    }
}

/** Every tool that came back, in the order the servers were saved. */
fun List<Reached>.tools(): List<Tool> = flatMap { it.tools }
