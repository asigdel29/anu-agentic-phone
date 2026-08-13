// GitWriteToolsTest.kt: what the three write tools refuse, and what they say.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-11  A. Sigdel  Took in the one that makes a repository, #393.
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
    var initialised = false

    override fun init() = answer.also { initialised = true }

    // No tool reaches this, and the tests below are of tools, so nothing here
    // records the call. It is on the interface because the app calls it.
    override fun identify(name: String, email: String) = answer

    override fun head() = answer

    override fun status() = answer

    override fun add(paths: List<String>) = answer.also { staged = paths }

    override fun commit(message: String) = answer.also { this.message = message }

    // On the interface for the app's sake, like identify: no tool reaches
    // either, so neither records the call.
    override fun remoteSet(name: String, url: String) = answer

    override fun fetch(name: String) = answer

    override fun push(remote: String, branch: String) = answer

    override fun pull(remote: String, branch: String) = answer
}

class GitWriteToolsTest {
    @Test
    fun makingARepositoryReadsDifferentlyFromFindingOne() {
        // The distinction the entry point exists for. Collapsed into one
        // success, a model that re-ran init would report having started work it
        // is in the middle of.
        assertTrue(made("""{"ok":{"kind":"created"}}""").startsWith("made this directory"))
        assertEquals(
            "there was already a repository here, and nothing was changed",
            made("""{"ok":{"kind":"already_there"}}"""),
        )
    }

    @Test
    fun aRefusedInitKeepsTheCoresWords() {
        // A path inside a file and a path nothing may write to are different
        // problems, and only the core knows which one it met.
        assertEquals(
            "Permission denied",
            made("""{"error":"Permission denied"}"""),
        )
    }

    @Test
    fun anInitAnswerNothingCanReadIsNotGuessedAt() {
        assertEquals("the repository could not be created at all", made(null))
        assertEquals("the repository answered nothing readable", made("not json"))
        // A kind a later core learned. Reading it as "created" would have the
        // model report having made something it did not.
        assertEquals(
            "the repository answered nothing readable",
            made("""{"ok":{"kind":"reopened"}}"""),
        )
    }

    @Test
    fun initAsksTheRepositoryAndSaysBackWhatItAnswered() = runTest {
        val worktree = Tracked("""{"ok":{"kind":"already_there"}}""")

        val said = GitInitTool(worktree).run("{}")

        assertTrue("the repository should have been asked", worktree.initialised)
        assertEquals("there was already a repository here, and nothing was changed", said)
    }

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
