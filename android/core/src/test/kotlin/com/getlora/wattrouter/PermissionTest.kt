// PermissionTest.kt — the places Android differs from iOS.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  The re-ask case counts dialogs as well as reads. It
//                          had asserted only that a second attempt looked, and
//                          looking is not what its name claims.
//
// On the JVM against a scripted Asking. Every case is one #229 named, or one
// that copying Permission.swift would have got wrong.

package com.getlora.wattrouter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Scripted(
    private var state: PermissionState,
    private val afterAsking: PermissionState = state,
    private val gate: CompletableDeferred<Unit>? = null,
) : Asking {
    var reads = 0
    var dialogs = 0

    override suspend fun state(of: Capability) = state.also { reads++ }

    override suspend fun request(capability: Capability): PermissionState {
        dialogs++
        gate?.await()
        state = afterAsking
        return afterAsking
    }
}

class PermissionTest {
    @Test
    fun somethingGrantedIsNotAskedAboutAndSomethingUnaskedIs() = runTest {
        val granted = Scripted(PermissionState.GRANTED)
        Permission(granted).obtain(Capability.CALENDAR)
        assertEquals(0, granted.dialogs)

        val unasked = Scripted(PermissionState.UNASKED, afterAsking = PermissionState.GRANTED)
        Permission(unasked).obtain(Capability.CONTACTS)
        assertEquals(1, unasked.dialogs)
    }

    @Test
    fun stateIsReadEveryTimeRatherThanRemembered() = runTest {
        // Somebody who relents in Settings tells the app nothing, so looking
        // every time is the only way to notice.
        val asking = Scripted(PermissionState.GRANTED)
        val permission = Permission(asking)

        permission.obtain(Capability.LOCATION)
        permission.obtain(Capability.LOCATION)

        assertEquals(2, asking.reads)
    }

    @Test
    fun aRefusalCanBeAskedAboutAgain() = runTest {
        // iOS spends its one prompt and never asks again. Here a second
        // attempt is legitimate, and sometimes right.
        val asking = Scripted(PermissionState.UNASKED, afterAsking = PermissionState.REFUSED)
        val permission = Permission(asking)

        runCatching { permission.obtain(Capability.CALENDAR) }
        runCatching { permission.obtain(Capability.CALENDAR) }

        assertEquals("the second attempt should have looked again", 2, asking.reads)
        // And asked. Looking alone leaves Refused's "ask me again and I will
        // request it" a sentence nothing behind it performs.
        assertEquals("a refusal is not a spent prompt", 2, asking.dialogs)
    }

    @Test
    fun aPermanentDenialIsNotAskedAboutAgain() = runTest {
        // The line between the two states. A refusal is re-asked because the
        // system would still show a dialog; a permanent denial is not, because
        // it would not, and asking anyway is a call that reports nothing.
        val asking = Scripted(PermissionState.PERMANENTLY_DENIED)
        val permission = Permission(asking)

        runCatching { permission.obtain(Capability.LOCATION) }
        runCatching { permission.obtain(Capability.LOCATION) }

        assertEquals(2, asking.reads)
        assertEquals(0, asking.dialogs)
    }

    @Test
    fun aPermanentDenialSaysWhereToTurnItOn() = runTest {
        // No iOS equivalent: Android stops showing the dialog and says
        // nothing, so a caller retries forever against silence.
        val asking = Scripted(PermissionState.PERMANENTLY_DENIED)

        val thrown = runCatching { Permission(asking).obtain(Capability.CONTACTS) }
            .exceptionOrNull() as PermissionError

        assertTrue("$thrown", thrown is PermissionError.PermanentlyDenied)
        assertTrue(thrown.message!!, thrown.message!!.contains("Settings > Apps"))
        assertEquals("asking again does nothing", 0, asking.dialogs)
    }

    @Test
    fun somethingUnavailableIsNotSomethingToAskFor() = runTest {
        // A restriction rather than a choice, so the prose says carry on.
        val thrown = runCatching {
            Permission(Scripted(PermissionState.UNAVAILABLE)).obtain(Capability.LOCATION)
        }.exceptionOrNull() as PermissionError

        assertTrue("$thrown", thrown is PermissionError.Unavailable)
        assertTrue(thrown.message!!, thrown.message!!.contains("Carry on without it"))
    }

    @Test
    fun aDismissedDialogIsNotAHang() = runTest {
        // Dismissed leaves the state UNASKED, which to a caller is the same
        // as no. Reporting it as unasked waits for an answer nobody gave.
        val asking = Scripted(PermissionState.UNASKED, afterAsking = PermissionState.UNASKED)

        val thrown = runCatching { Permission(asking).obtain(Capability.CALENDAR) }
            .exceptionOrNull()

        assertTrue("$thrown", thrown is PermissionError.Refused)
    }

    @Test
    fun twoToolsInOneRoundProduceOneDialog() = runTest {
        // Worth keeping from iOS: two dialogs for one capability is the app
        // asking twice in one breath.
        val gate = CompletableDeferred<Unit>()
        val asking = Scripted(PermissionState.UNASKED, PermissionState.GRANTED, gate)
        val permission = Permission(asking)

        val first = async { permission.obtain(Capability.CALENDAR) }
        val second = async { permission.obtain(Capability.CALENDAR) }
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, asking.dialogs)
    }
}
