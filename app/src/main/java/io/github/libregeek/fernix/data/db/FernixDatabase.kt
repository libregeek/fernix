// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        TrackFtsEntity::class,
        TagKeyEntity::class,
        TagValueEntity::class,
        TrackTagEntity::class,
        KeyValueEntity::class,
        PersonEntity::class,
        SplitRuleEntity::class,
        SplitRuleTrackEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FernixDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun tagDao(): TagDao
    abstract fun splitRuleDao(): SplitRuleDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val FILE_NAME = "fernix.db"
    }
}
