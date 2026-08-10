// RepositoryOnDeviceTest.kt: the four entry points resolve and answer.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On a device, because this is the only place the library loads. The parity test
// in router/src/jni.rs holds the symbol names in step; this is the other half of
// that claim: that the names it compared actually link, and that an envelope
// survives the trip back into a Kotlin String.
//
// A directory that is not a repository is the subject on purpose. Every call
// answers an error envelope for it, which exercises the whole path without
// needing a repository the emulator does not have.

package com.getlora.wattrouter

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
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
    fun thePathsCrossAsJsonRatherThanAsAnArray() {
        // add is the one entry point carrying a structure, and the encoding
        // happens on this side. A path the core cannot parse would come back
        // complaining about JSON rather than about a repository.
        val answered = Repository(directory.absolutePath).add(listOf("a\"b", "c\\d"))

        assertNotNull(answered)
        assertTrue(answered!!, !answered.contains("JSON array of strings"))
    }
}
