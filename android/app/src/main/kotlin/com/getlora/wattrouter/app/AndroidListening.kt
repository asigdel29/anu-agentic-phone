// AndroidListening.kt: the platform recognizer, and keeping the audio here.
//
// History
//   2026-08-11  A. Sigdel  Created with #650.
//
// Contents
//   heardFrom         A results list back into words, or into silence.
//   troubleFrom       One of the error codes back into a sentence.
//   AndroidListening  The seam over SpeechRecognizer.
//
// createOnDeviceSpeechRecognizer rather than createSpeechRecognizer, and that
// is the whole of this file's security posture. The ordinary factory binds
// whichever service holds RecognitionService, which on most phones recognises
// over somebody's network, and an app that cannot say where the audio went owes
// the paragraph #601 calls uncomfortable. The on-device one matches here or
// errors, so there is no paragraph. Its price is API 31 against a floor of 29.

package com.getlora.wattrouter.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.getlora.wattrouter.Capability
import com.getlora.wattrouter.Heard
import com.getlora.wattrouter.Listening
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// The two ways there is nothing to send: the ordinary miss, which is nobody's
// fault, and a phone that cannot listen at all without sending audio away.
private const val NOTHING = "I did not catch that. Say it again, or type it."
private const val NO_RECOGNITION = "This phone cannot recognise speech on its " +
    "own, and nothing here sends what you say elsewhere. Type it instead."

/**
 * The best of what the recognizer offered, or why there is nothing to send.
 *
 * The first candidate rather than a choice between them: they arrive ordered by
 * confidence, and offering the rest asks somebody to proofread, which is the
 * keyboard they were avoiding. Absent, empty and blank are all ordinary, and
 * RESULTS_RECOGNITION in particular is documented as present and often is not.
 */
internal fun heardFrom(candidates: List<String?>?): Heard {
    val best = candidates?.firstOrNull { !it.isNullOrBlank() }?.trim()
    return if (best == null) Heard.Silence(NOTHING) else Heard.Words(best)
}

/**
 * One of the recognizer's error codes, in words for the person who spoke.
 *
 * Every code, including ones this build was never compiled against: they span
 * four API levels, so the last arm is a sentence rather than a blank. The
 * network codes land there deliberately. One that could not reach a server is
 * not the on-device recognizer it said it was, and what is true to say is that
 * recognition did not happen here, not to point at somebody's connection.
 */
internal fun troubleFrom(code: Int): Heard.Silence = Heard.Silence(
    when (code) {
        // Not a failure: it listened and heard nothing, as an empty result says.
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOTHING
        // Revoked between Permission obtaining it and the microphone opening, a
        // small real window. That row is what changes it back, so it is named.
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "I am not allowed to use ${Capability.MICROPHONE.subject}. It can be " +
                "turned on in ${Capability.MICROPHONE.settings}."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Something else on the phone has the microphone. Try again once it lets go."
        SpeechRecognizer.ERROR_AUDIO ->
            "The microphone could not be read. Check nothing is covering it."
        else -> NO_RECOGNITION
    },
)

/** This phone's own speech recognition, and no other. */
class AndroidListening(private val context: Context) : Listening {

    override suspend fun listen(): Heard = withContext(Dispatchers.Main) {
        // Built, started and destroyed on the main thread, and the platform
        // says so by throwing, so the move is made rather than asked for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Heard.Silence(NO_RECOGNITION)
        } else {
            once()
        }
    }

    /** One pass at the microphone, behind the caller's version check, because
     *  createOnDeviceSpeechRecognizer arrived in API 31 and below it is the
     *  networked factory this file declines rather than an older one. */
    private suspend fun once(): Heard = suspendCancellableCoroutine { waiting ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

        // Destroyed on the way out, whichever way: one left alive holds the
        // microphone and the indicator the system shows beside it.
        fun finish(heard: Heard) {
            recognizer.destroy()
            if (waiting.isActive) waiting.resume(heard)
        }

        // Cancelling the turn closes the microphone. Posted rather than called:
        // cancellation runs on whichever thread cancelled, and this needs main.
        waiting.invokeOnCancellation { context.mainExecutor.execute { recognizer.destroy() } }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) = finish(
                heardFrom(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)),
            )

            override fun onError(error: Int) = finish(troubleFrom(error))

            // The rest is progress, and a half-said sentence is no instruction.
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        recognizer.startListening(request())
    }

    /** EXTRA_PREFER_OFFLINE beside the on-device factory rather than instead of
     *  it: the extra is a preference whatever was bound is free to ignore. */
    private fun request(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
}
