// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.tags.TagValues
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryWriterTest {

    private lateinit var db: FernixDatabase
    private lateinit var writer: LibraryWriter

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FernixDatabase::class.java).build()
        writer = newWriter()
    }

    @After
    fun close() = db.close()

    private fun newWriter() = LibraryWriter(db, FolderResolver(db.folderDao()))

    private fun media(id: Long, folder: String = "external_primary/Music/Rock", size: Long = 10) =
        MediaRow(id, size, 5, "$id.mp3", folder, durationMs = 1000)

    private fun upsert(id: Long, vararg tags: Pair<String, List<String>>, folder: String = "external_primary/Music/Rock", size: Long = 10) =
        LibraryOp.Upsert(media(id, folder, size), mapOf(*tags))

    private fun scalar(sql: String, vararg args: Any): Long =
        db.openHelper.readableDatabase.query(sql, args).use { it.moveToFirst(); it.getLong(0) }

    private fun trackCountFor(key: String, value: String) = scalar(
        "SELECT kv.trackCount FROM key_values kv JOIN tag_keys k ON k.id = kv.keyId JOIN tag_values v ON v.id = kv.valueId " +
            "WHERE k.`key` = ? AND v.value = ?", key, value,
    )

    private fun personCount(name: String): Long? = db.openHelper.readableDatabase.query(
        "SELECT p.trackCount FROM people p JOIN tag_values v ON v.id = p.valueId WHERE v.value = ?", arrayOf(name),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    @Test
    fun insert_storesTrackFieldsFolderAndAlbum() = runBlocking {
        writer.apply(
            listOf(
                upsert(
                    1, "title" to listOf(" Song "), "ARTIST" to listOf("A", "B"), "ALBUM" to listOf("Abbey"),
                    "DATE" to listOf("1969-09-26"), "TRACKNUMBER" to listOf("3/17"),
                ),
            ),
        )
        val track = db.trackDao().byId(db.trackDao().idByMediaStoreId(1)!!)!!
        assertEquals("Song", track.title)
        assertEquals(TagValues.join(listOf("A", "B")), track.artist)
        assertEquals(3, track.trackNumber)
        assertEquals(1969, track.year)
        assertEquals(1000L, track.durationMs)
        assertEquals(db.folderDao().byPath("external_primary/Music/Rock")!!.id, track.folderId)
        assertEquals(db.albumDao().find("Abbey", 1969)!!.id, track.albumId)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM tracks_fts WHERE tracks_fts MATCH 'song'"))
    }

    @Test
    fun reparse_keepsPlayHistory_andStoresDateAdded_setDateAddedTouchesOnlyThatColumn() = runBlocking {
        writer.apply(listOf(LibraryOp.Upsert(media(1).copy(dateAdded = 100), mapOf("TITLE" to listOf("Old")))))
        val id = db.trackDao().idByMediaStoreId(1)!!
        assertEquals(100L, db.trackDao().byId(id)!!.dateAdded)

        db.trackDao().update(db.trackDao().byId(id)!!.copy(playCount = 3, lastPlayedAt = 555))
        writer.apply(listOf(LibraryOp.Upsert(media(1, size = 99).copy(dateAdded = 200), mapOf("TITLE" to listOf("New")))))
        val after = db.trackDao().byId(id)!!
        assertEquals("New", after.title)
        assertEquals(200L, after.dateAdded)
        assertEquals(3, after.playCount)
        assertEquals(555L, after.lastPlayedAt)

        writer.apply(listOf(LibraryOp.SetDateAdded(1, 300)))
        val touched = db.trackDao().byId(id)!!
        assertEquals(after.copy(dateAdded = 300), touched)
    }

    @Test
    fun aStoredSplitRule_splitsTheValueAtIngestion_andRecordsTheTrack() = runBlocking {
        writer.apply(listOf(upsert(1, "ARTIST" to listOf("Ann"))))
        val artist = scalar("SELECT id FROM tag_keys WHERE `key` = 'ARTIST'")
        val rule = db.splitRuleDao().insert(
            io.github.libregeek.fernix.data.db.SplitRuleEntity(
                keyId = artist,
                value = "Simon; Garfunkel",
                parts = TagValues.join(listOf("Simon", "Garfunkel")),
                createdAt = 1,
            ),
        )

        // A new file, and a rescan of a changed one, both carrying the joined value; a new writer per scan.
        val scan = newWriter()
        scan.apply(
            listOf(
                upsert(2, "ARTIST" to listOf("simon; GARFUNKEL", "Ann")),
                upsert(1, "ARTIST" to listOf("Simon; Garfunkel"), size = 99),
            ),
        )
        fun artists(mediaStoreId: Long) = db.openHelper.readableDatabase.query(
            "SELECT v.value FROM track_tags tt JOIN tag_values v ON v.id = tt.valueId JOIN tracks t ON t.id = tt.trackId " +
                "WHERE t.mediaStoreId = ? AND tt.keyId = ? ORDER BY tt.ord",
            arrayOf(mediaStoreId, artist),
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        assertEquals(listOf("Simon", "Garfunkel", "Ann"), artists(2))
        assertEquals(listOf("Simon", "Garfunkel"), artists(1))
        assertEquals(listOf("Simon", "Garfunkel", "Ann"), TagValues.split(db.trackDao().byId(db.trackDao().idByMediaStoreId(2)!!)!!.artist))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM split_rule_tracks WHERE ruleId = ?", rule))
        assertEquals(2L, trackCountFor("ARTIST", "Simon"))
        assertEquals(2L, personCount("Garfunkel"))
    }

    @Test
    fun tagRows_keepOrder_andSharedValueIsOneRow() = runBlocking {
        writer.apply(listOf(upsert(1, "ARTIST" to listOf("Zed", "Amy"), "COMPOSER" to listOf("zed"))))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM tag_values"))
        assertEquals(
            listOf("Zed", "Amy"),
            db.openHelper.readableDatabase.query(
                "SELECT v.value FROM track_tags tt JOIN tag_values v ON v.id = tt.valueId JOIN tag_keys k ON k.id = tt.keyId " +
                    "WHERE k.`key` = 'ARTIST' ORDER BY tt.ord",
            ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() },
        )
    }

    @Test
    fun summaries_countTracks_andPeopleAcrossRoles() = runBlocking {
        writer.apply(
            listOf(
                upsert(1, "ARTIST" to listOf("Ann"), "GENRE" to listOf("Rock")),
                upsert(2, "COMPOSER" to listOf("Ann"), "GENRE" to listOf("Rock")),
                upsert(3, "ARTIST" to listOf("Bob"), "COMPOSER" to listOf("Bob")),
            ),
        )
        assertEquals(2L, trackCountFor("GENRE", "Rock"))
        assertEquals(1L, trackCountFor("ARTIST", "Ann"))
        assertEquals(2L, personCount("Ann"))
        assertEquals(1L, personCount("Bob"))
        assertNull(personCount("Rock"))
    }

    @Test
    fun reUpsert_replacesTagsAndFixesSummaries() = runBlocking {
        writer.apply(listOf(upsert(1, "ARTIST" to listOf("Ann"), "GENRE" to listOf("Rock"))))
        val id = db.trackDao().idByMediaStoreId(1)!!

        newWriter().apply(listOf(upsert(1, "ARTIST" to listOf("Bob"), size = 99)))

        assertEquals(id, db.trackDao().idByMediaStoreId(1))
        assertEquals(99L, db.trackDao().byId(id)!!.sizeBytes)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM track_tags"))
        assertNull(personCount("Ann"))
        assertEquals(1L, personCount("Bob"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM key_values kv JOIN tag_values v ON v.id = kv.valueId WHERE v.value = 'Rock'"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM tracks_fts WHERE tracks_fts MATCH 'bob'"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM tracks_fts WHERE tracks_fts MATCH 'ann'"))
    }

    @Test
    fun remove_deletesTrack_andDecrementsSummaries() = runBlocking {
        writer.apply(
            listOf(upsert(1, "ARTIST" to listOf("Ann")), upsert(2, "ARTIST" to listOf("Ann")), upsert(3, "ARTIST" to listOf("Cy"))),
        )
        writer.apply(listOf(LibraryOp.Remove(1), LibraryOp.Remove(3), LibraryOp.Remove(999)))

        assertNull(db.trackDao().idByMediaStoreId(1))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM tracks"))
        assertEquals(1L, personCount("Ann"))
        assertNull(personCount("Cy"))
        assertEquals(1L, trackCountFor("ARTIST", "Ann"))
    }

    @Test
    fun albumsWithoutYear_shareYearZero_andDifferentYearsSplit() = runBlocking {
        writer.apply(
            listOf(
                upsert(1, "ALBUM" to listOf("X")),
                upsert(2, "ALBUM" to listOf("x")),
                upsert(3, "ALBUM" to listOf("X"), "DATE" to listOf("2001")),
            ),
        )
        assertEquals(2L, scalar("SELECT COUNT(*) FROM albums"))
        val a1 = db.trackDao().byId(db.trackDao().idByMediaStoreId(1)!!)!!.albumId
        val a2 = db.trackDao().byId(db.trackDao().idByMediaStoreId(2)!!)!!.albumId
        assertNotNull(a1)
        assertEquals(a1, a2)
    }

    @Test
    fun unreadableFile_isStoredWithoutTags() = runBlocking {
        writer.apply(listOf(LibraryOp.Upsert(media(1), null)))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM tracks"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM track_tags"))
        assertNull(db.trackDao().byId(db.trackDao().idByMediaStoreId(1)!!)!!.albumId)
    }

    @Test
    fun performerKeys_makePeople() = runBlocking {
        writer.apply(listOf(upsert(1, "PERFORMER:GUITAR" to listOf("Jimi"))))
        assertEquals(1L, personCount("Jimi"))
    }
}
