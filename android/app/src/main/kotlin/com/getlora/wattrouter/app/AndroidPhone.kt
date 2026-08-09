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

import com.getlora.wattrouter.Done
import com.getlora.wattrouter.Generation
import com.getlora.wattrouter.Handle
import com.getlora.wattrouter.Phone
import com.getlora.wattrouter.Reading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The screen of the phone this is running on. */
class AndroidPhone : Phone {

    override suspend fun read(): Reading? = withContext(Dispatchers.Default) {
        DrivingService.connected?.read()
    }

    override suspend fun tap(at: Handle, from: Generation): Done? =
        withContext(Dispatchers.Default) {
            DrivingService.connected?.tap(at, from)
        }
}
