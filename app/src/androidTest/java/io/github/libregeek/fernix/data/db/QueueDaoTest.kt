// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The saved queue (spec/architecture.md §8 *Saved queue*): order and duplicates kept, deleted tracks dropped. */
@RunWith(AndroidJUnit4::class)
class QueueDaoTest {

    private lateinit var db: FernixDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FernixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.execSQL("INSERT INTO folders (id, parentId, name, path) VALUES (1, NULL, 'Music', 'vol/Music')")
        for (id in 1..3) {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO tracks (id, mediaStoreId, folderId, displayName, durationMs, dateModified, sizeBytes, " +
                    "title, album, date, artist, composer, lyricist) VALUES ($id, $id, 1, 't$id.mp3', 1000, 5, 10, 't$id', '', '', '', '', '')",
            )
        }
    }

    @After
    fun close() = db.close()

    @Test
    fun replace_keepsOrderAndDuplicates_andReplacesWhatWasThere() = runBlocking {
        val dao = db.queueDao()
        dao.replace(listOf(1, 2, 3))
        dao.replace(listOf(3, 1, 3))
        assertEquals(listOf("t3", "t1", "t3"), dao.load().map { it.title })
    }

    @Test
    fun load_dropsTracksTheLibraryNoLongerHas() = runBlocking {
        val dao = db.queueDao()
        dao.replace(listOf(1, 2, 3))
        db.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE id = 2")
        assertEquals(listOf("t1", "t3"), dao.load().map { it.title })
    }

    @Test
    fun replace_withAnEmptyList_clearsTheQueue() = runBlocking {
        val dao = db.queueDao()
        dao.replace(listOf(1, 2))
        dao.replace(emptyList())
        assertEquals(emptyList<TrackRow>(), dao.load())
    }

    @Test
    fun replace_handlesMoreThanOneChunk() = runBlocking {
        val dao = db.queueDao()
        dao.replace(List(1_300) { (it % 3 + 1).toLong() })
        assertEquals(1_300, dao.load().size)
    }
}
