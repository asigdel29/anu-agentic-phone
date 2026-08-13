// GitRemoteToolsTest.kt: what the model reads after pointing a remote or
// fetching from one.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// On the JVM against a Worktree that answers a canned envelope, in
// GitWriteToolsTest's shape: what is under test is the wording, and the core
// that writes those envelopes is tested in git.rs where it is written.
//
// The two to read first are the ones about absence. A fetch that moved nothing
// and a remote that already pointed there are both successes, and both read as
// failures if the words for them are the words for doing something.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A repository that answers the same envelope to everything, and remembers. */
private class Pointing(private val answer: String?) : Worktree {
    val asked = mutableListOf<String>()

    override fun init(): String? = answer
    override fun identify(name: String, email: String): String? = answer
    override fun head(): String? = answer
    override fun status(): String? = answer
    override fun add(paths: List<String>): String? = answer
    override fun commit(message: String): String? = answer

    override fun remoteSet(name: String, url: String): String? =
        answer.also { asked += "remoteSet $name $url" }

    override fun fetch(name: String): String? = answer.also { asked += "fetch $name" }
    override fun push(remote: String, branch: String): String? = answer
    override fun pull(remote: String, branch: String): String? = answer
}

class GitRemoteToolsTest {

    @Test
    fun aRemoteThatWasNotThereSaysSo() = runTest {
        val said = SetRemoteTool(Pointing("""{"ok":{"kind":"added"}}"""))
            .run("""{"name":"origin","url":"git@host:o/r.git"}""")

        assertTrue(said, said.contains("added the remote"))
    }

    @Test
    fun aRemoteThatMovedSaysWhereItUsedToPoint() = runTest {
        // The only record of it left anywhere after this call.
        val envelope = """{"ok":{"kind":"moved","from":"git@old:o/r.git"}}"""

        val said = SetRemoteTool(Pointing(envelope))
            .run("""{"name":"origin","url":"git@new:o/r.git"}""")

        assertTrue(said, said.contains("git@old:o/r.git"))
    }

    @Test
    fun aRemoteThatAlreadyPointedThereIsNotAFailure() = runTest {
        val said = SetRemoteTool(Pointing("""{"ok":{"kind":"unchanged"}}"""))
            .run("""{"name":"origin","url":"git@host:o/r.git"}""")

        assertTrue(said, said.contains("already pointed there"))
    }

    @Test
    fun anHttpsRemoteIsRefusedInTheCoreAndTheWordsAreCarried() = runTest {
        // The refusal is git.rs's and this asserts it is passed through rather
        // than replaced: it names the ssh form, which is what the model needs.
        val why = "https remotes are not supported. Use the ssh form"
        val said = SetRemoteTool(Pointing("""{"error":"$why"}"""))
            .run("""{"name":"origin","url":"https://host/o/r.git"}""")

        assertEquals(why, said)
    }

    @Test
    fun aRemoteWithNoNameIsNotSet() = runTest {
        val repository = Pointing("""{"ok":{"kind":"added"}}""")

        val said = SetRemoteTool(repository).run("""{"url":"git@host:o/r.git"}""")

        assertEquals(emptyList<String>(), repository.asked)
        assertTrue(said, said.contains("needs a name"))
    }

    @Test
    fun aFetchThatMovedNothingIsAStateRatherThanAFailure() = runTest {
        val said = FetchTool(Pointing("""{"ok":[]}""")).run("""{"remote":"origin"}""")

        assertTrue(said, said.contains("nothing this repository did not already have"))
    }

    @Test
    fun aFetchNamesWhatMoved() = runTest {
        val envelope = """{"ok":["refs/remotes/origin/main","refs/tags/v1"]}"""

        val said = FetchTool(Pointing(envelope)).run("""{"remote":"origin"}""")

        assertTrue(said, said.contains("refs/remotes/origin/main"))
        assertTrue(said, said.contains("refs/tags/v1"))
    }

    @Test
    fun aFetchWithNoRemoteIsNotAttempted() = runTest {
        val repository = Pointing("""{"ok":[]}""")

        val said = FetchTool(repository).run("""{}""")

        assertEquals(emptyList<String>(), repository.asked)
        assertTrue(said, said.contains("needs a remote"))
    }

    @Test
    fun anEnvelopeThatDidNotArriveIsSaidRatherThanGuessedAt() = runTest {
        assertTrue(SetRemoteTool(Pointing(null)).run("""{"name":"o","url":"git@h:o/r.git"}""")
            .contains("could not be set at all"))
        assertTrue(FetchTool(Pointing(null)).run("""{"remote":"origin"}""")
            .contains("could not be attempted at all"))
    }
}
