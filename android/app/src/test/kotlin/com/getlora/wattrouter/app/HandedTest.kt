// HandedTest.kt: what is worth taking from a share, and what is not.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. Intent.ACTION_SEND is a string constant rather than a framework
// call, so the whole decision runs on the host and none of it needs somebody
// sharing something at an emulator.

package com.getlora.wattrouter.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandedTest {
    private fun shared(
        action: String? = Intent.ACTION_SEND,
        type: String? = "text/plain",
        text: String? = null,
        subject: String? = null,
    ) = handedIn(action, type, text, subject)

    @Test
    fun aPlainNoteIsItself() {
        assertEquals("remember the bins", shared(text = "  remember the bins  "))
    }

    @Test
    fun aPageKeepsItsTitle() {
        // A browser shares a title and a URL as two extras, and the title is
        // half of what was meant.
        assertEquals(
            "Rust 1.90 released\nhttps://example.com/rust-1-90",
            shared(text = "https://example.com/rust-1-90", subject = "Rust 1.90 released"),
        )
    }

    @Test
    fun aTitleThatIsTheTextIsNotSaidTwice() {
        // The common case for a plain note, and repeating it reads as a fault.
        assertEquals("remember the bins", shared(text = "remember the bins", subject = "remember the bins"))
    }

    @Test
    fun aSubjectOnItsOwnIsStillSomethingSomebodyMeantToSend() {
        assertEquals("Rust 1.90 released", shared(subject = "Rust 1.90 released"))
    }

    @Test
    fun launchingTheAppIsNotAShare() {
        // getIntent() answers with the launcher's intent every time, so an
        // action that is not a send must seed nothing at all.
        assertNull(shared(action = Intent.ACTION_MAIN, text = "remember the bins"))
        assertNull(shared(action = null, text = "remember the bins"))
    }

    @Test
    fun aFileIsNotTakenAsThoughItWereText() {
        // Refusing beats pasting a content URI the model cannot open and will
        // describe as though it had.
        assertNull(shared(type = "image/png", text = "content://media/1"))
        assertNull(shared(type = null, text = "content://media/1"))
    }

    @Test
    fun nothingSharedIsNothingTaken() {
        assertNull(shared(text = "   ", subject = "  "))
        assertNull(shared())
    }
}
