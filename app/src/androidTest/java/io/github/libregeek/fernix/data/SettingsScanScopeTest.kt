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
    fun all_roundTrips() {
        settings.scanScope = ScanScope.All
        assertEquals(ScanScope.All, settings.scanScope)
    }

    @Test
    fun selected_roundTrips() {
        val scope = ScanScope.Selected(setOf("external_primary/Music", "1234-5678"))
        settings.scanScope = scope
        assertEquals(scope, settings.scanScope)
    }

    @Test
    fun selectedWithNoFolders_staysSelected() {
        settings.scanScope = ScanScope.Selected(emptySet())
        assertEquals(ScanScope.Selected(emptySet()), settings.scanScope)
    }

    @Test
    fun switchingToAll_dropsFolders() {
        settings.scanScope = ScanScope.Selected(setOf("external_primary/Music"))
        settings.scanScope = ScanScope.All
        settings.scanScope = ScanScope.Selected(emptySet())
        assertEquals(ScanScope.Selected(emptySet()), settings.scanScope)
    }
}
