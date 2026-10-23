// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanScopeTest {

    @Test
    fun folderPath_dropsTrailingSlash() {
        assertEquals("external_primary/Music/Albums", ScanScope.folderPath("external_primary", "Music/Albums/"))
    }

    @Test
    fun folderPath_volumeRoot() {
        assertEquals("1234-5678", ScanScope.folderPath("1234-5678", ""))
        assertEquals("1234-5678", ScanScope.folderPath("1234-5678", "/"))
    }

    @Test
    fun volumeOf_isFirstSegment() {
        assertEquals("external_primary", ScanScope.volumeOf("external_primary/Music/A"))
        assertEquals("1234-5678", ScanScope.volumeOf("1234-5678"))
    }

    @Test
    fun all_containsEverything() {
        assertTrue(ScanScope.All.contains("external_primary/Anything"))
    }

    @Test
    fun selected_includesSubFolders() {
        val scope = ScanScope.Selected(setOf("external_primary/Music"))
        assertTrue(scope.contains("external_primary/Music"))
        assertTrue(scope.contains("external_primary/Music/Rock/Live"))
    }

    @Test
    fun selected_doesNotMatchSiblingWithSamePrefix() {
        val scope = ScanScope.Selected(setOf("external_primary/Music"))
        assertFalse(scope.contains("external_primary/Music2"))
        assertFalse(scope.contains("external_primary/Podcasts"))
        assertFalse(scope.contains("1234-5678/Music"))
    }

    @Test
    fun selectedVolumeRoot_includesWholeVolume() {
        val scope = ScanScope.Selected(setOf("1234-5678"))
        assertTrue(scope.contains("1234-5678"))
        assertTrue(scope.contains("1234-5678/Music/A"))
        assertFalse(scope.contains("external_primary/Music"))
    }

    @Test
    fun emptySelection_containsNothing() {
        assertFalse(ScanScope.Selected(emptySet()).contains("external_primary/Music"))
    }
}
