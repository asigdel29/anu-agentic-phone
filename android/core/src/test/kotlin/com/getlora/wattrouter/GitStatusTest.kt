// GitStatusTest.kt: the envelope's two halves, and the lines they become.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against envelopes written out by hand. Whether the core produces
// these shapes is the router's own suite's claim; what this side does with them
// is here, and the case that matters is the one Recollection.kt throws away.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitStatusTest {
    @Test
    fun anErrorKeepsItsWords() {
        // The reason this does not reuse Recollection's decoding. "Not a
        // repository", a tree that would not walk and a path that is not there
        // want different answers, and a model told only that something failed
        // makes the same call again.
        val read = GitStatus.from("""{"error":"nothing at /tmp/x is a repository"}""")

        assertTrue("$read", read!!.isFailure)
        assertEquals(
            "nothing at /tmp/x is a repository",
            GitStatusTool.answer(read),
        )
    }

    @Test
    fun noEnvelopeAtAllIsNotAnErrorTheCoreGave() {
        // Null means the runtime could not allocate. Quoting an error nobody
        // wrote would attribute it to git.
        assertNull(GitStatus.from(null))
        assertNull(GitStatus.from("not json"))
        assertEquals("the repository could not be read at all", GitStatusTool.answer(null))
    }

    @Test
    fun aBranchWithWorkInItRendersInColumns() {
        val read = GitStatus.from(
            """{"ok":{"head":{"kind":"branch","name":"main"},
            "staged":[{"path":"a.kt","kind":"added"}],
            "unstaged":[{"path":"b.kt","kind":"modified"},{"path":"c.kt","kind":"deleted"}],
            "untracked":["d.kt"],"conflicted":[]}}""".trimIndent().replace("\n", ""),
        )

        assertEquals(
            """
            On branch main.

            Staged:
              added  a.kt

            Not staged:
              modified  b.kt
              deleted   c.kt

            Untracked:
              d.kt
            """.trimIndent(),
            GitStatusTool.answer(read),
        )
    }

    @Test
    fun aConflictIsItsOwnSectionRatherThanAChange() {
        // Rendered among the changes, a conflicted path is one the model
        // stages and commits.
        val said = GitStatusTool.describe(
            GitStatus(GitHead.Branch("main"), conflicted = listOf("a.kt")),
        )

        assertTrue(said, said.contains("Conflicted, and not committable until resolved:"))
        assertTrue(said, !said.contains("Staged:"))
    }

    @Test
    fun aCleanTreeSaysSo() {
        // A heading with nothing under it reads as a rendering that gave up.
        assertEquals(
            "On branch main.\nNothing staged, nothing changed, nothing untracked.",
            GitStatusTool.describe(GitStatus(GitHead.Branch("main"))),
        )
    }

    @Test
    fun aHeadThatIsNotABranchDoesNotPretendToBeOne() {
        assertTrue(
            GitStatusTool.describe(GitStatus(GitHead.Detached("abc1234")))
                .contains("at commit abc1234. A commit here belongs to no branch"),
        )
        assertTrue(
            GitStatusTool.describe(GitStatus(GitHead.Unborn("main")))
                .contains("has no commits yet. The next commit creates it"),
        )
    }

    @Test
    fun aStateThisBuildDoesNotKnowIsNoHeadRatherThanAGuess() {
        // Guessing "branch" would put a name in front of the model that
        // nothing gave it.
        val read = GitStatus.from("""{"ok":{"head":{"kind":"bisecting","name":"main"}}}""")

        assertNull(read!!.getOrThrow().head)
        assertTrue(GitStatusTool.answer(read).startsWith("The repository's head was not read."))
    }
}
