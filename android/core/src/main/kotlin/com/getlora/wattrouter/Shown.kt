// Shown.kt: the Terminal that puts the command in front of somebody first.
//
// History
//   2026-08-12  A. Sigdel  Created with #673.
//
// The name is the difference from Confirmed. That decorator deliberately keeps
// the argument out of the prompt, because the text being typed can be a
// paragraph or a pasted password and the action is already named by the verb.
// Here the argument is the action: run_command approves nothing, and `git
// status` and `rm -rf .` are the same tool name. So what is shown is the
// command.
//
// It sits at the Terminal seam rather than in the tool, which is Budget.kt's
// reasoning: a second thing that runs a command reaches the shell through one
// object and is gated without its author knowing this exists. The tool decides
// what to say about a Ran; this decides whether there is one.
//
// The one place it disagrees with Confirmed is Plan. Confirmed treats Plan as
// Auto, and is right to: Planned put the round's tool names to somebody at the
// top of the turn, so by the time a tap arrives the approval has been given.
// That reasoning holds for every tool whose name is its action and fails for
// this one, since the name approved was run_command and the command was not in
// it. Ask and Plan therefore behave the same way here, which is an
// inconsistency between this tool and the other seventeen, and the
// inconsistency is in what a shell is rather than in this file.
//
// Nothing here reads the command. No list of dangerous commands, no parsing for
// a semicolon, no guess about what rm means: Autonomy.kt refuses that shape of
// rule everywhere else, and a shell is where it would be least defensible,
// since every one of those checks is one string interpolation away from being
// wrong about what will run.

package com.getlora.wattrouter

/**
 * A [Terminal] that shows the command to somebody before it runs.
 *
 * @param mode read per command rather than held for a turn, which is
 *   [Confirmed]'s rule for its reason: somebody who turns this on while a turn
 *   is running means the next command, and somebody who turns it off has stopped
 *   wanting to be asked.
 * @param consent whoever answers. The same seam the phone tools ask through, so
 *   there is one way of asking on this phone rather than two.
 */
class Shown(
    private val terminal: Terminal,
    private val mode: () -> Autonomy,
    private val consent: Consent,
) : Terminal {

    override suspend fun run(command: String): Ran = when {
        mode() == Autonomy.AUTO -> terminal.run(command)
        // The command whole, and never a front of it. An elision is where the
        // second half of `ls; rm -rf .` hides, and a prompt that shows a command
        // it has shortened is one somebody approves without having seen what
        // runs. A command long enough to be unreadable is a real cost and the
        // honest one: the answer to it is a shorter command, not a shorter
        // prompt.
        consent.mayI(Intent("run", command)) -> terminal.run(command)
        else -> Ran.Refused(
            // A person rather than a policy, in Confirmed's words and for its
            // reason: a model told a rule refused it looks for another way
            // through, and there are many ways through a shell. "did not allow"
            // rather than "said no", because a phone with nowhere to show the
            // question answers false without anybody having been asked.
            "the person using the phone did not allow that command. They see " +
                "each command before it runs in this mode, and this one was " +
                "not approved. Do not try it another way or in pieces. Say " +
                "what you were going to run and why.",
        )
    }
}
