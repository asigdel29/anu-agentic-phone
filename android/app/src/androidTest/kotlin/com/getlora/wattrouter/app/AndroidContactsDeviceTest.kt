// AndroidContactsDeviceTest.kt — the provider answers, and the escape holds.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// The claims the JVM cannot make. A projection naming a column that is not there
// throws at the first row rather than at build; and `LIKE ? ESCAPE '\'` is
// SQLite's syntax, so whether the provider accepts the clause at all is only
// answerable by a provider. A rejected selection throws, and a wrong one would
// quietly match everybody.

package com.getlora.wattrouter.app

import android.Manifest
import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidContactsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun allow() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CONTACTS,
        )
    }

    @Test
    fun theProviderAnswersAndTheProjectionFits() = runBlocking {
        // Asserted rather than assumed: an image with no contacts provider
        // would pass this by answering nothing, which is the shape a working
        // one gives for a name nobody has.
        val provider = context.contentResolver
            .acquireContentProviderClient(ContactsContract.AUTHORITY)
        assertNotNull("no contacts provider on this image", provider)
        provider?.close()

        val found = AndroidContacts(context).find("zzz nobody by this name", 5)

        assertTrue(found.toString(), found.isEmpty())
    }

    @Test
    fun aWildcardIsNotEverybody() {
        // The escape clause is SQLite's, and a provider that rejected it would
        // throw here rather than on somebody's phone.
        val found = runBlocking { AndroidContacts(context).find("%", 5) }

        assertTrue("a bare wildcard matched $found", found.isEmpty())
    }
}
