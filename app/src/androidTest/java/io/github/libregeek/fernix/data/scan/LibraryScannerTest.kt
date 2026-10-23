// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScannerTest {

    private class FakeMedia : MediaSource {
        var mounted = setOf("external_primary")
        val rows = sortedMapOf<Long, MediaRow>()
        override fun mountedVolumes() = mounted
        override fun query(volumes: Set<String>) = object : MediaRowIterator {
            private val it = rows.values.filter { ScanScope.volumeOf(it.folderPath) in volumes }.iterator()
            override fun hasNext() = it.hasNext()
            override fun next() = it.next()
            override fun close() {}
        }
    }

    private class FakeTags : TagSource {
        val tags = HashMap<Long, Map<String, List<String>>>()
        var reads = 0
        var failAfter = Int.MAX_VALUE
        override fun read(mediaStoreId: Long): Map<String, List<String>>? {
            if (reads++ >= failAfter) throw IllegalStateException("boom")
            return tags[mediaStoreId]
        }
    }

    private lateinit var db: FernixDatabase
    private val media = FakeMedia()
    private val tags = FakeTags()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FernixDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun scanner(batch: Int = 500) = LibraryScanner(db, media, tags, batch)

    private fun add(id: Long, folder: String = "external_primary/Music/Rock", size: Long = 10, vararg t: Pair<String, List<String>>) {
        media.rows[id] = MediaRow(id, size, 5, "$id.mp3", folder, durationMs = 1000)
        tags.tags[id] = mapOf(*t)
    }

    private fun count(table: String): Long =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun firstScan_ingestsEverything_andFinishes() = runBlocking {
        add(1, t = arrayOf("TITLE" to listOf("A"), "ARTIST" to listOf("Ann")))
        add(2, t = arrayOf("TITLE" to listOf("B"), "ARTIST" to listOf("Ann")))
        val scanner = scanner()

        scanner.scan(ScanScope.All)

        assertEquals(ScanState.Finished(2), scanner.state.value)
        assertEquals(2L, count("tracks"))
        assertEquals(1L, count("people"))
    }

    @Test
    fun secondScan_withNothingChanged_readsNoFiles() = runBlocking {
        add(1, t = arrayOf("TITLE" to listOf("A")))
        scanner().scan(ScanScope.All)
        val reads = tags.reads

        val again = scanner()
        again.scan(ScanScope.All)

        assertEquals(reads, tags.reads)
        assertEquals(ScanState.Finished(0), again.state.value)
    }

    @Test
    fun changedAndVanishedFiles_areApplied_andCleanedUp() = runBlocking {
        add(1, "external_primary/Music/Rock", t = arrayOf("ALBUM" to listOf("X"), "ARTIST" to listOf("Ann")))
        add(2, "external_primary/Music/Jazz", t = arrayOf("ALBUM" to listOf("Y"), "ARTIST" to listOf("Bob")))
        scanner().scan(ScanScope.All)
        assertEquals(2L, count("albums"))

        media.rows.remove(2)
        add(1, "external_primary/Music/Rock", size = 99, "ALBUM" to listOf("X"), "ARTIST" to listOf("Cy"))
        scanner().scan(ScanScope.All)

        assertEquals(1L, count("tracks"))
        assertEquals(1L, count("albums"))
        // Jazz folder is gone; volume, Music and Rock remain.
        assertEquals(3L, count("folders"))
        // Only X and Cy are still used; Ann and Bob were cleaned up.
        assertEquals(2L, count("tag_values"))
    }

    @Test
    fun interruptedScan_resumesWhereItStopped() = runBlocking {
        for (id in 1L..5L) add(id, t = arrayOf("TITLE" to listOf("T$id")))
        tags.failAfter = 3

        val first = scanner(batch = 2)
        first.scan(ScanScope.All)
        assertTrue(first.state.value is ScanState.Failed)
        // Batch 1 (ids 1-2) was committed; the failure came while reading id 4.
        assertEquals(2L, count("tracks"))

        tags.failAfter = Int.MAX_VALUE
        val readsBefore = tags.reads
        val second = scanner(batch = 2)
        second.scan(ScanScope.All)

        assertEquals(ScanState.Finished(3), second.state.value)
        assertEquals(5L, count("tracks"))
        assertEquals(3, tags.reads - readsBefore)
    }

    @Test
    fun unmountedVolume_keepsItsTracks() = runBlocking {
        add(1, "external_primary/Music")
        add(2, "1234-5678/Music")
        media.mounted = setOf("external_primary", "1234-5678")
        scanner().scan(ScanScope.All)
        assertEquals(2L, count("tracks"))

        media.mounted = setOf("external_primary")
        scanner().scan(ScanScope.All)

        assertEquals(2L, count("tracks"))
        assertEquals(4L, count("folders"))
    }

    @Test
    fun narrowingTheScope_removesTracksOutsideIt() = runBlocking {
        add(1, "external_primary/Music/Rock")
        add(2, "external_primary/Music/Jazz")
        scanner().scan(ScanScope.All)

        scanner().scan(ScanScope.Selected(setOf("external_primary/Music/Jazz")))

        assertEquals(1L, count("tracks"))
        assertEquals(null, db.trackDao().idByMediaStoreId(1))
    }

    @Test
    fun emptySelection_indexesNothing() = runBlocking {
        add(1)
        scanner().scan(ScanScope.Selected(emptySet()))
        assertEquals(0L, count("tracks"))
    }
}
