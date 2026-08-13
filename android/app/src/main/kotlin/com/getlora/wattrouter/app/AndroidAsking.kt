// AndroidAsking.kt: the permission dialog, and the ambiguity in its silence.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-11  A. Sigdel  The microphone, which is a fourth thing a phone can
//                          be without, #650.
//
// Permission and the Asking seam live in core/ because everything about them can
// be checked without a phone. This is the half that cannot, and it is longer
// than the three system calls it makes for one reason: Android says whether a
// permission is held, and whether asking would show a dialog, and never whether
// it was ever asked. That last is the difference between somebody who has not
// been asked yet and somebody the system has stopped asking on behalf of;
// shouldShowRequestPermissionRationale answers false in both.
//
// So one thing is kept here, and deliberately not the answer: that the dialog
// was shown. The answer is re-read every time, which is #229's point and why
// Permission holds no cache of its own.

package com.getlora.wattrouter.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.getlora.wattrouter.Asking
import com.getlora.wattrouter.Capability
import com.getlora.wattrouter.PermissionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the system's answers mean taken together.
 *
 * Separate from the calls that produce them, and pure, because this is where all
 * of the subtlety is and none of it needs a device to check.
 *
 * @param present whether the phone has the thing at all.
 * @param granted what `checkSelfPermission` answered.
 * @param everAsked whether this app has ever put the dialog on screen.
 * @param rationale what `shouldShowRequestPermissionRationale` answered. True
 *   only while the system would still show a dialog if asked.
 */
internal fun stateFrom(
    present: Boolean,
    granted: Boolean,
    everAsked: Boolean,
    rationale: Boolean,
): PermissionState = when {
    // Before granted, because a permission held on a phone that has nothing to
    // use it on is still nothing to offer. The prose for it says carry on.
    !present -> PermissionState.UNAVAILABLE
    granted -> PermissionState.GRANTED
    !everAsked -> PermissionState.UNASKED
    rationale -> PermissionState.REFUSED
    else -> PermissionState.PERMANENTLY_DENIED
}

/** The Android permission a capability is spelled as. */
internal fun permissionFor(capability: Capability): String = when (capability) {
    Capability.CALENDAR -> Manifest.permission.READ_CALENDAR
    Capability.CONTACTS -> Manifest.permission.READ_CONTACTS
    // Coarse alone. Asking for fine location on API 31 and above obliges an app
    // to ask for coarse alongside it and puts a precise/approximate choice in
    // the dialog, and none of that buys anything here: an agent answering where
    // somebody is does not need to know which side of the street.
    Capability.LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
    // Holding a stream, and nothing about where it goes. AndroidListening is
    // what answers that question, by matching on the phone.
    Capability.MICROPHONE -> Manifest.permission.RECORD_AUDIO
}

/**
 * The dialog, over the real APIs.
 *
 * Built in `onCreate` and handed on. `registerForActivityResult` has to run
 * before the Activity is STARTED and a composition is not before it, so building
 * this inside `setContent` throws about a lifecycle state rather than about the
 * line that caused it.
 */
class AndroidAsking(private val activity: ComponentActivity) : Asking {

    // That the dialog was shown, never what it answered. Loaded when this is
    // built, which is onCreate and off any turn's path; a read after that is
    // memory. checkSelfPermission below is Context's rather than ContextCompat's
    // for the reason MainActivity gives: the androidx.core release carrying the
    // helper wants an AGP this build does not have (#357).
    private val asked =
        activity.getSharedPreferences("permissions-asked", Context.MODE_PRIVATE)

    // One dialog at a time. Permission coalesces per capability so it never asks
    // twice for one, but two capabilities in a round are still two, and
    // launching a second contract over the first loses the first answer.
    private val oneAtATime = Mutex()
    private var answering: CompletableDeferred<Unit>? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The result is dropped and the state read again instead. A dialog
            // dismissed rather than answered arrives here as false, which is
            // indistinguishable from a refusal until rationale is consulted,
            // and consulting it is all state() does.
            answering?.complete(Unit)
        }

    override suspend fun state(of: Capability): PermissionState {
        val permission = permissionFor(of)
        val granted =
            activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        // Cleared on grant rather than merely ignored. Revoking in Settings
        // resets Android's own counter, as does the automatic revocation of an
        // unused app's permissions, and a record left set would read the fresh
        // askable permission that follows as permanently denied, sending
        // somebody to a Settings row that already says what they want.
        if (granted) asked.edit().remove(permission).apply()

        return stateFrom(
            present = isPresent(of),
            granted = granted,
            everAsked = asked.getBoolean(permission, false),
            rationale = activity.shouldShowRequestPermissionRationale(permission),
        )
    }

    override suspend fun request(capability: Capability): PermissionState {
        val permission = permissionFor(capability)
        oneAtATime.withLock {
            val answered = CompletableDeferred<Unit>()
            answering = answered
            // Recorded before the dialog and not after it. The process can be
            // killed while one is up, and an ask nobody wrote down reads
            // afterwards as an ask that never happened.
            asked.edit().putBoolean(permission, true).apply()
            try {
                // The launch has to be on the main thread and a tool could reach
                // here from any. Waiting is unbounded on purpose: the wait is a
                // person, and the scope that started the turn cancels it.
                withContext(Dispatchers.Main) { launcher.launch(permission) }
                answered.await()
            } finally {
                answering = null
            }
        }
        return state(capability)
    }

    /**
     * Whether the phone has the thing behind the permission.
     *
     * Location and the microphone are the two of the four a phone can genuinely
     * lack; the calendar and contacts providers are platform, and one holding no
     * event is empty rather than absent. Resolving their authorities to guess
     * would be worse than not guessing: package visibility filters that lookup
     * from targetSdk 30 upwards, so its "no" means two things and one of them is
     * wrong.
     */
    private fun isPresent(capability: Capability): Boolean = when (capability) {
        Capability.LOCATION ->
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
        Capability.MICROPHONE ->
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        Capability.CALENDAR, Capability.CONTACTS -> true
    }
}
