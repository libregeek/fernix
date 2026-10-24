// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TagSplitService
import io.github.libregeek.fernix.tags.TagValues
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Split two tracks, then delete the rule: the files are read again and the joined value is back. */
@RunWith(AndroidJUnit4::class)
class SplitUndoTest {

    private lateinit var db: FernixDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FernixDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun media(id: Long) = MediaRow(id, 10, 5, "$id.mp3", "external_primary/Music", durationMs = 1000)

    private val files = mutableMapOf<Long, Map<String, List<String>>?>()

    private suspend fun ingest(id: Long, vararg tags: Pair<String, List<String>>) {
        files[id] = mapOf(*tags)
        LibraryWriter(db, FolderResolver(db.folderDao())).apply(listOf(LibraryOp.Upsert(media(id), files[id])))
    }

    private fun artistsOf(mediaStoreId: Long): List<String> = db.openHelper.readableDatabase.query(
        "SELECT v.value FROM track_tags tt JOIN tag_values v ON v.id = tt.valueId JOIN tracks t ON t.id = tt.trackId " +
            "JOIN tag_keys k ON k.id = tt.keyId WHERE t.mediaStoreId = ? AND k.`key` = 'ARTIST' ORDER BY tt.ord",
        arrayOf(mediaStoreId),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    private suspend fun splitSimon(): Long {
        val service = TagSplitService(db)
        val tracks = listOf(1L, 2L).map { db.trackDao().idByMediaStoreId(it)!! }
        val artist = db.tagDao().keyId("ARTIST")!!
        service.apply(listOf(service.preview(tracks, listOf(artist), listOf(";")).single()))
        return db.splitRuleDao().find(artist, "Simon; Garfunkel")!!.id
    }

    @Test
    fun deletingTheRule_readsTheFilesAgain_andRestoresTheJoinedValue() = runBlocking {
        ingest(1, "ARTIST" to listOf("Simon; Garfunkel"), "TITLE" to listOf("One"))
        ingest(2, "ARTIST" to listOf("Ann", "Simon; Garfunkel"), "TITLE" to listOf("Two"))
        val rule = splitSimon()
        assertEquals(listOf("Simon", "Garfunkel"), artistsOf(1))
        assertEquals(listOf("Ann", "Simon", "Garfunkel"), artistsOf(2))

        val progress = mutableListOf<Pair<Int, Int>>()
        val reread = SplitUndo(db, TagSource { files[it] }).delete(rule) { done, total -> progress += done to total }

        assertEquals(2, reread)
        assertEquals(listOf(2 to 2), progress)
        assertEquals(listOf("Simon; Garfunkel"), artistsOf(1))
        assertEquals(listOf("Ann", "Simon; Garfunkel"), artistsOf(2))
        assertEquals("Simon; Garfunkel", TagValues.split(db.trackDao().byId(db.trackDao().idByMediaStoreId(1)!!)!!.artist).single())
        assertNull(db.splitRuleDao().find(db.tagDao().keyId("ARTIST")!!, "Simon; Garfunkel"))
        // The parts only the split had made are gone from the dictionary; the joined value is back and counted.
        assertNull(db.tagDao().valueId("Garfunkel"))
        assertNull(db.tagDao().valueId("Simon"))
        assertEquals(2, db.tagDao().libraryTrackCount(db.tagDao().keyId("ARTIST")!!, db.tagDao().valueId("Simon; Garfunkel")!!))
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM split_rule_tracks").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test
    fun anUnreadableFile_isLeftAsItWas_notEmptied() = runBlocking {
        ingest(1, "ARTIST" to listOf("Simon; Garfunkel"))
        ingest(2, "ARTIST" to listOf("Simon; Garfunkel"))
        val rule = splitSimon()

        val reread = SplitUndo(db, TagSource { id -> if (id == 2L) null else files[id] }).delete(rule)

        assertEquals(1, reread)
        assertEquals(listOf("Simon; Garfunkel"), artistsOf(1))
        assertEquals(listOf("Simon", "Garfunkel"), artistsOf(2)) // still split, with its tags intact
        assertNotNull(db.tagDao().valueId("Garfunkel"))
    }

    @Test
    fun anotherRuleStillApplies_whileThisOneIsUndone() = runBlocking {
        ingest(1, "ARTIST" to listOf("Simon; Garfunkel", "Ann/Bob"), "GENRE" to listOf("Rock"))
        ingest(2, "ARTIST" to listOf("Simon; Garfunkel", "Ann/Bob"))
        val service = TagSplitService(db)
        val tracks = listOf(1L, 2L).map { db.trackDao().idByMediaStoreId(it)!! }
        val artist = db.tagDao().keyId("ARTIST")!!
        service.apply(service.preview(tracks, listOf(artist), listOf(";", "/")))
        val simon = db.splitRuleDao().find(artist, "Simon; Garfunkel")!!.id
        assertEquals(listOf("Simon", "Garfunkel", "Ann", "Bob"), artistsOf(1))

        SplitUndo(db, TagSource { files[it] }).delete(simon)

        assertEquals(listOf("Simon; Garfunkel", "Ann", "Bob"), artistsOf(1))
    }
}
