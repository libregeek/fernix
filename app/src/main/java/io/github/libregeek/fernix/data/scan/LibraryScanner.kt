// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import io.github.libregeek.fernix.data.db.FernixDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

sealed interface ScanState {
    data object Idle : ScanState

    /** [processed] files (new, changed or removed) committed so far. */
    data class Running(val processed: Int) : ScanState

    data class Finished(val processed: Int) : ScanState

    data class Failed(val message: String) : ScanState
}

/**
 * Runs a scan (spec/architecture.md §6): discovery, diff, tag ingestion, batched persistence,
 * cleanup. Each committed batch is final, so a cancelled or crashed scan simply resumes on the
 * next run, when the diff skips what is already stored. Only one scan runs at a time.
 */
class LibraryScanner(
    private val db: FernixDatabase,
    private val media: MediaSource,
    private val tags: TagSource,
    private val batchSize: Int = BATCH_SIZE,
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    /** Scans [scope] on `Dispatchers.IO`. Returns at once if a scan is already running. */
    suspend fun scan(scope: ScanScope) {
        if (!claim()) return
        try {
            val processed = withContext(Dispatchers.IO) { run(scope) }
            _state.value = ScanState.Finished(processed)
        } catch (e: CancellationException) {
            _state.value = ScanState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = ScanState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun claim(): Boolean {
        if (_state.value is ScanState.Running) return false
        _state.value = ScanState.Running(0)
        return true
    }

    private suspend fun run(scope: ScanScope): Int {
        val mounted = media.mountedVolumes()
        val writer = LibraryWriter(db, FolderResolver(db.folderDao()))
        val pending = ArrayList<LibraryOp>(batchSize)
        var processed = 0

        suspend fun flush() {
            if (pending.isEmpty()) return
            writer.apply(pending)
            processed += pending.size
            pending.clear()
            _state.value = ScanState.Running(processed)
        }

        media.query(mounted).use { mediaRows ->
            StoredRows(db.trackDao().storedCursor()).use { storedRows ->
                ScanDiff.walk(mediaRows, storedRows, scope, mounted) { event ->
                    coroutineContext.ensureActive()
                    pending += when (event) {
                        is DiffEvent.New -> LibraryOp.Upsert(event.media, tags.read(event.media.mediaStoreId))
                        is DiffEvent.Changed -> LibraryOp.Upsert(event.media, tags.read(event.media.mediaStoreId))
                        is DiffEvent.Vanished -> LibraryOp.Remove(event.mediaStoreId)
                        is DiffEvent.DateAdded -> LibraryOp.SetDateAdded(event.mediaStoreId, event.dateAdded)
                    }
                    if (pending.size >= batchSize) flush()
                }
            }
        }
        flush()
        cleanUp()
        return processed
    }

    /** Spec §6.5: unused folders (bottom-up), albums and tag values. */
    private suspend fun cleanUp() {
        val maintenance = db.maintenanceDao()
        while (maintenance.deleteUnusedFolders() > 0) { /* climb until no empty leaf remains */ }
        maintenance.deleteUnusedAlbums()
        maintenance.deleteUnusedTagValues()
    }

    companion object {
        const val BATCH_SIZE = 500
    }
}
