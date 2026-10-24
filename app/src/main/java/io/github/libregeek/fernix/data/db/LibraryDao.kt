// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FolderDao {

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("SELECT * FROM folders WHERE parentId IS NULL ORDER BY name, id")
    suspend fun volumes(): List<FolderEntity>

    /** Sub-folders of [parentId] by name. Start with `afterName = ""`, `afterId = 0`. */
    @Query(
        "SELECT * FROM folders WHERE parentId = :parentId AND (name, id) > (:afterName, :afterId) " +
            "ORDER BY name, id LIMIT :limit",
    )
    suspend fun childrenPage(parentId: Long, afterName: String, afterId: Long, limit: Int): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE path = :path")
    suspend fun byPath(path: String): FolderEntity?
}

@Dao
interface AlbumDao {

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("SELECT * FROM albums WHERE album = :album AND year = :year")
    suspend fun find(album: String, year: Int): AlbumEntity?

    /** Albums by name then year. Start with `afterAlbum = ""`, `afterYear = -1`. */
    @Query(
        "SELECT * FROM albums WHERE (album, year) > (:afterAlbum, :afterYear) " +
            "ORDER BY album, year LIMIT :limit",
    )
    suspend fun page(afterAlbum: String, afterYear: Int, limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): AlbumEntity?
}

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKey(key: TagKeyEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertValue(value: TagValueEntity): Long

    @Insert
    suspend fun insertTrackTags(rows: List<TrackTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKeyValues(rows: List<KeyValueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeople(rows: List<PersonEntity>)

    @Query("SELECT COUNT(DISTINCT trackId) FROM track_tags WHERE keyId = :keyId AND valueId = :valueId")
    suspend fun countTracks(keyId: Long, valueId: Long): Int

    @Query("DELETE FROM key_values WHERE keyId = :keyId AND valueId = :valueId")
    suspend fun deleteKeyValue(keyId: Long, valueId: Long)

    @Query("SELECT COUNT(DISTINCT trackId) FROM track_tags WHERE valueId = :valueId AND keyId IN (:peopleKeyIds)")
    suspend fun countPersonTracks(valueId: Long, peopleKeyIds: List<Long>): Int

    @Query("DELETE FROM people WHERE valueId = :valueId")
    suspend fun deletePerson(valueId: Long)

    @Query("SELECT id FROM tag_keys WHERE `key` = :key")
    suspend fun keyId(key: String): Long?

    @Query("SELECT id FROM tag_values WHERE value = :value")
    suspend fun valueId(value: String): Long?

    /** All keys present in the library (hundreds at most), for the key pickers. */
    @Query("SELECT * FROM tag_keys ORDER BY `key`")
    suspend fun allKeys(): List<TagKeyEntity>

    /** The ids of [keys] plus every `PERFORMER:*` key that exists. */
    @Query("SELECT id FROM tag_keys WHERE `key` IN (:keys) OR `key` LIKE 'PERFORMER:%'")
    suspend fun peopleKeyIds(keys: List<String>): List<Long>

    /**
     * Values under [keyId], by value. [likePattern] is an escaped prefix plus `%` (see
     * [likePrefix]); start with `afterValue = ""`, `afterId = 0`.
     */
    @Query(
        "SELECT v.id AS valueId, v.value AS value, kv.trackCount AS trackCount " +
            "FROM key_values kv JOIN tag_values v ON v.id = kv.valueId " +
            "WHERE kv.keyId = :keyId AND v.value LIKE :likePattern ESCAPE '\\' " +
            "AND (v.value, v.id) > (:afterValue, :afterId) " +
            "ORDER BY v.value, v.id LIMIT :limit",
    )
    suspend fun valuesPage(
        keyId: Long,
        likePattern: String,
        afterValue: String,
        afterId: Long,
        limit: Int,
    ): List<KeyValueRow>

    /** Every person once, by name. Start with `afterName = ""`, `afterId = 0`. */
    @Query(
        "SELECT v.id AS valueId, v.value AS name, p.trackCount AS trackCount " +
            "FROM tag_values v CROSS JOIN people p ON p.valueId = v.id " +
            "WHERE (v.value, v.id) > (:afterName, :afterId) " +
            "ORDER BY v.value, v.id LIMIT :limit",
    )
    suspend fun peoplePage(afterName: String, afterId: Long, limit: Int): List<PersonRow>

    companion object {
        /** Escapes LIKE wildcards in what the user typed and appends `%`. */
        fun likePrefix(typed: String): String =
            typed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

@Dao
interface SplitRuleDao {

    @Insert
    suspend fun insert(rule: SplitRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRuleTracks(rows: List<SplitRuleTrackEntity>)

    @Query("SELECT * FROM split_rules WHERE keyId = :keyId AND value = :value")
    suspend fun find(keyId: Long, value: String): SplitRuleEntity?

    @Query("SELECT * FROM split_rules WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun page(afterId: Long, limit: Int): List<SplitRuleEntity>

    @Query("DELETE FROM split_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Insert
    suspend fun insertEntry(entry: PlaylistEntryEntity)

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Post-scan cleanup (spec/architecture.md §6.5). */
@Dao
interface MaintenanceDao {

    /** Deletes folders with no tracks and no sub-folders; returns how many. Repeat until 0 to climb the tree. */
    @Query(
        "DELETE FROM folders WHERE id NOT IN (SELECT folderId FROM tracks) " +
            "AND id NOT IN (SELECT parentId FROM folders WHERE parentId IS NOT NULL)",
    )
    suspend fun deleteUnusedFolders(): Int

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT albumId FROM tracks WHERE albumId IS NOT NULL)")
    suspend fun deleteUnusedAlbums(): Int

    @Query("DELETE FROM tag_values WHERE id NOT IN (SELECT valueId FROM track_tags)")
    suspend fun deleteUnusedTagValues(): Int
}
