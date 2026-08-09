// GitWriteToolsTest.kt — what the two write tools refuse, and what they say.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against a scripted Worktree, which is the seam's whole purpose:
// Repository loads the library when its class initialises, so a tool holding one
// cannot be built on the host at all.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Tracked(private val answer: String?) : Worktree {
    var staged: List<String>? = null
    var message: String? = null

    override fun head() = answer

    override fun status() = answer

    override fun add(paths: List<String>) = answer.also { staged = paths }

    override fun commit(message: String) = answer.also { this.message = message }
}

class GitWriteToolsTest {
    @Test
    fun aCommitIsSaidBackWithItsId() {
        // "Committed" alone leaves a model unable to reference what it wrote,
        // and it reads the log to find out.
        assertEquals("committed abc1234", committed("""{"ok":"abc1234"}"""))
    }

    @Test
    fun aRefusedCommitKeepsTheCoresWords() {
        // Committing nothing is refused in the core on purpose: libgit2 writes
        // a commit whose tree matches its parent without complaint.
        assertEquals(
            "there is nothing staged to commit",
            committed("""{"error":"there is nothing staged to commit"}"""),
        )
    }

    @Test
    fun noEnvelopeIsNotAnErrorTheCoreGave() {
        assertEquals("the commit could not be attempted at all", committed(null))
        assertEquals("the repository answered nothing readable", committed("not json"))
    }

    @Test
    fun pathsAreReadAsAListOfStrings() {
        assertEquals(listOf("a.kt", "docs"), stagedPaths("""{"paths":["a.kt","docs"]}"""))
        assertEquals(emptyList<String>(), stagedPaths("""{"paths":[]}"""))
    }

    @Test
    fun anythingThatIsNotAListOfStringsIsRefusedRatherThanCoerced() {
        // A nested structure where a path belongs is not a path the model can
        // be told is missing, so it is refused before the core sees it.
        assertNull(stagedPaths("""{"paths":[{"path":"a.kt"}]}"""))
        assertNull(stagedPaths("""{"paths":"a.kt"}"""))
        assertNull(stagedPaths("""{}"""))
        assertNull(stagedPaths("not json"))
    }

    @Test
    fun stagingNothingIsNotTheSameComplaintAsStagingWrongly() = runTest {
        // Told the arguments were wrong, a model rewrites arguments it had
        // written correctly.
        val worktree = Tracked(null)
        val tool = GitAddTool(worktree)

        assertEquals("no paths were named, so nothing was staged", tool.run("""{"paths":[]}"""))
        assertTrue(tool.run("""{"paths":{}}""").startsWith("paths must be a list of strings"))
        assertNull("neither call should have reached the repository", worktree.staged)
    }

    @Test
    fun stagingAnswersWithTheStatusThatResulted() = runTest {
        // Rather than "done". A model that cannot see what landed does it again.
        val worktree = Tracked("""{"ok":{"head":{"kind":"branch","name":"main"},
            "staged":[{"path":"a.kt","kind":"added"}]}}""".replace("\n", ""))

        val said = GitAddTool(worktree).run("""{"paths":["a.kt"]}""")

        assertEquals(listOf("a.kt"), worktree.staged)
        assertTrue(said, said.contains("On branch main."))
        assertTrue(said, said.contains("added  a.kt"))
    }

    @Test
    fun aCommitWithNoMessageIsRefusedBeforeTheCore() = runTest {
        // git allows an empty message, and a history of them is a history
        // nobody can read.
        val worktree = Tracked(null)

        assertEquals(
            "a commit needs a message, so nothing was committed",
            GitCommitTool(worktree).run("""{"message":"   "}"""),
        )
        assertNull("the core should not have been asked", worktree.message)
    }

    @Test
    fun aCommitIsMadeWithTheMessageAsWritten() = runTest {
        val worktree = Tracked("""{"ok":"abc1234"}""")

        val said = GitCommitTool(worktree).run("""{"message":"  Add a thing  "}""")

        assertEquals("Add a thing", worktree.message)
        assertEquals("committed abc1234", said)
    }
}
