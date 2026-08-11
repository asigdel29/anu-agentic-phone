// AndroidListeningTest.kt: reading the two answers the recognizer can give.
//
// History
//   2026-08-11  A. Sigdel  Created with #650.
//
// On the JVM, in AndroidAskingTest's shape and for its reason: the microphone
// needs a phone, and the reading of what it hands back does not.

package com.getlora.wattrouter.app

import android.speech.SpeechRecognizer
import com.getlora.wattrouter.Capability
import com.getlora.wattrouter.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidListeningTest {

    @Test
    fun nothingUsableIsSilenceRatherThanEmptyWords() {
        // Four shapes, one meaning. RESULTS_RECOGNITION is documented as
        // present and often is not, and Words("") would be refused downstream.
        val nothing = heardFrom(null)

        assertTrue(nothing.toString(), nothing is Heard.Silence)
        assertEquals(nothing, heardFrom(emptyList()))
        assertEquals(nothing, heardFrom(listOf(null)))
        assertEquals(nothing, heardFrom(listOf("", "   ")))
    }

    @Test
    fun theFirstUsableCandidateIsTheOneToSend() {
        // Ordered by confidence, so offering the rest asks somebody to
        // proofread, which is the keyboard they were avoiding by speaking.
        assertEquals(
            Heard.Words("turn the kitchen light off"),
            heardFrom(listOf("turn the kitchen light off", "turn the kitchen light of")),
        )
        assertEquals(Heard.Words("send it"), heardFrom(listOf("  send it  ")))
        assertEquals(Heard.Words("send it"), heardFrom(listOf(" ", "send it")))
    }

    @Test
    fun beingUnheardIsNotAFailure() {
        // Both codes say it listened and heard nothing, as an empty result
        // does, so all three answer alike. Telling somebody who mumbled that
        // something broke sends them looking for a fault that is not there.
        assertEquals(heardFrom(null), troubleFrom(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(heardFrom(null), troubleFrom(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun aMicrophoneTakenAwayNamesTheRowThatGivesItBack() {
        // The window between Permission obtaining it and the microphone
        // opening is small and real, and that row is what reopens it.
        val said = troubleFrom(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).why

        assertTrue(said, said.contains(Capability.MICROPHONE.settings))
    }

    @Test
    fun everyCodeSaysSomething() {
        // Including ones this build was never compiled against: the constants
        // span four API levels, and a blank sentence is a button that did
        // nothing. A network code is answered by the last arm rather than by
        // advice about a connection nothing here should have needed.
        (0..30).forEach { assertTrue("code $it says nothing", troubleFrom(it).why.isNotBlank()) }
        assertNotEquals(
            troubleFrom(SpeechRecognizer.ERROR_NETWORK).why,
            troubleFrom(SpeechRecognizer.ERROR_NO_MATCH).why,
        )
    }
}
