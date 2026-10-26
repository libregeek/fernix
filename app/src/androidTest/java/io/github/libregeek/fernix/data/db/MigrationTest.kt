// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a version 1 database from the exported schema (no extra test library), fills it, and
 * opens it with the real migrations. Opening also makes Room validate the result against version 2.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = context.getDatabasePath("migration-test.db")

    @Before
    fun deleteOld() {
        context.deleteDatabase(file.name)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(file.name)
    }

    private fun createVersion(version: Int, fill: SQLiteDatabase.() -> Unit) {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("${FernixDatabase::class.java.name}/$version.json").bufferedReader().use { JSONObject(it.readText()) }
        val entities = json.getJSONObject("database").getJSONArray("entities")
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                    for (t in 0 until triggers.length()) db.execSQL(triggers.getString(t))
                }
                entity.optJSONArray("indices")?.let { indices ->
                    for (x in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(x).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
            }
            db.fill()
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun SQLiteDatabase.addTrack(id: Long, title: String) = execSQL(
        "INSERT INTO tracks (id, mediaStoreId, folderId, displayName, durationMs, dateModified, sizeBytes, " +
            "title, album, date, artist, composer, lyricist) VALUES ($id, $id, 1, '$title.mp3', 1000, 5, 10, " +
            "'$title', '', '', '', '', '')",
    )

    private fun open() = Room.databaseBuilder(context, FernixDatabase::class.java, file.name)
        .addMigrations(FernixDatabase.MIGRATION_1_2, FernixDatabase.MIGRATION_2_3, FernixDatabase.MIGRATION_3_4)
        .allowMainThreadQueries()
        .build()

    @Test
    fun migrate1To2_addsColumnsAndFavourites_keepsData_dropsDuplicateEntries() = runBlocking {
        createVersion(1) {
            execSQL("INSERT INTO folders (id, parentId, name, path) VALUES (1, NULL, 'Music', 'vol/Music')")
            addTrack(1, "A")
            addTrack(2, "B")
            execSQL("INSERT INTO playlists (id, name, createdAt, updatedAt) VALUES (1, 'Mix', 1, 1)")
            // A, B, A again: the second A must go; the survivors keep their positions.
            execSQL("INSERT INTO playlist_entries (playlistId, position, trackId) VALUES (1, 0, 1), (1, 1, 2), (1, 2, 1)")
        }

        val db = open()
        try {
            // Existing data survives, with the new columns at their defaults.
            val track = db.trackDao().byId(1)!!
            assertEquals("A", track.title)
            assertEquals(0L, track.dateAdded)
            assertEquals(0, track.playCount)
            assertNull(track.lastPlayedAt)

            val playlists = db.playlistDao()
            val mix = playlists.summaries().first().single()
            assertEquals("Mix", mix.name)
            assertEquals(2, mix.trackCount)
            assertEquals(listOf("A", "B"), playlists.tracks(1).map { it.title })
            assertEquals(listOf(0, 1), playlists.entriesPage(1, -1, PAGE_SIZE).map { it.position })

            // The Favourites row exists once, is not listed among the user's playlists, and is protected.
            val favourites = playlists.favouritesId()
            assertNotNull(favourites)
            assertEquals(1, playlists.summaries().first().size)
            playlists.rename(favourites!!, "Renamed", now = 9)
            playlists.delete(favourites)
            assertEquals(FernixDatabase.FAVOURITES_NAME, playlists.byId(favourites)!!.name)

            // The unique index is live: the same track can't be added twice, even by a raw insert.
            try {
                playlists.insertEntry(PlaylistEntryEntity(1, 5, 1))
                fail("duplicate playlist entry accepted")
            } catch (expected: SQLiteConstraintException) {
                // expected
            }
            playlists.append(1, listOf(1, 2), now = 10)
            assertEquals(2, playlists.tracks(1).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To3_dropsTheSplitRuleTables_keepsEverythingElse() = runBlocking {
        createVersion(2) {
            execSQL("INSERT INTO folders (id, parentId, name, path) VALUES (1, NULL, 'Music', 'vol/Music')")
            addTrack(1, "A")
            execSQL("INSERT INTO tag_keys (id, `key`) VALUES (1, 'ARTIST')")
            execSQL("INSERT INTO split_rules (id, keyId, value, parts, createdAt) VALUES (1, 1, 'X; Y', 'X\u001FY', 1)")
            execSQL("INSERT INTO split_rule_tracks (ruleId, trackId) VALUES (1, 1)")
            execSQL("INSERT INTO playlists (id, name, kind, createdAt, updatedAt) VALUES (1, 'Mix', 'user', 1, 1)")
        }

        val db = open()
        try {
            assertEquals("A", db.trackDao().byId(1)!!.title)
            assertEquals("Mix", db.playlistDao().byId(1)!!.name)
            val tables = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'")
                .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
            assertTrue(tables.none { it.startsWith("split_rule") })
            assertTrue("tag_keys" in tables)
        } finally {
            db.close()
        }
    }

    @Test
    fun newDatabase_getsFavouritesFromCallback() = runBlocking {
        val db = Room.databaseBuilder(context, FernixDatabase::class.java, file.name)
            .addCallback(FernixDatabase.CREATE_CALLBACK)
            .allowMainThreadQueries()
            .build()
        try {
            assertNotNull(db.playlistDao().favouritesId())
            assertTrue(db.playlistDao().summaries().first().isEmpty())
        } finally {
            db.close()
        }
    }
}
