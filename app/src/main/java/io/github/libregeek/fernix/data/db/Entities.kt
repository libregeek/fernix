// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema of spec/architecture.md §5. Multi-value columns hold U+001F-joined values (TagValues).

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["parentId", "name"]),
    ],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null for a volume root. */
    val parentId: Long?,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    /** `<volume>/<relativePath>`, e.g. `external_primary/Music/Albums`. */
    val path: String,
)

@Entity(
    tableName = "albums",
    indices = [Index(value = ["album", "year"], unique = true)],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val album: String,
    /** 0 means unknown, so that the unique index alone prevents duplicate albums. */
    val year: Int = 0,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["mediaStoreId"], unique = true),
        Index(value = ["folderId"]),
        Index(value = ["title"]),
        Index(value = ["albumId", "discNumber", "trackNumber"]),
    ],
)
data class TrackEntity(
    /** Also the Media3 `MediaItem.mediaId`. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val folderId: Long,
    val displayName: String,
    val durationMs: Long,
    val dateModified: Long,
    val sizeBytes: Long,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String = "",
    val album: String = "",
    val date: String = "",
    val artist: String = "",
    val composer: String = "",
    val lyricist: String = "",
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val albumId: Long? = null,
)

/** Full-text index over [TrackEntity], kept in sync by Room-generated triggers. */
@Fts4(
    contentEntity = TrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
)
@Entity(tableName = "tracks_fts")
data class TrackFtsEntity(
    val title: String,
    val album: String,
    val artist: String,
    val composer: String,
    val lyricist: String,
)

/** Uppercase tag key, e.g. `ARTIST`. */
@Entity(tableName = "tag_keys", indices = [Index(value = ["key"], unique = true)])
data class TagKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
)

/** One row per distinct value across all keys (case-insensitive; first spelling kept). */
@Entity(tableName = "tag_values", indices = [Index(value = ["value"], unique = true)])
data class TagValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val value: String,
)

/** One row per value of a tag on a track; integers only. */
@Entity(
    tableName = "track_tags",
    primaryKeys = ["trackId", "keyId", "valueId"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["valueId", "keyId", "trackId"])],
)
data class TrackTagEntity(
    val trackId: Long,
    val keyId: Long,
    val valueId: Long,
    /** Position among the track's values for this key. */
    val ord: Int,
)

/** Which values occur under each key, and on how many tracks; drives the filter value picker. */
@Entity(tableName = "key_values", primaryKeys = ["keyId", "valueId"])
data class KeyValueEntity(
    val keyId: Long,
    val valueId: Long,
    val trackCount: Int,
)

/** A person: a value that occurs under at least one people tag key, regardless of role. */
@Entity(
    tableName = "people",
    foreignKeys = [
        ForeignKey(
            entity = TagValueEntity::class,
            parentColumns = ["id"],
            childColumns = ["valueId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PersonEntity(
    @PrimaryKey val valueId: Long,
    /** Number of distinct tracks the person is credited on. */
    val trackCount: Int,
)

@Entity(
    tableName = "split_rules",
    indices = [Index(value = ["keyId", "value"], unique = true)],
)
data class SplitRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyId: Long,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val value: String,
    /** The parts, U+001F-joined. */
    val parts: String,
    val createdAt: Long,
)

/** The tracks a split rule was applied to, used to undo it. */
@Entity(
    tableName = "split_rule_tracks",
    primaryKeys = ["ruleId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = SplitRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId"])],
)
data class SplitRuleTrackEntity(
    val ruleId: Long,
    val trackId: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Entries are read `ORDER BY position`; gaps are fine and the same track may appear twice. */
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId"])],
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val trackId: Long,
)
