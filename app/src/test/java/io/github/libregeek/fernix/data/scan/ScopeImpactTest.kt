// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import io.github.libregeek.fernix.data.db.FolderCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeImpactTest {

    private val primary = "external_primary"
    private val sd = "1234-5678"
    private val tracks = listOf(
        FolderCount("$primary/Music/Rock", 10),
        FolderCount("$primary/Music/Jazz", 4),
        FolderCount("$sd/Music", 6),
    )
    private val entries = listOf(
        FolderCount("$primary/Music/Rock", 3),
        FolderCount("$sd/Music", 2),
    )

    private fun impact(scope: ScanScope, mounted: Set<String> = setOf(primary, sd)) =
        ScopeImpact.of(tracks, entries, scope, mounted)

    @Test
    fun allStorage_removesNothing() {
        assertTrue(impact(ScanScope.All).isEmpty)
    }

    @Test
    fun aNarrowerScope_countsTheTracksAndPlaylistEntriesOutsideIt() {
        // Only Jazz is kept: Rock (10 tracks, 3 entries) and the SD card (6 tracks, 2 entries) go.
        assertEquals(ScopeImpact(16, 5), impact(ScanScope.Selected(setOf("$primary/Music/Jazz"))))
    }

    @Test
    fun aFolderCoversItsSubFolders() {
        assertEquals(ScopeImpact(6, 2), impact(ScanScope.Selected(setOf("$primary/Music"))))
    }

    @Test
    fun anUnmountedVolume_isNotCounted_becauseTheScanKeepsItsTracks() {
        assertEquals(ScopeImpact(10, 3), impact(ScanScope.Selected(setOf("$primary/Music/Jazz")), mounted = setOf(primary)))
    }

    @Test
    fun anEmptySelection_removesEverythingOnMountedVolumes() {
        assertEquals(ScopeImpact(20, 5), impact(ScanScope.Selected(emptySet())))
    }

    @Test
    fun aWiderScopeThanBefore_removesNothing() {
        assertTrue(impact(ScanScope.Selected(setOf("$primary/Music/Rock", "$primary/Music/Jazz", "$sd/Music"))).isEmpty)
    }
}
