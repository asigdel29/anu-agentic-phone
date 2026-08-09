// RepositoryTest.kt — the paths as the core will read them.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM, and only the encoding: touching Repository itself runs
// System.loadLibrary and the library is built for aarch64-linux-android. That
// the four entry points are reachable is the instrumented suite's claim.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryTest {
    @Test
    fun ordinaryPathsAreAJsonArray() {
        assertEquals("""["src/main.rs","docs"]""", encodePaths(listOf("src/main.rs", "docs")))
    }

    @Test
    fun nothingStagedIsAnEmptyArrayRatherThanNothing() {
        // The core parses this before it decides anything, so "" would be a
        // refusal about JSON rather than the answer that nothing was named.
        assertEquals("[]", encodePaths(emptyList()))
    }

    @Test
    fun aQuoteInAFilenameDoesNotEndTheString() {
        assertEquals("""["a\"b"]""", encodePaths(listOf("a\"b")))
    }

    @Test
    fun theBackslashIsEscapedFirst() {
        // Done last, it would escape the backslashes just added for the quotes
        // and the core would look for a file with two of them in its name.
        assertEquals("""["a\\b"]""", encodePaths(listOf("a\\b")))
        assertEquals("""["a\\\"b"]""", encodePaths(listOf("a\\\"b")))
    }

    @Test
    fun aControlCharacterIsLegalOnDiskAndNotInJson() {
        // Left raw this produces an envelope the core cannot parse, and so
        // cannot explain — the model is told the JSON was wrong rather than
        // that the file it named is unusual.
        assertEquals("""["a\nb"]""", encodePaths(listOf("a\nb")))
        assertEquals("""["a\tb"]""", encodePaths(listOf("a\tb")))
    }
}
