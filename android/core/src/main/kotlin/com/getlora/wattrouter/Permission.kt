// Permission.kt — asking for a capability, and what the answer means.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  A refusal is asked about again, which the prose had
//                          been promising and the code had not been doing.
//
// Contents
//   Capability       Something a tool needs before it can work.
//   PermissionState  How things stand with one.
//   Asking           The system dialog, as a seam.
//   PermissionError  Why a tool could not do its job.
//   Permission       One place that asks, so two tools produce one dialog.
//
// #229 named this as the one place the two phones should not agree, and
// Permission.swift is the thing not to copy: it keeps `asked: Set<Capability>`
// and treats a capability as spent once asked, because iOS grants one prompt
// for the life of an install.
//
// That is wrong twice over here. Somebody who relents in Settings should be
// noticed, so nothing is cached. And somebody who refuses twice is permanently
// denied — the dialog stops appearing, silently — which has no iOS equivalent
// and needs a name, because the only useful answer is the Settings row.
//
// Worth keeping: coalescing, so two tools in one round produce one dialog; and
// asking only after a tool has validated its arguments, since a malformed call
// must not spend a prompt.

package com.getlora.wattrouter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Something a tool needs before it can work.
 *
 * @property subject how the model refers to it.
 * @property settings where a person turns it on, named exactly — "Settings" on
 *   its own is advice nobody can act on.
 */
enum class Capability(val subject: String, val settings: String) {
    CALENDAR("the calendar", "Settings > Apps > WattRouter > Permissions > Calendar"),
    CONTACTS("contacts", "Settings > Apps > WattRouter > Permissions > Contacts"),
    LOCATION("location", "Settings > Apps > WattRouter > Permissions > Location"),
}

/** How things stand with a capability. */
enum class PermissionState {
    GRANTED,

    /** Refused, and asking again would still show a dialog. */
    REFUSED,

    /**
     * Refused enough times that the system no longer shows the dialog. No iOS
     * equivalent: asking again does nothing and reports nothing, so a caller
     * that cannot tell it from [REFUSED] retries forever against silence.
     */
    PERMANENTLY_DENIED,

    /** Not on this device at all: no calendar app, no location hardware. */
    UNAVAILABLE,

    /** Nobody has been asked yet. */
    UNASKED,
}

/**
 * The system dialog, as a seam: showing one needs an Activity and the turn loop
 * is not one. It also lets everything below be tested without a device.
 */
interface Asking {
    /**
     * How things stand, read now rather than remembered.
     *
     * # Rely
     * Cheap and non-blocking; called on every attempt.
     */
    suspend fun state(of: Capability): PermissionState

    /**
     * Show the dialog and wait. Answers the state afterwards, whatever it is.
     *
     * # Rely
     * Needs an Activity in the foreground, and suspends for as long as somebody
     * takes to answer. Never called concurrently for one capability.
     */
    suspend fun request(capability: Capability): PermissionState
}

/** Why a tool could not do its job. */
sealed class PermissionError(message: String) : Exception(message) {
    /** No, and possibly yes if asked again. */
    class Refused(val capability: Capability) :
        PermissionError(
            "I do not have access to ${capability.subject}. You can allow it in " +
                "${capability.settings}, or ask me again and I will request it.",
        )

    /**
     * The system will not ask again. The message names the Settings row: that
     * is the only thing that changes it.
     */
    class PermanentlyDenied(val capability: Capability) :
        PermissionError(
            "Access to ${capability.subject} is turned off and Android will not ask " +
                "again. It can be turned on in ${capability.settings}.",
        )

    /** Not a choice, so there is nothing to ask for. */
    class Unavailable(val capability: Capability) :
        PermissionError(
            "This phone has no ${capability.subject}, so there is nothing to allow. " +
                "Carry on without it.",
        )
}

/** One place that asks, so two tools in a round produce one dialog. */
class Permission(private val asking: Asking) {
    private val lock = Mutex()
    private val inFlight = mutableMapOf<Capability, CompletableDeferred<PermissionState>>()
    /**
     * Get [capability], asking if that is still possible.
     *
     * # Rely
     * Called from a tool after it has validated its arguments: a malformed call
     * must not spend a prompt.
     *
     * # Atomic
     * Coalesced on one lock, so two tools in a round wait on one dialog. The
     * lock is held while joining or starting, not while the dialog is up.
     *
     * @throws PermissionError if it cannot be had, with prose the model can act
     *   on — for a permanent denial, the Settings row.
     */
    suspend fun obtain(capability: Capability) {
        when (val state = resolve(capability)) {
            PermissionState.GRANTED -> return
            PermissionState.UNAVAILABLE -> throw PermissionError.Unavailable(capability)
            PermissionState.PERMANENTLY_DENIED ->
                throw PermissionError.PermanentlyDenied(capability)
            // A dialog that was dismissed rather than answered leaves UNASKED,
            // and to a caller that is the same as no: it did not happen.
            PermissionState.REFUSED, PermissionState.UNASKED ->
                throw PermissionError.Refused(capability)
        }
    }

    private suspend fun resolve(capability: Capability): PermissionState {
        val (waiting, mine) = lock.withLock {
            inFlight[capability]?.let { return@withLock it to false }
            CompletableDeferred<PermissionState>().also { inFlight[capability] = it } to true
        }
        if (!mine) return waiting.await()

        return try {
            // Read first, never from a cache: somebody who relents in Settings
            // tells the app nothing, so looking is the only way to notice.
            val known = asking.state(capability)
            // REFUSED as well as UNASKED. It is the state documented above as
            // the one where a dialog would still be shown, and on Android the
            // system says so directly; declining to show one would make
            // Refused's "ask me again and I will request it" a promise nothing
            // keeps, which is iOS's one-prompt rule arriving by the back door.
            //
            // Not nagging either: obtain is only reached because a tool ran,
            // and a tool only ran because somebody asked for something that
            // needs this.
            val answer = when (known) {
                PermissionState.UNASKED, PermissionState.REFUSED ->
                    asking.request(capability)
                else -> known
            }
            waiting.complete(answer)
            answer
        } catch (e: Throwable) {
            waiting.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { inFlight.remove(capability) }
        }
    }
}
