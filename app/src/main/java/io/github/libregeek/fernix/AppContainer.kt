// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.content.Context
import io.github.libregeek.fernix.ui.permission.StoragePermission
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import coil3.SingletonImageLoader
import io.github.libregeek.fernix.ui.art.ThumbnailCache
import io.github.libregeek.fernix.ui.art.contentResolverOpener
import java.io.File
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.data.scan.LibraryScanner
import io.github.libregeek.fernix.data.scan.MediaStoreSource
import io.github.libregeek.fernix.data.scan.MediaStoreTagSource
import io.github.libregeek.fernix.data.scan.ScanScope
import io.github.libregeek.fernix.data.scan.ScanState
import io.github.libregeek.fernix.data.ProblemKind
import io.github.libregeek.fernix.data.ProblemLog
import io.github.libregeek.fernix.playback.MediaItems
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
            .addMigrations(FernixDatabase.MIGRATION_1_2, FernixDatabase.MIGRATION_2_3)
            .addCallback(FernixDatabase.CREATE_CALLBACK)
            .build()
    }

    val trackSearch: TrackSearch by lazy { TrackSearch(database) }

    /** The small thumbnail cache of the Albums and Tracks lists, in the app's cache directory (spec §7). */
    val thumbnails: ThumbnailCache by lazy { ThumbnailCache(File(appContext.cacheDir, "thumbnails")) }

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

    /** Where the scanner and the folder chooser look for audio. */
    val mediaSource: MediaStoreSource by lazy { MediaStoreSource(appContext) }

    val scanner: LibraryScanner by lazy {
        LibraryScanner(database, mediaSource, tagSource, problems = problems)
    }

    /** The files that could not be indexed or played, kept 30 days and viewed from Settings (spec §6 *Errors*). */
    val problems: ProblemLog by lazy { ProblemLog(File(appContext.filesDir, "problem-files.log")) }

    /** Called by the playback service when the file behind [mediaId] cannot be played; the lookup and write run off the main thread. */
    fun recordPlayFailure(mediaId: String?, reason: String) {
        val trackId = mediaId?.let(MediaItems::trackId) ?: return
        appScope.launch(Dispatchers.IO) {
            val track = database.trackDao().byId(trackId) ?: return@launch
            val folder = database.folderDao().byId(track.folderId)?.path.orEmpty()
            problems.add(ProblemKind.Play, track.displayName, folder, reason)
        }
    }

    /**
     * Saves a new scan scope and scans with it (spec/architecture.md §6): a wider one adds tracks, a
     * narrower one deletes the tracks outside it and, through the playlists' cascade, their entries.
     * The caller has already shown [ScopeImpact] and had it confirmed.
     */
    fun changeScope(scope: ScanScope) {
        settings.scanScope = scope
        startScan()
    }

    /**
     * Scans with the chosen scope (spec/architecture.md §6); does nothing until one is chosen or
     * while a scan is running. `scanCompleted` stays false until the scan finishes, so an
     * interrupted one is resumed by the next call.
     */
    fun startScan() {
        val scope = settings.scanScope ?: return
        if (scanner.state.value is ScanState.Running) return
        // Without the audio permission MediaStore answers with nothing: don't scan (and so don't look like an empty library).
        if (ContextCompat.checkSelfPermission(appContext, StoragePermission.permission) != PackageManager.PERMISSION_GRANTED) return
        appScope.launch {
            settings.scanCompleted = false
            scanner.scan(scope)
            val state = scanner.state.value
            if (state is ScanState.Finished) {
                settings.scanCompleted = true
                // A scan that added, changed or removed files may have changed art: start the thumbnails afresh.
                if (state.processed > 0) {
                    thumbnails.clear()
                    SingletonImageLoader.get(appContext).memoryCache?.clear()
                }
            }
        }
    }
}
