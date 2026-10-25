// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import io.github.libregeek.fernix.data.scan.ScanScope
import io.github.libregeek.fernix.data.scan.ScopeImpact
import org.junit.Assert.assertEquals
import org.junit.Test

/** The scope confirmation is kept as strings across rotation and process death. */
class PendingScopeTest {

    @Test
    fun selectedFolders_surviveTheRoundTrip_withTheirCounts() {
        val scope = ScanScope(setOf("external_primary/Music/Rock", "0000-0000/Music"))
        val raw = encodePending(scope, ScopeImpact(tracks = 12, playlistEntries = 3))
        assertEquals(scope to ScopeImpact(12, 3), decodePending(raw))
    }

    @Test
    fun anEmptySelection_survivesToo() {
        val none = ScanScope(emptySet())
        assertEquals(none to ScopeImpact(5, 0), decodePending(encodePending(none, ScopeImpact(5, 0))))
    }

    @Test
    fun aFolderNameWithSpacesAndUnicode_isKeptWhole() {
        val scope = ScanScope(setOf("external_primary/Music/Café del Mar 日本"))
        assertEquals(scope, decodePending(encodePending(scope, ScopeImpact(1, 1))).first)
    }
}
