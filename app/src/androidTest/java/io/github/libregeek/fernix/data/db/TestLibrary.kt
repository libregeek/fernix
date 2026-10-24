// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.libregeek.fernix.tags.TagValues

/** Helpers to build small libraries in tests. */
class TestLibrary(val db: FernixDatabase) {

    private var nextMediaStoreId = 1L

    suspend fun folder(name: String = "Music", parentId: Long? = null): Long =
        db.folderDao().insert(FolderEntity(parentId = parentId, name = name, path = "vol/$name/${nextMediaStoreId++}"))

    suspend fun album(name: String, year: Int = 0): Long = db.albumDao().insert(AlbumEntity(album = name, year = year))

    suspend fun track(
        title: String,
        folderId: Long,
        artist: List<String> = emptyList(),
        album: String = "",
        albumId: Long? = null,
        disc: Int? = null,
        number: Int? = null,
        displayName: String = "$title.mp3",
        dateAdded: Long = 0,
        playCount: Int = 0,
        lastPlayedAt: Long? = null,
    ): Long = db.trackDao().insert(
        TrackEntity(
            mediaStoreId = nextMediaStoreId++,
            folderId = folderId,
            displayName = displayName,
            durationMs = 1000,
            dateModified = 0,
            sizeBytes = 1,
            title = title,
            album = album,
            artist = TagValues.join(artist),
            discNumber = disc,
            trackNumber = number,
            albumId = albumId,
            dateAdded = dateAdded,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
        ),
    )

    suspend fun keyId(key: String): Long {
        val inserted = db.tagDao().insertKey(TagKeyEntity(key = key))
        return if (inserted > 0) inserted else db.tagDao().keyId(key)!!
    }

    suspend fun valueId(value: String): Long {
        val inserted = db.tagDao().insertValue(TagValueEntity(value = value))
        return if (inserted > 0) inserted else db.tagDao().valueId(value)!!
    }

    /** Adds the values of [key] to a track, and keeps `key_values` and `people` in step. */
    suspend fun tag(trackId: Long, key: String, vararg values: String) {
        val keyId = keyId(key)
        val rows = values.mapIndexed { i, v -> TrackTagEntity(trackId, keyId, valueId(v), i) }
        db.tagDao().insertTrackTags(rows)
    }

    /** Recomputes `key_values` and `people` from `track_tags` (a test stand-in for the scanner). */
    fun refreshSummaries(peopleKeys: Set<String>) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM key_values")
        sql.execSQL(
            "INSERT INTO key_values SELECT keyId, valueId, COUNT(DISTINCT trackId) FROM track_tags GROUP BY keyId, valueId",
        )
        sql.execSQL("DELETE FROM people")
        val marks = peopleKeys.joinToString(",") { "?" }
        sql.execSQL(
            "INSERT INTO people SELECT valueId, COUNT(DISTINCT trackId) FROM track_tags " +
                "WHERE keyId IN (SELECT id FROM tag_keys WHERE `key` IN ($marks) OR `key` LIKE 'PERFORMER:%') GROUP BY valueId",
            peopleKeys.toTypedArray(),
        )
    }

    /** Runs EXPLAIN QUERY PLAN and returns the plan lines. */
    fun plan(query: SqlQuery): String {
        val cursor = db.openHelper.readableDatabase.query(
            SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.sql}", query.args.toTypedArray()),
        )
        return cursor.use {
            buildList { while (it.moveToNext()) add(it.getString(3)) }.joinToString("\n")
        }
    }

    companion object {
        fun inMemoryDb(context: Context): FernixDatabase =
            Room.inMemoryDatabaseBuilder(context, FernixDatabase::class.java).build()
    }
}
