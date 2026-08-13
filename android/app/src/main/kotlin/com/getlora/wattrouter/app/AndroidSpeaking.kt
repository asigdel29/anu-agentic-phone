// AndroidSpeaking.kt: the phone's own voice, and no other.
//
// History
//   2026-08-13  A. Sigdel  Created with #709.
//
// AndroidListening's shape one direction along, including the parts that look
// like ceremony and are not: built and torn down per utterance so nothing holds
// the engine between turns, and cancellation stops the speaking rather than
// letting it finish into a room the person has left.
//
// TextToSpeech synthesises on the phone, so this adds nothing to SECURITY.md's
// list of what leaves the device. That is stated there as well as here, because
// it is the same claim the microphone makes and the two belong together.
//
// One engine per utterance rather than one held for the process, which is the
// decision in this file. A held engine is a service binding kept alive to say
// something every few minutes, and shutdown() is the only way to release the
// audio focus it takes; the cost is the engine's start-up on each answer, which
// is tens of milliseconds against a sentence that takes seconds to read.

package com.getlora.wattrouter.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.getlora.wattrouter.Speaking
import com.getlora.wattrouter.Spoken
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** No voice on this phone at all. */
internal const val NO_VOICE =
    "this phone has no speech installed, so there is nothing to read an answer with. " +
        "Android puts it under Settings > System > Languages and input > Text-to-speech."

/** A voice that is there and would not start. */
internal const val NO_SOUND =
    "the phone would not read that out. Nothing is wrong with the answer; it is on screen."

/** What one utterance is called, since the platform keys its callbacks on a string. */
private const val ONCE = "answer"

/** This phone's own voice. */
class AndroidSpeaking(private val context: Context) : Speaking {

    override suspend fun say(text: String): Spoken = withContext(Dispatchers.Main) { once(text) }

    /**
     * One engine, one sentence, then shut down.
     *
     * The two callbacks are not symmetric and the platform is why: `onInit`
     * answers whether an engine exists at all, and the progress listener answers
     * whether this utterance reached the speaker. A phone with no voice fails
     * the first; a phone whose engine declined fails the second.
     */
    private suspend fun once(text: String): Spoken = suspendCancellableCoroutine { waiting ->
        var engine: TextToSpeech? = null

        fun finish(spoken: Spoken) {
            engine?.shutdown()
            engine = null
            if (waiting.isActive) waiting.resume(spoken)
        }

        // Cancelling the turn stops the speaking. Posted rather than called, as
        // AndroidListening does: cancellation runs on whichever thread cancelled
        // and this needs main.
        waiting.invokeOnCancellation {
            context.mainExecutor.execute {
                engine?.stop()
                engine?.shutdown()
                engine = null
            }
        }

        engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                finish(Spoken.Silence(NO_VOICE))
                return@TextToSpeech
            }

            val speaking = engine ?: return@TextToSpeech
            speaking.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(utteranceId: String?) = finish(Spoken.Said)

                @Deprecated("The platform's own signature, and the one it calls.")
                override fun onError(utteranceId: String?) = finish(Spoken.Silence(NO_SOUND))

                override fun onError(utteranceId: String?, errorCode: Int) =
                    finish(Spoken.Silence(NO_SOUND))

                // Progress, and a half-read sentence is not an answer to give.
                override fun onStart(utteranceId: String?) = Unit
            })

            // QUEUE_FLUSH rather than QUEUE_ADD: a second answer replaces the
            // first rather than reading both, because two turns' answers read
            // back to back is a phone talking over itself about work somebody
            // has already seen.
            if (speaking.speak(text, TextToSpeech.QUEUE_FLUSH, null, ONCE) != TextToSpeech.SUCCESS) {
                finish(Spoken.Silence(NO_SOUND))
            }
        }
    }
}
