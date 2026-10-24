// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.ui.art.contentResolverOpener
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.data.scan.LibraryScanner
import io.github.libregeek.fernix.data.scan.MediaStoreSource
import io.github.libregeek.fernix.data.scan.MediaStoreTagSource
import io.github.libregeek.fernix.data.scan.ScanState
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container, owned by [FernixApp] (spec/architecture.md §2). Add the database,
 * scanner and playback controller here as they are built.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings: Settings by lazy { Settings(appContext) }

    /** Opens the file of a track by its MediaStore id (read-only), or null if it can't be opened. */
    val trackFiles: (Long) -> ParcelFileDescriptor? by lazy { contentResolverOpener(appContext.contentResolver) }

    val database: FernixDatabase by lazy {
        Room.databaseBuilder(appContext, FernixDatabase::class.java, FernixDatabase.FILE_NAME)
            // The scan writes batches while a read cursor over `tracks` is open; that needs WAL,
            // which Room would otherwise skip on low-RAM devices.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(FernixDatabase.MIGRATION_1_2)
            .addCallback(FernixDatabase.CREATE_CALLBACK)
            .build()
    }

    val trackSearch: TrackSearch by lazy { TrackSearch(database) }

    /** Called by the playback service when a track plays through; the write runs off the main thread. */
    fun recordPlay(trackId: Long) {
        appScope.launch(Dispatchers.IO) { database.trackDao().recordPlay(trackId, System.currentTimeMillis()) }
    }

    /** Main thread only. Connects to the playback service on first use. */
    val playback: PlaybackController by lazy { PlaybackController(appContext) }

    /** Outlives activities, so a scan keeps going across rotation and screen changes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Reads a file's tags by MediaStore id: for scans and for undoing a split rule. */
    val tagSource: MediaStoreTagSource by lazy { MediaStoreTagSource(appContext) }

    val scanner: LibraryScanner by lazy {
        LibraryScanner(database, MediaStoreSource(appContext), tagSource)
    }

    /**
     * Scans with the chosen scope (spec/architecture.md §6); does nothing until one is chosen or
     * while a scan is running. `scanCompleted` stays false until the scan finishes, so an
     * interrupted one is resumed by the next call.
     */
    fun startScan() {
        val scope = settings.scanScope ?: return
        if (scanner.state.value is ScanState.Running) return
        appScope.launch {
            settings.scanCompleted = false
            scanner.scan(scope)
            if (scanner.state.value is ScanState.Finished) settings.scanCompleted = true
        }
    }
}
