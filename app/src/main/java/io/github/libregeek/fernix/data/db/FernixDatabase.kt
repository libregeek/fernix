// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        QueueItemEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class FernixDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun tagDao(): TagDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun queueDao(): QueueDao

    companion object {
        const val FILE_NAME = "fernix.db"

        /** Name stored for the Favourites row; the UI shows its own localised title for that kind. */
        const val FAVOURITES_NAME = "Favourites"

        /**
         * Version 2: play history and date added on `tracks`, `playlists.kind` with the Favourites
         * row, and one entry per track in a playlist (spec/architecture.md §5).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN dateAdded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastPlayedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_dateAdded ON tracks (dateAdded)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_playCount ON tracks (playCount)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_lastPlayedAt ON tracks (lastPlayedAt)")

                db.execSQL("ALTER TABLE playlists ADD COLUMN kind TEXT NOT NULL DEFAULT 'user'")
                // Keep the first entry of each track before the unique index goes on.
                db.execSQL(
                    "DELETE FROM playlist_entries WHERE EXISTS (SELECT 1 FROM playlist_entries e2 " +
                        "WHERE e2.playlistId = playlist_entries.playlistId " +
                        "AND e2.trackId = playlist_entries.trackId AND e2.position < playlist_entries.position)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_entries_playlistId_trackId " +
                        "ON playlist_entries (playlistId, trackId)",
                )
                insertFavourites(db)
            }
        }

        /**
         * Version 3: splits are no longer stored as rules (a split just edits the tracks that carry the
         * value), so the two rule tables go (spec/architecture.md §5 *Tag splitting*).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS split_rule_tracks")
                db.execSQL("DROP TABLE IF EXISTS split_rules")
            }
        }

        /** Version 4: the saved play queue (spec/architecture.md §8 *Saved queue*). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS queue_items (position INTEGER NOT NULL, trackId INTEGER NOT NULL, PRIMARY KEY(position))",
                )
            }
        }

        /** Creates the Favourites row on a new database (the migration does it for existing ones). */
        val CREATE_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = insertFavourites(db)
        }

        private fun insertFavourites(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT INTO playlists (name, kind, createdAt, updatedAt) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(FAVOURITES_NAME, PlaylistEntity.KIND_FAVOURITES, now, now),
            )
        }
    }
}
