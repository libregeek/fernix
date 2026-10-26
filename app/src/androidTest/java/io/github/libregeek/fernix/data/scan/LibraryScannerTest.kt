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

    private val EVERYTHING = ScanScope(setOf("external_primary", "1234-5678"))

    private class FakeMedia : MediaSource {
        var mounted = setOf("external_primary")
        val rows = sortedMapOf<Long, MediaRow>()
        var failing = false
        override fun mountedVolumes() = mounted
        override fun folders(volumes: Set<String>) = rows.values.map { it.folderPath }.toSet()
        override fun query(volumes: Set<String>): MediaRowIterator {
            if (failing) throw java.io.IOException("MediaStore did not answer")
            return rows(volumes)
        }
        private fun rows(volumes: Set<String>) = object : MediaRowIterator {
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
        // The scanner reads several files at a time.
        @Synchronized
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

        scanner.scan(EVERYTHING)

        assertEquals(ScanState.Finished(2), scanner.state.value)
        assertEquals(2L, count("tracks"))
        assertEquals(1L, count("people"))
    }

    @Test
    fun secondScan_withNothingChanged_readsNoFiles() = runBlocking {
        add(1, t = arrayOf("TITLE" to listOf("A")))
        scanner().scan(EVERYTHING)
        val reads = tags.reads

        val again = scanner()
        again.scan(EVERYTHING)

        assertEquals(reads, tags.reads)
        assertEquals(ScanState.Finished(0), again.state.value)
    }

    @Test
    fun forgettingFileDates_makesTheNextScanReadEveryFileAgain_andKeepsPlayHistory() = runBlocking {
        add(1, t = arrayOf("TITLE" to listOf("A")))
        add(2, t = arrayOf("TITLE" to listOf("B")))
        scanner().scan(EVERYTHING)
        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET playCount = 3 WHERE mediaStoreId = 1")
        // The file now has a title it did not have when it was first read.
        tags.tags[2] = mapOf("TITLE" to listOf("B (fixed)"))
        val readsBefore = tags.reads

        db.trackDao().forgetFileDates()
        scanner().scan(EVERYTHING)

        assertEquals(2, tags.reads - readsBefore)
        assertEquals(1L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks WHERE title = 'B (fixed)'").use { it.moveToFirst(); it.getLong(0) })
        assertEquals(3L, db.openHelper.readableDatabase.query("SELECT playCount FROM tracks WHERE mediaStoreId = 1").use { it.moveToFirst(); it.getLong(0) })
        // Read once more, nothing is read a third time.
        val reads = tags.reads
        scanner().scan(EVERYTHING)
        assertEquals(reads, tags.reads)
    }

    @Test
    fun changedAndVanishedFiles_areApplied_andCleanedUp() = runBlocking {
        add(1, "external_primary/Music/Rock", t = arrayOf("ALBUM" to listOf("X"), "ARTIST" to listOf("Ann")))
        add(2, "external_primary/Music/Jazz", t = arrayOf("ALBUM" to listOf("Y"), "ARTIST" to listOf("Bob")))
        scanner().scan(EVERYTHING)
        assertEquals(2L, count("albums"))

        media.rows.remove(2)
        add(1, "external_primary/Music/Rock", size = 99, "ALBUM" to listOf("X"), "ARTIST" to listOf("Cy"))
        scanner().scan(EVERYTHING)

        assertEquals(1L, count("tracks"))
        assertEquals(1L, count("albums"))
        // Jazz folder is gone; volume, Music and Rock remain.
        assertEquals(3L, count("folders"))
        // Only X and Cy are still used; Ann and Bob were cleaned up.
        assertEquals(2L, count("tag_values"))
    }

    @Test
    fun anUnreadableFile_isKeptUntagged_andReadAgainAtTheNextScan() = runBlocking {
        add(1, t = arrayOf("TITLE" to listOf("Good")))
        add(2, t = arrayOf("TITLE" to listOf("Bad later")))
        tags.tags.remove(2) // the file cannot be read this time
        val first = scanner()
        first.scan(EVERYTHING)
        assertEquals(ScanState.Finished(2, unreadable = 1), first.state.value)
        assertEquals(2L, count("tracks"))

        tags.tags[2] = mapOf("TITLE" to listOf("Bad later"))
        val readsBefore = tags.reads
        val second = scanner()
        second.scan(EVERYTHING)

        // Only the unreadable one is read again, and now it has its title.
        assertEquals(1, tags.reads - readsBefore)
        assertEquals(ScanState.Finished(1, unreadable = 0), second.state.value)
        assertEquals(1L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks WHERE title = 'Bad later'").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test
    fun anUnreadableFile_isLogged_byNameAndFolder() = runBlocking {
        val file = java.io.File.createTempFile("problems", ".log")
        val problems = io.github.libregeek.fernix.data.ProblemLog(file)
        add(1, "external_primary/Music/Rock", t = arrayOf("TITLE" to listOf("Good")))
        add(2, "external_primary/Music/Jazz")
        tags.tags.remove(2)
        LibraryScanner(db, media, tags, problems = problems).scan(EVERYTHING)

        val logged = problems.entries().single()
        assertEquals("2.mp3", logged.name)
        assertEquals("external_primary/Music/Jazz", logged.folder)
        assertEquals(io.github.libregeek.fernix.data.ProblemKind.Index, logged.kind)
        file.delete()
        Unit
    }

    @Test
    fun aFileRemovedBeforeItIsRead_isRemovedByTheNextScan() = runBlocking {
        add(1, t = arrayOf("TITLE" to listOf("Here")))
        add(2, t = arrayOf("TITLE" to listOf("Gone")))
        tags.tags.remove(2)
        scanner().scan(EVERYTHING)
        assertEquals(2L, count("tracks"))

        media.rows.remove(2) // it really is gone
        scanner().scan(EVERYTHING)

        assertEquals(1L, count("tracks"))
    }

    @Test
    fun aFailedMediaStoreQuery_failsTheScan_andLeavesTheLibraryAlone() = runBlocking {
        add(1, t = arrayOf("TITLE" to listOf("A")))
        add(2, t = arrayOf("TITLE" to listOf("B")))
        scanner().scan(EVERYTHING)

        media.failing = true // e.g. access revoked
        val failed = scanner()
        failed.scan(EVERYTHING)

        assertTrue(failed.state.value is ScanState.Failed)
        assertEquals(2L, count("tracks"))
        failed.clearFailure()
        assertEquals(ScanState.Idle, failed.state.value)
    }

    @Test
    fun interruptedScan_resumesWhereItStopped() = runBlocking {
        for (id in 1L..5L) add(id, t = arrayOf("TITLE" to listOf("T$id")))
        tags.failAfter = 3

        val first = scanner(batch = 2)
        first.scan(EVERYTHING)
        assertTrue(first.state.value is ScanState.Failed)
        // Batch 1 (ids 1-2) was committed; the failure came while reading id 4.
        assertEquals(2L, count("tracks"))

        tags.failAfter = Int.MAX_VALUE
        val readsBefore = tags.reads
        val second = scanner(batch = 2)
        second.scan(EVERYTHING)

        assertEquals(ScanState.Finished(3), second.state.value)
        assertEquals(5L, count("tracks"))
        assertEquals(3, tags.reads - readsBefore)
    }

    @Test
    fun unmountedVolume_keepsItsTracks() = runBlocking {
        add(1, "external_primary/Music")
        add(2, "1234-5678/Music")
        media.mounted = setOf("external_primary", "1234-5678")
        scanner().scan(EVERYTHING)
        assertEquals(2L, count("tracks"))

        media.mounted = setOf("external_primary")
        scanner().scan(EVERYTHING)

        assertEquals(2L, count("tracks"))
        assertEquals(4L, count("folders"))
    }

    @Test
    fun theImpactShownBeforeNarrowing_isWhatTheScanThenRemoves() = runBlocking {
        add(1, "external_primary/Music/Rock", t = arrayOf("TITLE" to listOf("A")))
        add(2, "external_primary/Music/Rock", t = arrayOf("TITLE" to listOf("B")))
        add(3, "external_primary/Music/Jazz", t = arrayOf("TITLE" to listOf("C")))
        scanner().scan(EVERYTHING)
        val playlist = db.playlistDao().insert(io.github.libregeek.fernix.data.db.PlaylistEntity(name = "p", createdAt = 0, updatedAt = 0))
        db.playlistDao().append(playlist, listOf(1L, 2L, 3L).map { db.trackDao().idByMediaStoreId(it)!! }, now = 1)
        val narrower = ScanScope(setOf("external_primary/Music/Jazz"))

        val impact = ScopeImpact.compute(db, narrower, media.mountedVolumes())
        assertEquals(ScopeImpact(tracks = 2, playlistEntries = 2), impact)

        scanner().scan(narrower)
        assertEquals(3L - impact.tracks, count("tracks"))
        assertEquals(3L - impact.playlistEntries, count("playlist_entries"))
    }

    @Test
    fun narrowingTheScope_removesTracksOutsideIt() = runBlocking {
        add(1, "external_primary/Music/Rock")
        add(2, "external_primary/Music/Jazz")
        scanner().scan(EVERYTHING)

        scanner().scan(ScanScope(setOf("external_primary/Music/Jazz")))

        assertEquals(1L, count("tracks"))
        assertEquals(null, db.trackDao().idByMediaStoreId(1))
    }

    @Test
    fun emptySelection_indexesNothing() = runBlocking {
        add(1)
        scanner().scan(ScanScope(emptySet()))
        assertEquals(0L, count("tracks"))
    }
}
