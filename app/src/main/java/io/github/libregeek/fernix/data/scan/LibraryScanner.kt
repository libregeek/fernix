// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import io.github.libregeek.fernix.data.ProblemKind
import io.github.libregeek.fernix.data.ProblemLog
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

    /** [unreadable] files could not be read this time; they are kept untagged and tried again at the next scan. */
    data class Finished(val processed: Int, val unreadable: Int = 0) : ScanState

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
    private val problems: ProblemLog? = null,
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    /** Scans [scope] on `Dispatchers.IO`. Returns at once if a scan is already running. */
    suspend fun scan(scope: ScanScope) {
        if (!claim()) return
        try {
            unreadable = 0
            val processed = withContext(Dispatchers.IO) { run(scope) }
            _state.value = ScanState.Finished(processed, unreadable)
        } catch (e: CancellationException) {
            _state.value = ScanState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = ScanState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private var unreadable = 0

    /** Back to idle, e.g. when the user dismisses a failure message. */
    fun clearFailure() {
        if (_state.value is ScanState.Failed) _state.value = ScanState.Idle
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
                        is DiffEvent.New -> upsert(event.media)
                        is DiffEvent.Changed -> upsert(event.media)
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

    /** Reads the file's tags; a file that cannot be read is counted and stored untagged (see [LibraryWriter]). */
    private fun upsert(media: MediaRow): LibraryOp.Upsert {
        val raw = tags.read(media.mediaStoreId)
        if (raw == null) {
            unreadable++
            problems?.add(ProblemKind.Index, media.displayName, media.folderPath, REASON_TAGS_UNREADABLE)
        }
        return LibraryOp.Upsert(media, raw)
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

        /** The reason logged for a file whose tags could not be read. */
        const val REASON_TAGS_UNREADABLE = "TAGS_UNREADABLE"
    }
}
