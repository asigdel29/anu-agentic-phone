// TerminalTools.kt: running one command, and wording what came back.
//
// History
//   2026-08-12  A. Sigdel  Created with #677.
//
// Contents
//   RunCommandTool  Run one command where the git tools work.
//
// The half Terminal.kt deliberately left out. Nothing there words a Ran, for
// ScreenTools.kt's split: what to bound and how long to wait are prose a JVM
// test reaches, and how to say it to a model belongs with the tool, the way
// describe belongs to read_screen rather than to Phone.
//
// say is TapTool.say's shape: one when over a sealed result, one sentence per
// outcome, in a tool's companion rather than on the seam. Three outcomes exist
// because a failed command, a killed one and a shell that never started are
// three things a model acts on differently, and the wording has to keep them
// apart or the seam distinguished them for nobody.

package com.getlora.wattrouter

/** Run one command where the git tools work. */
class RunCommandTool(private val terminal: Terminal) : Tool {
    override val name = "run_command"

    override val purpose =
        "Run one command with the shell the phone ships, in the same working " +
            "directory the repository tools use. This is how to find out whether " +
            "a change works: build it, test it, format it, or ask git something " +
            "the other tools do not answer. Answers with the exit status and what " +
            "the command printed, with its errors mixed into the same text. Long " +
            "output is cut, so narrow it with head, grep or tail rather than " +
            "running it again and hoping for less."

    override val schema = """
        {"type":"object","properties":{"command":{"type":"string",
        "description":"One command line, as a shell reads it. It runs in the working directory."}},
        "required":["command"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  As [Terminal.run], which this is a wording over: one at a time, and for
     *  as long as the command takes. */
    override suspend fun run(arguments: String): String {
        val command = Tools.field(arguments, "command").trim()
        // Distinct from a command that ran and printed nothing, which is the
        // answer below. A model reading "exit 0" for a call it never filled in
        // learns that the empty string is a command.
        if (command.isEmpty()) return "a command needs something to run, so nothing was run"

        return say(terminal.run(command))
    }

    companion object {
        /**
         * What one command came back with, in words.
         *
         * The status leads, and is present even when it is zero: a compiler that
         * failed and one that succeeded quietly both print almost nothing, and
         * the number is the only thing that tells them apart.
         */
        internal fun say(ran: Ran): String = when (ran) {
            is Ran.Finished -> "exit ${ran.status}\n" + printed(ran.output, ran.dropped)
            is Ran.TimedOut ->
                "still running after $PATIENCE seconds, so it was stopped. It may " +
                    "have done some of what it was going to. What it printed " +
                    "first:\n" + printed(ran.output, ran.dropped)
            // The words are the whole answer, since nothing ran and there is no
            // status to read one off.
            is Ran.Refused -> ran.why
        }

        /**
         * What it printed, and how much of it is missing.
         *
         * The tail line is [ReadScreenTool.describe]'s, counting what was cut so
         * a model narrowing the command knows there was something to narrow.
         */
        private fun printed(output: String, dropped: Int): String {
            val body = if (output.isBlank()) "it printed nothing" else output.trimEnd()
            val more = if (dropped > 0) "\nand $dropped more characters not shown" else ""
            return body + more
        }
    }
}
