// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.FolderEntity
import io.github.libregeek.fernix.data.db.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoredRowsTest {

    private val EVERYTHING = ScanScope(setOf("external_primary", "1234-5678"))

    private lateinit var db: FernixDatabase

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, FernixDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private suspend fun track(mediaStoreId: Long, folderId: Long, name: String = "$mediaStoreId.mp3", size: Long = 10) =
        db.trackDao().insert(
            TrackEntity(
                mediaStoreId = mediaStoreId, folderId = folderId, displayName = name,
                durationMs = 1000, dateModified = 5, sizeBytes = size,
            ),
        )

    @Test
    fun emptyLibrary_hasNoRows() {
        db.trackDao().storedCursor().let(::StoredRows).use { assertFalse(it.hasNext()) }
    }

    @Test
    fun rowsAreOrderedByMediaStoreId_withFolderPath() = runBlocking {
        val music = db.folderDao().insert(FolderEntity(parentId = null, name = "Music", path = "external_primary/Music"))
        val sd = db.folderDao().insert(FolderEntity(parentId = null, name = "Rock", path = "1234-5678/Rock"))
        track(30, music)
        track(10, sd, name = "x.flac", size = 99)
        track(20, music)

        val rows = db.trackDao().storedCursor().let(::StoredRows).use { it.asSequence().toList() }

        assertEquals(listOf(10L, 20L, 30L), rows.map { it.mediaStoreId })
        assertEquals(StoredRow(10, 99, 5, "x.flac", "1234-5678/Rock"), rows[0])
        assertEquals("external_primary/Music", rows[1].folderPath)
    }

    @Test
    fun walkAgainstDatabase_findsNewChangedAndVanished() = runBlocking {
        val music = db.folderDao().insert(FolderEntity(parentId = null, name = "Music", path = "external_primary/Music"))
        track(1, music)
        track(2, music)
        track(3, music)

        fun media(id: Long, size: Long = 10) = MediaRow(id, size, 5, "$id.mp3", "external_primary/Music")
        val events = mutableListOf<DiffEvent>()
        db.trackDao().storedCursor().let(::StoredRows).use { stored ->
            ScanDiff.walk(
                listOf(media(1), media(3, size = 11), media(4)).iterator(),
                stored, EVERYTHING, setOf("external_primary"), events::add,
            )
        }

        assertEquals(
            listOf(DiffEvent.Vanished(2), DiffEvent.Changed(media(3, 11)), DiffEvent.New(media(4))),
            events,
        )
    }
}
