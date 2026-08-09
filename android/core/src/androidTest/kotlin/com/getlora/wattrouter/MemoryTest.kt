// MemoryTest.kt — the store, opened and asked for real.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On a device, because this is the only place the library loads. What the
// envelopes carry is checked by string rather than decoded: turning them into
// values is the next change, and a test that needed it would be testing two
// things at once.

package com.getlora.wattrouter

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryTest {
    private lateinit var directory: File

    private fun path() = File(directory, "memory.db").absolutePath

    @Before
    fun start() {
        directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "memory-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    @After
    fun finish() {
        directory.deleteRecursively()
    }

    @Test
    fun aStoreOpensWhereThereWasNothing() {
        // A fresh install: no database, and no history for the horizon to bound.
        val store = requireNotNull(Memory.open(path())) { "the store did not open" }
        store.close()
    }

    @Test
    fun whatWasRememberedIsRecalled() {
        // The whole point, through Kotlin, JNI, C, Rust and SQLite and back.
        requireNotNull(Memory.open(path())).use { store ->
            assertNotNull(store.remember("the spare key is with Dave next door", "user", "s", NOW))

            val found = requireNotNull(store.recall("where is the spare key", most = 5))
            assertTrue(found, found.contains("\"ok\""))
            assertTrue(found, found.contains("spare key"))
        }
    }

    @Test
    fun anEmptyStoreAnswersRatherThanFailing() {
        // A fresh install being asked a question, which is ordinary.
        requireNotNull(Memory.open(path())).use { store ->
            val found = requireNotNull(store.recall("anything", most = 5))
            assertTrue(found, found.contains("\"ok\""))
        }
    }

    @Test
    fun aTurnWithNoTextIsRefusedRatherThanStored() {
        // Nothing indexes it, so it could never be recalled and would still
        // count against the horizon.
        requireNotNull(Memory.open(path())).use { store ->
            val said = requireNotNull(store.remember("   ", "user", "s", NOW))
            assertTrue(said, said.contains("error"))
            assertTrue(said, said.contains("no text"))
        }
    }

    @Test
    fun theStoreSurvivesBeingClosedAndOpenedAgain() {
        // Which is every launch, and the horizon runs on the way in — so a
        // store the horizon broke would fail here rather than on a phone.
        requireNotNull(Memory.open(path())).use {
            it.remember("the bins go out on Tuesday", "user", "s", NOW)
        }

        requireNotNull(Memory.open(path())).use { second ->
            assertTrue(requireNotNull(second.recall("bins", most = 5)).contains("bins"))
        }
    }

    @Test
    fun aClosedStoreRefusesRatherThanUsingAFreedPointer() {
        val store = requireNotNull(Memory.open(path()))
        store.close()

        try {
            store.recall("anything")
            throw AssertionError("used a freed handle")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("closed"))
        }
    }

    @Test
    fun closingTwiceIsNotADoubleFree() {
        // The property the parity test asserts is still written; this is it
        // actually happening on a device.
        val store = requireNotNull(Memory.open(path()))
        store.close()
        store.close()
    }

    @Test
    fun aStoreThatWillNotOpenIsNullRatherThanACrash() {
        // A path inside a file rather than a directory. The native side reports
        // every reason the same way, which is why open answers null.
        val blocked = File(directory, "notadir").apply { writeText("x") }

        assertNull(Memory.open(File(blocked, "memory.db").absolutePath))
    }

    private companion object {
        /** Fixed rather than now(): a turn's age is data, not the clock. */
        const val NOW = 1_786_000_000L
    }
}
