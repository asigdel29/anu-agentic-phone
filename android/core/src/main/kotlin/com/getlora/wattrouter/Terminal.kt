// Terminal.kt: running one command, and everything that can come back from it.
//
// History
//   2026-08-12  A. Sigdel  Created with #669.
//
// Contents
//   Ran          What one command came back with.
//   Bounded      What was kept of its output, and how much was not.
//   drain        Reading a stream and keeping the front of it.
//   Terminal     Running a command, as a seam.
//   SystemShell  One command through the shell the platform ships.
//
// The split is ScreenTools.kt's: what to bound and how long to wait are prose a
// JVM test reaches, and only the exec needs a device. Nothing here words a Ran,
// which belongs with the tool the way describe belongs to read_screen not Phone.
//
// The property holding across the file is that a command is one element of an
// argv array, never a line built by concatenation. what-android-allows.md fixed
// that and ExecDeviceTest holds it, because every shell injection in a tool of
// this kind is a string that was built rather than an array that was passed. The
// other three decisions sit beside what makes them: the bound at OUTPUT_LIMIT,
// the wait at PATIENCE, stderr where the process is built.

package com.getlora.wattrouter

import java.io.File
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Most characters of output one command may put in front of the model.
 *
 * Characters rather than lines, which is the decision and not the unit it looks
 * like: one line of a minified file is a megabyte, so a limit counted in lines
 * lets through exactly what a limit exists to stop. 4000 is about a thousand
 * tokens, and more is not refused: the model narrows with `head`, `grep` or
 * `tail`, all of which the platform ships.
 */
internal const val OUTPUT_LIMIT = 4000

/**
 * Seconds a command may take before it is killed.
 *
 * A guess, said as one. This exists for a formatter, a status and a test run; a
 * minute covers all three on a phone and keeps a command that will never finish
 * to a minute rather than an evening. ExecDeviceTest waits 20, a number for
 * `echo` rather than for work. That there has to be one is not a guess.
 */
internal const val PATIENCE = 60L

/** The shell every Android ships, and the one path here that is not portable. */
private const val SHELL = "/system/bin/sh"

/**
 * What one command came back with.
 *
 * Three outcomes rather than a nullable string, which is [Heard]'s shape and
 * [Done]'s: one answer for all of them cannot tell a failed compile from a shell
 * that never started.
 */
sealed interface Ran {
    /**
     * It finished by itself.
     *
     * @property status the exit status, zero included, since a command that
     *   printed nothing and worked reads exactly like one whose output vanished.
     * @property output stdout and stderr together, at most [OUTPUT_LIMIT] of it.
     * @property dropped what came after those, zero when nothing did.
     */
    data class Finished(val status: Int, val output: String, val dropped: Int) : Ran

    /** It was still running when the patience ran out, and has been killed. It
     *  carries what it printed first, since a command that hangs usually says
     *  where it got to before it does. */
    data class TimedOut(val output: String, val dropped: Int) : Ran

    /** Nothing ran, and why, in [Done.Refused]'s shape: the words are the whole
     *  answer, since there is no status to read one off. */
    data class Refused(val why: String) : Ran
}

/** What was kept of a command's output, and how much came after it. Counted
 *  rather than flagged, so a tail line can say how much is missing. */
internal data class Bounded(val shown: String, val dropped: Int)

/**
 * Read a stream to its end, keeping the first [limit] characters.
 *
 * To the end rather than abandoned once full: a pipe nobody drains fills, a
 * process writing into a full one stops, and a command that succeeded would then
 * be reported as one that hung. What follows the limit is read and thrown away.
 */
internal fun drain(from: Reader, limit: Int = OUTPUT_LIMIT): Bounded {
    val kept = StringBuilder()
    var dropped = 0
    // 4096 characters at a time, which is a pipe's own buffer and no decision.
    val buffer = CharArray(4096)

    while (true) {
        val read = from.read(buffer)
        if (read < 0) break
        val keep = minOf(maxOf(0, limit - kept.length), read)
        kept.appendRange(buffer, 0, keep)
        dropped += read - keep
    }

    return Bounded(kept.toString(), dropped)
}

/** Running a command, as a seam. */
interface Terminal {
    /**
     * Run one command, and wait for it.
     *
     * # Rely
     * Called from the turn loop, one at a time and in the order the model asked,
     * for [Tool.run]'s reason: a command writing a file and one reading it are a
     * correct sequence and a race if they overlap. Suspends for as long as the
     * command takes, bounded at [PATIENCE] seconds; what is still running then
     * is killed, and cancelling the caller kills it too.
     *
     * @param command as the model wrote it, WHERE it reaches a shell as one
     *   argument rather than concatenated into a line.
     * @return what came back, never null, and nothing throws: a command exiting
     *   nonzero is an ordinary outcome the model acts on.
     */
    suspend fun run(command: String): Ran
}

/**
 * One command through the shell the platform ships.
 *
 * @property path where commands run: the workspace the git tools have rather
 *   than a second one, absolute because a relative path resolves against this
 *   process's directory, which on Android is `/`.
 */
class SystemShell(private val path: String) : Terminal {

    override suspend fun run(command: String): Ran = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(SHELL, "-c", command)
                .directory(File(path))
                // Merged rather than kept apart. Two pipes have no interleaving
                // between them, so keeping them apart invents an order the
                // command did not have, and stderr is the wrong half to drop:
                // git, a compiler and a test runner put the diagnosis there and
                // nothing on stdout, so a model reading stdout alone reads an
                // empty answer beside a nonzero status and calls it success.
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            // What W^X refusing something looks like from here, and a missing
            // working directory too. The message carries which, and a sentence
            // per cause would be this file guessing at a platform it just asked.
            return@withContext Ran.Refused("the command could not be started: ${e.message}")
        }

        coroutineScope {
            // Drained on another thread rather than after the wait: read first,
            // a command that never returns blocks in the read and the timeout
            // below is never reached, which is the one thing ExecDeviceTest.run
            // could not be copied on.
            val output = async { drain(process.inputStream.bufferedReader()) }
            val finished = try {
                runInterruptible { process.waitFor(PATIENCE, TimeUnit.SECONDS) }
            } finally {
                // However this ended, cancellation included, and before the
                // output is collected: closing the process's end of the pipe is
                // what lets the read above reach the end of the stream, and on
                // one already exited it does nothing.
                process.destroyForcibly()
            }

            val bounded = output.await()
            if (finished) {
                Ran.Finished(process.exitValue(), bounded.shown, bounded.dropped)
            } else {
                Ran.TimedOut(bounded.shown, bounded.dropped)
            }
        }
    }
}
