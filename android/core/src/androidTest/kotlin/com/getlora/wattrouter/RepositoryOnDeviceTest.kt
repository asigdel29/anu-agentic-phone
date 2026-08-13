// RepositoryOnDeviceTest.kt: the ten entry points resolve and answer.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-11  A. Sigdel  Took in init, which the first test cannot use, #393.
//   2026-08-11  A. Sigdel  Took in identify, and the first commit to land on a
//                          device, #636.
//   2026-08-12  A. Sigdel  Took in the two network calls, #668. Neither reaches
//                          a network here and neither needs to: what is being
//                          claimed is that the symbols link.
//   2026-08-12  A. Sigdel  Took in push and pull, #671. What each can claim
//                          here differs and the tests say which. pull runs end
//                          to end over a path remote. push needs a bare
//                          repository as its target and nothing in
//                          Repository.kt can make one, so only its refusals
//                          are reachable from a device; the success path is
//                          proved in git.rs against a bare one a test creates.
//
// On a device, because this is the only place the library loads. The parity test
// in router/src/jni.rs holds the symbol names in step; this is the other half of
// that claim: that the names it compared actually link, and that an envelope
// survives the trip back into a Kotlin String.
//
// A directory that is not a repository is the subject of the first test on
// purpose. Every call there answers an error envelope, which exercises the whole
// path without needing a repository the emulator does not have. init is the one
// entry point that cannot be in it, because it exists to stop the directory
// being what that test needs, so it has its own with its own directory.

package com.getlora.wattrouter

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepositoryOnDeviceTest {
    private lateinit var directory: File

    @Before
    fun start() {
        directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "repository-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    @After
    fun finish() {
        directory.deleteRecursively()
    }

    @Test
    fun everyEntryPointLinksAndAnswers() {
        val repository = Repository(directory.absolutePath)

        // Named individually rather than looped, so a failure says which of the
        // four did not link instead of which iteration.
        val answers = mapOf(
            "head" to repository.head(),
            "status" to repository.status(),
            "add" to repository.add(listOf("nothing")),
            "commit" to repository.commit("nothing to commit"),
        )

        answers.forEach { (call, envelope) ->
            assertNotNull("$call returned nothing at all", envelope)
            assertTrue("$call answered $envelope", envelope!!.contains("error"))
        }
    }

    @Test
    fun aRepositoryIsMadeOnceAndFoundTheSecondTime() {
        val repository = Repository(directory.absolutePath)
        val tool = GitInitTool(repository)

        val first = runBlocking { tool.run("{}") }
        val second = runBlocking { tool.run("{}") }

        assertTrue(first, first.startsWith("made this directory"))
        assertEquals("there was already a repository here, and nothing was changed", second)

        // The payoff, and the thing no JVM test can claim: the other three
        // tools stop answering that this is not a repository.
        val status = repository.status()
        assertNotNull(status)
        assertTrue("status after init: $status", status!!.contains("\"ok\""))
    }

    @Test
    fun anIdentifiedRepositoryCanBeCommittedTo() {
        // The first commit this repository has ever written on a device.
        // Nothing set user.name or user.email before #636, and a phone has no
        // gitconfig for libgit2 to fall back to, so every commit here failed.
        val repository = Repository(directory.absolutePath)
        runBlocking { GitInitTool(repository).run("{}") }
        File(directory, "notes.txt").writeText("hello")

        val identified = repository.identify("Ada", "ada@example.com")
        repository.add(listOf("notes.txt"))
        val committed = repository.commit("Add a note")

        assertNotNull(identified)
        assertTrue("identify answered $identified", identified!!.contains("\"ok\""))
        assertNotNull(committed)
        assertTrue("commit answered $committed", committed!!.contains("\"ok\""))
    }

    @Test
    fun thePathsCrossAsJsonRatherThanAsAnArray() {
        // add is the one entry point carrying a structure, and the encoding
        // happens on this side. A path the core cannot parse would come back
        // complaining about JSON rather than about a repository.
        val answered = Repository(directory.absolutePath).add(listOf("a\"b", "c\\d"))

        assertNotNull(answered)
        assertTrue(answered!!, !answered.contains("JSON array of strings"))
    }

    @Test
    fun aRemoteCanBePointedAtAPathAndFetchedFrom() {
        // A path remote, which is a complete remote needing no transport, no
        // key and no network. What this proves is that the two new symbols
        // link and that their envelopes survive the trip back as a String;
        // reaching a real forge is a claim no emulator can make.
        val elsewhere = File(directory.parentFile, "elsewhere").apply { mkdirs() }
        val origin = Repository(elsewhere.absolutePath)
        runBlocking { GitInitTool(origin).run("{}") }
        origin.identify("Ada", "ada@example.com")
        File(elsewhere, "a.txt").writeText("first")
        origin.add(listOf("a.txt"))
        origin.commit("Add a file")

        val here = Repository(directory.absolutePath)
        runBlocking { GitInitTool(here).run("{}") }
        val pointed = here.remoteSet("origin", elsewhere.absolutePath)
        val fetched = here.fetch("origin")

        assertNotNull(pointed)
        assertTrue("remoteSet answered $pointed", pointed!!.contains("\"added\""))
        assertNotNull(fetched)
        assertTrue("fetch answered $fetched", fetched!!.contains("refs/remotes/origin/"))
        elsewhere.deleteRecursively()
    }

    @Test
    fun anHttpsRemoteIsRefusedOnTheDeviceToo() {
        // The refusal is the half of remoteSet that a person meets, so it is
        // worth knowing the words reach Kotlin rather than only Rust.
        val here = Repository(directory.absolutePath)
        runBlocking { GitInitTool(here).run("{}") }

        val refused = here.remoteSet("origin", "https://github.com/owner/repository.git")

        assertNotNull(refused)
        assertTrue("remoteSet answered $refused", refused!!.contains("git@host:"))
    }

    @Test
    fun aPullStartsTheBranchThisRepositoryDidNotHave() {
        // End to end on a device, and the ordinary first case rather than an
        // edge one: a repository made by init_repository and pointed at a
        // remote has no branch at all, so the first pull creates it.
        val elsewhere = File(directory.parentFile, "pull-from").apply { mkdirs() }
        val origin = Repository(elsewhere.absolutePath)
        runBlocking { GitInitTool(origin).run("{}") }
        origin.identify("Ada", "ada@example.com")
        File(elsewhere, "a.txt").writeText("first")
        origin.add(listOf("a.txt"))
        origin.commit("Add a file")
        val branch = branchOf(origin)

        val here = Repository(directory.absolutePath)
        runBlocking { GitInitTool(here).run("{}") }
        here.remoteSet("origin", elsewhere.absolutePath)
        val pulled = here.pull("origin", branch)

        assertNotNull(pulled)
        assertTrue("pull answered $pulled", pulled!!.contains("\"started\""))
        assertTrue("the file did not arrive", File(directory, "a.txt").exists())
        elsewhere.deleteRecursively()
    }

    @Test
    fun aPushWithNowhereToGoAndNothingToSendSaysWhich() {
        // Push's success needs a bare repository as its target and nothing
        // here can make one, so what a device proves about it is that the
        // symbol links and the refusal crosses back as words. That is a
        // smaller claim than the one above and reads as one.
        val here = Repository(directory.absolutePath)
        runBlocking { GitInitTool(here).run("{}") }
        here.identify("Ada", "ada@example.com")
        File(directory, "a.txt").writeText("first")
        here.add(listOf("a.txt"))
        here.commit("Add a file")

        val nowhere = here.push("origin", branchOf(here))
        here.remoteSet("origin", File(directory.parentFile, "absent").absolutePath)
        val nothing = here.push("origin", "not-a-branch")

        assertNotNull(nowhere)
        assertTrue("push answered $nowhere", nowhere!!.contains("no remote called origin"))
        assertNotNull(nothing)
        assertTrue("push answered $nothing", nothing!!.contains("not-a-branch"))
    }

    /**
     * The branch a repository is on, read rather than assumed.
     *
     * `init.defaultBranch` is a global setting, so the name a fresh repository
     * lands on is whatever the machine running this says. Hard-coding "master"
     * passes on one emulator image and fails on another.
     */
    private fun branchOf(repository: Repository): String {
        val head = repository.head().orEmpty()
        return Regex("\"name\":\"([^\"]+)\"").find(head)?.groupValues?.get(1)
            ?: error("no branch in $head")
    }
}
