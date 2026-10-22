// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface TrackDao {

    @Insert
    suspend fun insert(track: TrackEntity): Long

    @Insert
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

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

    /** Tracks directly in a folder, by file name. Start with `afterName = ""`, `afterId = 0`. */
    @Query(
        "SELECT id, mediaStoreId, title, album, artist, durationMs FROM tracks " +
            "WHERE folderId = :folderId " +
            "AND (displayName COLLATE NOCASE, id) > (:afterName, :afterId) " +
            "ORDER BY displayName COLLATE NOCASE, id LIMIT :limit",
    )
    suspend fun folderPage(folderId: Long, afterName: String, afterId: Long, limit: Int): List<TrackRow>

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
