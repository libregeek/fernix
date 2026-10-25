// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDiffTest {

    private val EVERYTHING = ScanScope(setOf("external_primary", "1234-5678"))

    private val primary = "external_primary"
    private val mounted = setOf(primary)

    private fun media(id: Long, folder: String = "$primary/Music", size: Long = 10, modified: Long = 1, name: String = "a.mp3") =
        MediaRow(id, size, modified, name, folder)

    private fun stored(id: Long, folder: String = "$primary/Music", size: Long = 10, modified: Long = 1, name: String = "a.mp3") =
        StoredRow(id, size, modified, name, folder)

    private fun walk(
        media: List<MediaRow>,
        stored: List<StoredRow>,
        scope: ScanScope = EVERYTHING,
        mountedVolumes: Set<String> = mounted,
    ): Pair<List<DiffEvent>, DiffCounts> {
        val events = mutableListOf<DiffEvent>()
        val counts = runBlocking { ScanDiff.walk(media.iterator(), stored.iterator(), scope, mountedVolumes) { events += it } }
        return events to counts
    }

    @Test
    fun differentDateAdded_alone_isNotAChange_butReportsTheDate() {
        val m = media(1).copy(dateAdded = 777)
        val (events, counts) = walk(listOf(m), listOf(stored(1)))
        assertEquals(listOf<DiffEvent>(DiffEvent.DateAdded(1, 777)), events)
        assertEquals(DiffCounts(0, 0, 0, 1), counts)
    }

    @Test
    fun sameDateAdded_reportsNothing() {
        val (events, _) = walk(listOf(media(1).copy(dateAdded = 5)), listOf(stored(1).copy(dateAdded = 5)))
        assertEquals(emptyList<DiffEvent>(), events)
    }

    @Test
    fun emptyBoth_noEvents() {
        val (events, counts) = walk(emptyList(), emptyList())
        assertEquals(emptyList<DiffEvent>(), events)
        assertEquals(DiffCounts(0, 0, 0, 0), counts)
    }

    @Test
    fun firstScan_everythingNew() {
        val (events, counts) = walk(listOf(media(1), media(2)), emptyList())
        assertEquals(listOf(DiffEvent.New(media(1)), DiffEvent.New(media(2))), events)
        assertEquals(DiffCounts(2, 0, 0, 0), counts)
    }

    @Test
    fun identicalRows_areUnchanged() {
        val (events, counts) = walk(listOf(media(1), media(2)), listOf(stored(1), stored(2)))
        assertEquals(emptyList<DiffEvent>(), events)
        assertEquals(DiffCounts(0, 0, 0, 2), counts)
    }

    @Test
    fun interleavedNewChangedVanished() {
        val (events, counts) = walk(
            media = listOf(media(1), media(3, size = 99), media(5), media(6)),
            stored = listOf(stored(1), stored(2), stored(3), stored(4), stored(6)),
        )
        assertEquals(
            listOf(
                DiffEvent.Vanished(2),
                DiffEvent.Changed(media(3, size = 99)),
                DiffEvent.Vanished(4),
                DiffEvent.New(media(5)),
            ),
            events,
        )
        assertEquals(DiffCounts(1, 1, 2, 2), counts)
    }

    @Test
    fun eachChangeDetectionField_marksChanged() {
        val variants = listOf(
            media(1, size = 11),
            media(1, modified = 2),
            media(1, name = "b.mp3"),
            media(1, folder = "$primary/Other"),
        )
        for (m in variants) {
            val (events, _) = walk(listOf(m), listOf(stored(1)))
            assertEquals(listOf<DiffEvent>(DiffEvent.Changed(m)), events)
        }
    }

    @Test
    fun trailingStoredRows_vanish() {
        val (events, _) = walk(listOf(media(1)), listOf(stored(1), stored(2), stored(3)))
        assertEquals(listOf(DiffEvent.Vanished(2), DiffEvent.Vanished(3)), events)
    }

    @Test
    fun unmountedVolume_keepsItsStoredTracks() {
        val (events, counts) = walk(
            media = listOf(media(1)),
            stored = listOf(stored(1), stored(2, folder = "1234-5678/Music")),
        )
        assertEquals(emptyList<DiffEvent>(), events)
        assertEquals(1, counts.unchanged)
    }

    @Test
    fun mountedSdCard_vanishedFileIsRemoved() {
        val (events, _) = walk(
            media = emptyList(),
            stored = listOf(stored(2, folder = "1234-5678/Music")),
            mountedVolumes = mounted + "1234-5678",
        )
        assertEquals(listOf<DiffEvent>(DiffEvent.Vanished(2)), events)
    }

    @Test
    fun outOfScopeStoredTrack_isRemoved_andOutOfScopeMediaIsNotNew() {
        val scope = ScanScope(setOf("$primary/Music/Jazz"))
        val (events, counts) = walk(
            media = listOf(media(1, folder = "$primary/Music/Jazz/Blue"), media(2, folder = "$primary/Music/Rock"), media(3, folder = "$primary/Music/Jazz")),
            stored = listOf(stored(1, folder = "$primary/Music/Jazz/Blue"), stored(2, folder = "$primary/Music/Rock")),
            scope = scope,
        )
        assertEquals(
            listOf(DiffEvent.Vanished(2), DiffEvent.New(media(3, folder = "$primary/Music/Jazz"))),
            events,
        )
        assertEquals(DiffCounts(1, 0, 1, 1), counts)
    }

    @Test
    fun emptySelection_indexesNothing() {
        val (events, _) = walk(listOf(media(1)), listOf(stored(2)), scope = ScanScope(emptySet()))
        assertEquals(listOf<DiffEvent>(DiffEvent.Vanished(2)), events)
    }
}
