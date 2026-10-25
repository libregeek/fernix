// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

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

    /** One track as a `Flow`, e.g. the playing one; null once it is deleted. */
    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: Long): Flow<TrackEntity?>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Every track with its folder path, ordered by `mediaStoreId`, for the scan diff (spec §6.2).
     * Blocking and unpaged on purpose: the caller walks it row by row and closes it. Call from
     * `Dispatchers.IO`.
     */
    @Query(
        "SELECT t.mediaStoreId, t.sizeBytes, t.dateModified, t.dateAdded, t.displayName, f.path AS folderPath " +
            "FROM tracks t JOIN folders f ON f.id = t.folderId ORDER BY t.mediaStoreId",
    )
    fun storedCursor(): Cursor

    /** Sets only `dateAdded`, for a track stored before the column existed. */
    @Query("UPDATE tracks SET dateAdded = :dateAdded WHERE mediaStoreId = :mediaStoreId")
    suspend fun setDateAdded(mediaStoreId: Long, dateAdded: Long)

    /** Tracks per folder, for counting what a narrower scan scope would remove (a handful of rows per folder, not per track). */
    @Query("SELECT f.path AS path, COUNT(*) AS n FROM tracks t JOIN folders f ON f.id = t.folderId GROUP BY f.id")
    suspend fun trackCountsByFolder(): List<FolderCount>

    /** Playlist entries per folder of their track. */
    @Query(
        "SELECT f.path AS path, COUNT(*) AS n FROM playlist_entries e JOIN tracks t ON t.id = e.trackId " +
            "JOIN folders f ON f.id = t.folderId GROUP BY f.id",
    )
    suspend fun playlistEntryCountsByFolder(): List<FolderCount>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /** The number of tracks in the library, again whenever it changes (shown on Tracks, spec §9.3). */
    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    /** Album detail, by disc then track number (missing numbers sort first). */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs, albumId, date, folderId FROM tracks " +
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

    /** The files of a person's first [limit] tracks by `id`, to look for their image. */
    @Query(
        "SELECT id, mediaStoreId FROM tracks WHERE id IN " +
            "(SELECT trackId FROM track_tags WHERE valueId = :personId AND keyId IN (:peopleKeyIds)) " +
            "ORDER BY id LIMIT :limit",
    )
    suspend fun personTrackFiles(personId: Long, peopleKeyIds: List<Long>, limit: Int): List<TrackFile>

    /** The files of an album's first [limit] tracks (disc/track order), to look for its art. */
    @Query(
        "SELECT mediaStoreId FROM tracks WHERE albumId = :albumId " +
            "ORDER BY COALESCE(discNumber, 0), COALESCE(trackNumber, 0), id LIMIT :limit",
    )
    suspend fun albumMediaStoreIds(albumId: Long, limit: Int): List<Long>

    /** Every track of an album, in the order of [albumPage]. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs, albumId, date, folderId FROM tracks WHERE albumId = :albumId " +
            "ORDER BY COALESCE(discNumber, 0), COALESCE(trackNumber, 0), id",
    )
    suspend fun albumTracks(albumId: Long): List<TrackRow>

    /** Tracks directly in a folder, by file name. Start with `afterName = ""`, `afterId = 0`. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs, albumId, date, folderId FROM tracks " +
            "WHERE folderId = :folderId " +
            "AND (displayName COLLATE NOCASE, id) > (:afterName, :afterId) " +
            "ORDER BY displayName COLLATE NOCASE, id LIMIT :limit",
    )
    suspend fun folderPage(folderId: Long, afterName: String, afterId: Long, limit: Int): List<TrackRow>

    /** Every track directly in a folder, in the order of [folderPage]. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs, albumId, date, folderId FROM tracks " +
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
        "SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs, t.albumId, t.date, t.folderId FROM tracks t " +
            "JOIN folders f ON f.id = t.folderId " +
            "WHERE f.path = :path OR (f.path >= :low AND f.path < :high) " +
            "ORDER BY f.path, t.displayName COLLATE NOCASE, t.id",
    )
    suspend fun subtreeTracks(path: String, low: String, high: String): List<TrackRow>

    /** *Recently Added*: the [DEFAULT_PLAYLIST_SIZE] newest tracks (spec/architecture.md §5). */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs, albumId, date, folderId FROM tracks " +
            "ORDER BY dateAdded DESC, id DESC LIMIT $DEFAULT_PLAYLIST_SIZE",
    )
    suspend fun recentlyAdded(): List<TrackRow>

    /** *Most Played*: the [DEFAULT_PLAYLIST_SIZE] tracks played most, ties by most recent play. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs, albumId, date, folderId FROM tracks WHERE playCount > 0 " +
            "ORDER BY playCount DESC, lastPlayedAt DESC, id LIMIT $DEFAULT_PLAYLIST_SIZE",
    )
    suspend fun mostPlayed(): List<TrackRow>

    /**
     * Forgets every file date, so the next scan sees each track as changed and reads its tags again (the
     * *Re-read all tags* action; play history and playlist entries are kept, as in any re-parse).
     */
    @Query("UPDATE tracks SET dateModified = 0")
    suspend fun forgetFileDates()

    /** The size of *Never Played*; a track is in it while `lastPlayedAt` is null. */
    @Query("SELECT COUNT(*) FROM tracks WHERE lastPlayedAt IS NULL")
    suspend fun neverPlayedCount(): Int

    /** A track played through to its end (spec §5 *What counts as a play*). */
    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :now WHERE id = :trackId")
    suspend fun recordPlay(trackId: Long, now: Long)

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
