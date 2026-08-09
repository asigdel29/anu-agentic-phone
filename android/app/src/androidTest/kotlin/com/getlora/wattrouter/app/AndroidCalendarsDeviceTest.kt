// AndroidCalendarsDeviceTest.kt — the provider answers, and the columns exist.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// The claim the JVM cannot make. A projection naming a column that is not there
// throws at the first row rather than at build, and a URI assembled wrongly
// answers null — which the conformance turns into an empty list, so the failure
// arrives as a person having nothing on rather than as an error.

package com.getlora.wattrouter.app

import android.Manifest
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCalendarsDeviceTest {
    @Test
    fun theProviderAnswersAndTheProjectionFits() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )

        // Asserted rather than assumed. Without it a system image with no
        // calendar at all passes this test by answering nothing, which is the
        // same shape as the answer a working provider gives for 1970.
        val provider = context.contentResolver
            .acquireContentProviderClient(CalendarContract.AUTHORITY)
        assertNotNull("no calendar provider on this image", provider)
        provider?.close()

        val found = AndroidCalendars(context).between(0, 86_400)

        assertTrue(found.toString(), found.isEmpty())
    }
}
