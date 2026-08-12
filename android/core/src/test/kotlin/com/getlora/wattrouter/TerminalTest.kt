// TerminalTest.kt: what is kept of a command's output, and what is not.
//
// History
//   2026-08-12  A. Sigdel  Created with #669.
//
// On the JVM, and nothing here starts a process: the half under test is the
// bounding, which needs no device to be wrong. Whether a command runs at all is
// TerminalDeviceTest's, on an emulator, because /system/bin/sh is not on this
// host.
//
// The test to read first is the one over a single long line. A limit counted in
// lines would pass it untouched, which is the whole reason the unit here is
// characters.

package com.getlora.wattrouter

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTest {

    @Test
    fun shortOutputIsNotCut() {
        val bounded = drain(StringReader("here.txt\n"), limit = 100)

        assertEquals("here.txt\n", bounded.shown)
        assertEquals(0, bounded.dropped)
    }

    @Test
    fun whatWasCutIsCounted() {
        // Counted rather than flagged, so the tail line can say how much is
        // missing and the model can decide whether to ask again for the rest.
        val bounded = drain(StringReader("0123456789abcdef"), limit = 10)

        assertEquals("0123456789", bounded.shown)
        assertEquals(6, bounded.dropped)
    }

    @Test
    fun oneLongLineIsBoundedTheSameAsManyShortOnes() {
        // A line is not a bound. One line of a minified file is a megabyte, so
        // a limit counted in lines would let this through whole.
        val bounded = drain(StringReader("x".repeat(50_000)))

        assertEquals(OUTPUT_LIMIT, bounded.shown.length)
        assertEquals(50_000 - OUTPUT_LIMIT, bounded.dropped)
    }

    @Test
    fun theStreamIsReadToItsEndRatherThanAbandoned() {
        // The property that keeps a working command from being reported as a
        // hung one: a pipe nobody drains fills, and a process writing into a
        // full one stops.
        val reader = StringReader("0123456789")

        drain(reader, limit = 2)

        assertEquals(-1, reader.read())
    }
}
