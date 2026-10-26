// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.util.Log
import io.github.libregeek.fernix.data.ProblemKind
import io.github.libregeek.fernix.data.ProblemLog
import io.github.libregeek.fernix.data.db.FernixDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        val pending = ArrayList<DiffEvent>(batchSize)
        var processed = 0

        suspend fun flush() {
            if (pending.isEmpty()) return
            writer.apply(buildOps(pending))
            processed += pending.size
            pending.clear()
            _state.value = ScanState.Running(processed)
        }

        val started = System.currentTimeMillis()
        var counts: DiffCounts? = null
        media.query(mounted).use { mediaRows ->
            StoredRows(db.trackDao().storedCursor()).use { storedRows ->
                counts = ScanDiff.walk(mediaRows, storedRows, scope, mounted) { event ->
                    coroutineContext.ensureActive()
                    pending += event
                    if (pending.size >= batchSize) flush()
                }
            }
        }
        flush()
        cleanUp()
        counts?.let { logSummary(it, System.currentTimeMillis() - started) }
        return processed
    }

    /**
     * One line in logcat (`adb logcat -s FernixScan`) after each scan: the files MediaStore listed in
     * scope, and what the scan did with them. Counts only, never a file name or path.
     */
    private fun logSummary(counts: DiffCounts, millis: Long) {
        val files = counts.new + counts.changed + counts.unchanged
        Log.i(LOG_TAG, "Scanned $files files in scope: ${counts.new} new, ${counts.changed} changed, " +
            "${counts.unchanged} unchanged, ${counts.vanished} removed, $unreadable unreadable; ${millis} ms")
    }

    /**
     * The writes for one batch of diff events. The tags of the batch's files are read several at a time (each
     * file is opened and parsed on its own, so this changes how long the scan takes and nothing it stores);
     * the writes come out in the order of the events.
     */
    private suspend fun buildOps(events: List<DiffEvent>): List<LibraryOp> = coroutineScope {
        val readers = Dispatchers.IO.limitedParallelism(PARALLEL_READS)
        val reads = events.map { event ->
            when (event) {
                is DiffEvent.New -> async(readers) { tags.read(event.media.mediaStoreId) }
                is DiffEvent.Changed -> async(readers) { tags.read(event.media.mediaStoreId) }
                else -> null
            }
        }
        events.mapIndexed { i, event ->
            when (event) {
                is DiffEvent.New -> upsert(event.media, reads[i]!!.await())
                is DiffEvent.Changed -> upsert(event.media, reads[i]!!.await())
                is DiffEvent.Vanished -> LibraryOp.Remove(event.mediaStoreId)
                is DiffEvent.DateAdded -> LibraryOp.SetDateAdded(event.mediaStoreId, event.dateAdded)
            }
        }
    }

    /** A file whose tags could not be read ([raw] is null) is counted and stored untagged (see [LibraryWriter]). */
    private fun upsert(media: MediaRow, raw: Map<String, List<String>>?): LibraryOp.Upsert {
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

        /** Files read at the same time: enough to hide the storage latency, few enough to leave the phone usable. */
        private val PARALLEL_READS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        private const val LOG_TAG = "FernixScan"

        /** The reason logged for a file whose tags could not be read. */
        const val REASON_TAGS_UNREADABLE = "TAGS_UNREADABLE"
    }
}
