// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.libregeek.fernix.data.scan.ScanScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScanScopeTest {

    private lateinit var settings: Settings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
    }

    @Test
    fun unset_isNull() = assertNull(settings.scanScope)

    @Test
    fun folders_roundTrip() {
        val scope = ScanScope(setOf("external_primary/Music", "1234-5678"))
        settings.scanScope = scope
        assertEquals(scope, settings.scanScope)
    }

    @Test
    fun noFolders_isAnEmptyScope_notUnset() {
        settings.scanScope = ScanScope(emptySet())
        assertEquals(ScanScope(emptySet()), settings.scanScope)
    }

    @Test
    fun anInstallThatHadAllStorage_hasNoScopeUntilFoldersAreChosen() {
        prefs().edit().putString("scan_scope", "all").commit()
        assertNull(settings.scanScope)
        settings.scanScope = ScanScope(setOf("external_primary/Music"))
        assertEquals(ScanScope(setOf("external_primary/Music")), settings.scanScope)
        assertEquals(false, prefs().contains("scan_scope"))
    }

    @Test
    fun anInstallThatHadSelectedFolders_keepsThem() {
        prefs().edit().putString("scan_scope", "selected").putStringSet("scan_folders", setOf("external_primary/Music")).commit()
        assertEquals(ScanScope(setOf("external_primary/Music")), settings.scanScope)
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    @Test
    fun scanCompleted_defaultsFalse_andRoundTrips() {
        assertEquals(false, settings.scanCompleted)
        settings.scanCompleted = true
        assertEquals(true, settings.scanCompleted)
    }
}
