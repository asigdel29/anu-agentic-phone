// Kept.kt: the Terminal that remembers what ran through it.
//
// History
//   2026-09-17  A. Sigdel  Created with #713.
//
// The fourth decorator at the Terminal seam, and Recorded.kt's ordering rule
// carried over to it: wrap inside everything else, so a command the consent
// gate refused never reaches run() and never enters the record. Shown decides
// whether there is a command; this decides what is worth keeping about the
// ones there were, and the two are apart because they are different questions.
//
// The recording is after the run rather than before, which is how Shown orders
// its own decision turned round: it must not let through what nobody saw, and
// this must not keep what did not happen.

package com.getlora.wattrouter

/**
 * A [Terminal] that keeps every command that reached it.
 *
 * Wrap inside everything else: `Shown(Kept(terminal))`. A command the consent
 * gate refused is answered by [Shown] alone and never reaches [run], so a
 * scrollback showing one would show a command that did not run, which is
 * [Recorded]'s stated ordering rule carried from the Phone seam to this one.
 *
 * Everything that does arrive is kept with the [Ran] it answered, a
 * [Ran.Refused] from below included: this seam sits under every gate, so a
 * refusal here is the platform saying it could not start the command, and that
 * is what somebody reading the scrollback wants to find.
 */
class Kept(
    private val terminal: Terminal,
    private val scrollback: Scrollback,
) : Terminal {

    override suspend fun run(command: String): Ran {
        val ran = terminal.run(command)
        scrollback.add(KeptCommand(command, ran))
        return ran
    }
}
