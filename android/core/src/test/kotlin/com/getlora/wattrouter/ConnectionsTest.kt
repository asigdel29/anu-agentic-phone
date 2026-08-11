// ConnectionsTest.kt: what may be saved as a server, and why not.
//
// History
//   2026-08-10  A. Sigdel  Created with #596.
//
// On the JVM, which is why `refusing` is a separate function. Everything else
// in Connections is SharedPreferences and belongs on a device, the split
// Credential already makes for the same reason: the decision with a bug in it
// should not be the one that only runs on an emulator.
//
// The case to read first is https. A release build has no network security
// config and so cannot send cleartext at all, which means a plain-http server
// works for whoever built the APK and fails for everybody else, at the first
// tool call, with a platform error nobody can act on. Refusing it at the point
// of saving is the only place that failure has a sentence attached.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {

    @Test
    fun anOrdinaryPairIsAccepted() {
        assertNull(refusing("desk", "https://tools.example.com/mcp"))
    }

    @Test
    fun httpIsRefusedAndTheReasonSaysWhy() {
        // Not a preference. A released build inherits the platform default,
        // which forbids cleartext, so this address cannot work off this
        // developer's machine.
        val why = refusing("desk", "http://tools.example.com/mcp")

        assertNotNull(why)
        assertTrue(why!!, why.contains("https"))
        assertTrue(why, why.contains("released build"))
    }

    @Test
    fun somethingThatIsNotAUrlIsRefused() {
        assertNotNull(refusing("desk", "tools.example.com"))
        assertNotNull(refusing("desk", "ftp://tools.example.com"))
    }

    @Test
    fun aSchemeWithNoHostIsRefused() {
        // `https://` passes a startsWith check and is not an address. Saved, it
        // would be a server that fails at the first call rather than at the
        // field where somebody could fix it.
        assertNotNull(refusing("desk", "https://"))
        assertNotNull(refusing("desk", "https:///mcp"))
    }

    @Test
    fun aNameIsRequired() {
        assertNotNull(refusing("", "https://tools.example.com"))
        assertNotNull(refusing("   ", "https://tools.example.com"))
    }

    @Test
    fun aNameOfPunctuationIsRefused() {
        // It survives `prefixed` as underscores, so the model would be offered
        // `mcp___lookup` and somebody reading a tool list could not tell which
        // server it came from.
        val why = refusing("!!!", "https://tools.example.com")

        assertNotNull(why)
        assertTrue(why!!, why.contains("letters or numbers"))
    }

    @Test
    fun aNameAlreadyTakenIsRefused() {
        // Two servers with one label would offer two tools under one name, and
        // ToolBox keeps the first of a duplicate. The second server's tools
        // would be listed and never reachable.
        assertNotNull(refusing("desk", "https://other.example.com", taken = setOf("desk")))
        assertNull(refusing("desk", "https://other.example.com", taken = setOf("laptop")))
    }

    @Test
    fun surroundingSpaceIsNotWhatMakesAPairInvalid() {
        // Somebody pasting an address brings whitespace with it, which is
        // Credential.clean's case one layer up. Trimmed before judging, and
        // trimmed again before storing.
        assertNull(refusing("  desk  ", "  https://tools.example.com/mcp  "))
    }

    @Test
    fun everyRefusalIsSomethingToDo() {
        // A field that goes red without saying what to change is one people
        // retype unchanged. Each of these is a different fix.
        val reasons = listOf(
            refusing("", "https://x.example.com"),
            refusing("!!!", "https://x.example.com"),
            refusing("desk", ""),
            refusing("desk", "http://x.example.com"),
            refusing("desk", "x.example.com"),
            refusing("desk", "https://"),
            refusing("desk", "https://x.example.com", taken = setOf("desk")),
        )

        assertTrue(reasons.all { it != null })
        assertEquals("each says its own thing", reasons.size, reasons.toSet().size)
    }
}
