// RepositoryOnDeviceTest.kt: the six entry points resolve and answer.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-11  A. Sigdel  Took in init, which the first test cannot use, #393.
//   2026-08-11  A. Sigdel  Took in identify, and the first commit to land on a
//                          device, #636.
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
}
