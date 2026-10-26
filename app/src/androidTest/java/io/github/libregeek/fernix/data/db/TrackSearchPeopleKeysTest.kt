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

/** A person filter must work when [TrackSearch] was first used before the library had any tags (a fresh install). */
@RunWith(AndroidJUnit4::class)
class TrackSearchPeopleKeysTest {

    private lateinit var db: FernixDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FernixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun personFilter_worksOnceTagsExist_evenIfSearchedWhileEmpty() = runBlocking {
        val search = TrackSearch(db)
        val person = TrackFilter(personValueId = 1)
        assertEquals(0, search.page(person).size)

        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO folders (id, parentId, name, path) VALUES (1, NULL, 'Music', 'vol/Music')")
        sql.execSQL(
            "INSERT INTO tracks (id, mediaStoreId, folderId, displayName, durationMs, dateModified, sizeBytes, " +
                "title, album, date, artist, composer, lyricist) VALUES (1, 1, 1, 'a.mp3', 1000, 5, 10, 'A', '', '', '', '', '')",
        )
        sql.execSQL("INSERT INTO tag_keys (id, `key`) VALUES (1, 'ARTIST'), (2, 'COMPOSER')")
        sql.execSQL("INSERT INTO tag_values (id, value) VALUES (1, 'Johnson')")
        sql.execSQL("INSERT INTO track_tags (trackId, keyId, valueId, ord) VALUES (1, 1, 1, 0)")

        assertEquals(listOf("A"), search.page(person).map { it.track.title })
        // Narrowed to a role the person does not have on the track, nothing matches; with the one they have, the track does.
        assertEquals(0, search.page(TrackFilter(personValueId = 1, personRoleKeyId = 2)).size)
        assertEquals(1, search.page(TrackFilter(personValueId = 1, personRoleKeyId = 1)).size)
    }
}
