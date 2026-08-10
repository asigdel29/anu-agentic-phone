// AndroidPhone.kt — the screen tools, joined to the service that can reach one.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// It holds no service. DrivingService.connected is read on every call, because
// the person can switch the service off from Settings mid-turn and a held
// reference would go on calling into one the system has torn down. Answering
// null is what the tools already word: read_screen's sentence names the switch
// and the restricted-settings trap behind it.
//
// The work moves to Dispatchers.Default. Reading a screen walks a tree of a few
// hundred nodes and hashes it, which is not main-thread work, and the seam says
// moving it is this side's job rather than the caller's.

package com.getlora.wattrouter.app

import android.content.Context
import com.getlora.wattrouter.Done
import com.getlora.wattrouter.Generation
import com.getlora.wattrouter.Handle
import com.getlora.wattrouter.Launchable
import com.getlora.wattrouter.Onward
import com.getlora.wattrouter.Phone
import com.getlora.wattrouter.Reading
import com.getlora.wattrouter.Way
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The screen of the phone this is running on. */
class AndroidPhone(private val context: Context) : Phone {

    override suspend fun barredNow(): String? = withContext(Dispatchers.Default) {
        DrivingService.connected?.barredNow()?.why
    }

    // The whole of the distinction #517 turned on: `connected` is null when
    // nobody switched the service on, and non-null with a null reading when
    // there is simply no focused window yet. One object away, and the answer to
    // a person is opposite in each case.
    override suspend fun attached(): Boolean = withContext(Dispatchers.Default) {
        DrivingService.connected != null
    }

    override suspend fun read(): Reading? = withContext(Dispatchers.Default) {
        DrivingService.connected?.read()
    }

    override suspend fun tap(at: Handle, from: Generation): Done? =
        withContext(Dispatchers.Default) {
            DrivingService.connected?.tap(at, from)
        }

    override suspend fun type(at: Handle, from: Generation, text: String): Done? =
        withContext(Dispatchers.Default) {
            DrivingService.connected?.type(at, from, text)
        }

    override suspend fun navigate(way: Way): Done? = withContext(Dispatchers.Default) {
        DrivingService.connected?.navigate(way)
    }

    override suspend fun scroll(at: Handle, from: Generation, onward: Onward): Done? =
        withContext(Dispatchers.Default) {
            DrivingService.connected?.scroll(at, from, onward)
        }

    // Not through DrivingService: reading the package manager and starting an
    // activity need a Context and nothing else, so they work whether or not
    // somebody has switched the accessibility service on. Only the screen
    // afterwards needs it, and that answers null the way every read does.
    override suspend fun apps(): List<Launchable> = withContext(Dispatchers.Default) {
        installed(context)
    }

    override suspend fun open(packageName: String): Done? = withContext(Dispatchers.Default) {
        if (!start(context, packageName)) {
            Done.Refused("that app is installed and has nothing to open")
        } else {
            // Read after a moment. Answering immediately catches the screen the
            // app was launched from, which reads as the launch having done
            // nothing at all.
            delay(SETTLING)
            Done.Did(DrivingService.connected?.read())
        }
    }

    private companion object {
        /** Long enough for a launch to have started, short enough not to be a wait. */
        const val SETTLING = 400L
    }
}
