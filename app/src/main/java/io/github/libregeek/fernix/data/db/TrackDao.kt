// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface TrackDao {

    @Insert
    suspend fun insert(track: TrackEntity): Long

    @Insert
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT id FROM tracks WHERE mediaStoreId = :mediaStoreId")
    suspend fun idByMediaStoreId(mediaStoreId: Long): Long?

    /** The (key, value) pairs of a track's tags, to recompute their summaries when it changes. */
    @Query("SELECT keyId, valueId FROM track_tags WHERE trackId = :trackId")
    suspend fun tagPairs(trackId: Long): List<TagPair>

    @Query("DELETE FROM track_tags WHERE trackId = :trackId")
    suspend fun deleteTags(trackId: Long)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Every track with its folder path, ordered by `mediaStoreId`, for the scan diff (spec §6.2).
     * Blocking and unpaged on purpose: the caller walks it row by row and closes it. Call from
     * `Dispatchers.IO`.
     */
    @Query(
        "SELECT t.mediaStoreId, t.sizeBytes, t.dateModified, t.displayName, f.path AS folderPath " +
            "FROM tracks t JOIN folders f ON f.id = t.folderId ORDER BY t.mediaStoreId",
    )
    fun storedCursor(): Cursor

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /** Album detail, by disc then track number (missing numbers sort first). */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs FROM tracks " +
            "WHERE albumId = :albumId " +
            "AND (COALESCE(discNumber, 0), COALESCE(trackNumber, 0), id) > (:afterDisc, :afterTrack, :afterId) " +
            "ORDER BY COALESCE(discNumber, 0), COALESCE(trackNumber, 0), id LIMIT :limit",
    )
    suspend fun albumPage(
        albumId: Long,
        afterDisc: Int,
        afterTrack: Int,
        afterId: Long,
        limit: Int,
    ): List<TrackRow>

    /** Every track of an album, in the order of [albumPage]. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs FROM tracks WHERE albumId = :albumId " +
            "ORDER BY COALESCE(discNumber, 0), COALESCE(trackNumber, 0), id",
    )
    suspend fun albumTracks(albumId: Long): List<TrackRow>

    /** Tracks directly in a folder, by file name. Start with `afterName = ""`, `afterId = 0`. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs FROM tracks " +
            "WHERE folderId = :folderId " +
            "AND (displayName COLLATE NOCASE, id) > (:afterName, :afterId) " +
            "ORDER BY displayName COLLATE NOCASE, id LIMIT :limit",
    )
    suspend fun folderPage(folderId: Long, afterName: String, afterId: Long, limit: Int): List<TrackRow>

    /** Every track directly in a folder, in the order of [folderPage]. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs FROM tracks " +
            "WHERE folderId = :folderId ORDER BY displayName COLLATE NOCASE, id",
    )
    suspend fun folderTracks(folderId: Long): List<TrackRow>

    /**
     * Every track in a folder and its sub-folders, folder by folder (path order) and by file name
     * within each. [path] is the folder's `folders.path`; [low] and [high] are
     * [FolderPaths.subtreeRange], so the sub-folders are a range scan on the unique `path` index
     * with bound arguments (no `LIKE`).
     */
    @Query(
        "SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs FROM tracks t " +
            "JOIN folders f ON f.id = t.folderId " +
            "WHERE f.path = :path OR (f.path >= :low AND f.path < :high) " +
            "ORDER BY f.path, t.displayName COLLATE NOCASE, t.id",
    )
    suspend fun subtreeTracks(path: String, low: String, high: String): List<TrackRow>

    /** Runs a query from [TrackQueryBuilder]. */
    @RawQuery
    suspend fun rawPage(query: SupportSQLiteQuery): List<TrackRow>

    /** The tag keys [personId] has on each of [trackIds], among [peopleKeyIds]. */
    @Query(
        "SELECT tt.trackId AS trackId, k.`key` AS `key` FROM track_tags tt " +
            "JOIN tag_keys k ON k.id = tt.keyId " +
            "WHERE tt.valueId = :personId AND tt.trackId IN (:trackIds) AND tt.keyId IN (:peopleKeyIds) " +
            "ORDER BY tt.trackId, k.`key`",
    )
    suspend fun roles(personId: Long, trackIds: List<Long>, peopleKeyIds: List<Long>): List<RoleRow>
}

data class TagPair(val keyId: Long, val valueId: Long)
