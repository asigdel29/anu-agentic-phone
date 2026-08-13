// GitSendToolsTest.kt: what the model reads after sending a branch or taking one.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// On the JVM against a Worktree answering a canned envelope, as
// GitRemoteToolsTest. What is under test is the wording and the refusing; that
// a non-fast-forward is detected at all is git.rs's, against a real repository.
//
// The one to read first is that force is in neither schema. It is asserted
// rather than left to review, because the way this goes wrong is somebody
// adding it for a good reason on a day when the error will not go away.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A repository that answers the same envelope to everything, and remembers. */
private class Sending(private val answer: String?) : Worktree {
    val asked = mutableListOf<String>()

    override fun init(): String? = answer
    override fun identify(name: String, email: String): String? = answer
    override fun head(): String? = answer
    override fun status(): String? = answer
    override fun add(paths: List<String>): String? = answer
    override fun commit(message: String): String? = answer
    override fun remoteSet(name: String, url: String): String? = answer
    override fun fetch(name: String): String? = answer

    override fun push(remote: String, branch: String): String? =
        answer.also { asked += "push $remote $branch" }

    override fun pull(remote: String, branch: String): String? =
        answer.also { asked += "pull $remote $branch" }
}

class GitSendToolsTest {

    @Test
    fun forceIsInNeitherSchema() {
        // Not defaulted to false, not hidden, not present. The schema is what a
        // model reads as the set of things it may say, so it is the only place
        // the word must not appear: PushTool.purpose uses it to say there is no
        // way to force this, which is the sentence that stops it looking.
        for (tool in listOf(PushTool(Sending(null)), PullTool(Sending(null)))) {
            assertFalse(tool.schema, tool.schema.contains("force"))
        }
        assertTrue(PushTool(Sending(null)).purpose.contains("no way to force this"))
    }

    @Test
    fun aPushThatWorkedSaysWhatTheRemoteNowHas() = runTest {
        val said = PushTool(Sending("""{"ok":null}""")).run("""{"remote":"origin","branch":"main"}""")

        assertTrue(said, said.contains("sent main"))
    }

    @Test
    fun aRefusedPushCarriesTheCoreSWordsRatherThanASummary() = runTest {
        // The refusal names what to do next and says it is not something to
        // retry. A tool that summarised it would drop exactly that.
        val why = "refs/heads/main was refused by the remote: fetch first"
        val said = PushTool(Sending("""{"error":"$why"}"""))
            .run("""{"remote":"origin","branch":"main"}""")

        assertEquals(why, said)
    }

    @Test
    fun aPushWithNoBranchIsNotAttempted() = runTest {
        val repository = Sending("""{"ok":null}""")

        val said = PushTool(repository).run("""{"remote":"origin"}""")

        assertEquals(emptyList<String>(), repository.asked)
        assertTrue(said, said.contains("needs a branch"))
    }

    @Test
    fun aPullThatMovedNothingIsAStateRatherThanAFailure() = runTest {
        val said = PullTool(Sending("""{"ok":{"kind":"already_here"}}"""))
            .run("""{"remote":"origin","branch":"main"}""")

        assertTrue(said, said.contains("nothing this branch did not already have"))
    }

    @Test
    fun aFastForwardSaysWhereItMovedTo() = runTest {
        val said = PullTool(Sending("""{"ok":{"kind":"fast_forwarded","commit":"a1b2c3d"}}"""))
            .run("""{"remote":"origin","branch":"main"}""")

        assertTrue(said, said.contains("a1b2c3d"))
    }

    @Test
    fun aBranchThatWasNotHereIsStartedRatherThanFastForwarded() = runTest {
        // The ordinary first pull: init_repository then set_remote leaves a
        // repository with no branch at all.
        val said = PullTool(Sending("""{"ok":{"kind":"started","commit":"9f8e7d6"}}"""))
            .run("""{"remote":"origin","branch":"main"}""")

        assertTrue(said, said.contains("no such branch here"))
        assertTrue(said, said.contains("9f8e7d6"))
    }

    @Test
    fun aPullThatWouldMergeCarriesTheRefusal() = runTest {
        val why = "main and the remote have both moved on, so nothing was changed"
        val said = PullTool(Sending("""{"error":"$why"}"""))
            .run("""{"remote":"origin","branch":"main"}""")

        assertEquals(why, said)
    }

    @Test
    fun anEnvelopeThatDidNotArriveIsSaidRatherThanGuessedAt() = runTest {
        assertTrue(PushTool(Sending(null)).run("""{"remote":"o","branch":"m"}""")
            .contains("could not be attempted at all"))
        assertTrue(PullTool(Sending(null)).run("""{"remote":"o","branch":"m"}""")
            .contains("could not be attempted at all"))
    }
}
