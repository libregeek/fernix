// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

/** A row of the MediaStore discovery cursor (spec/architecture.md §6.1). */
data class MediaRow(
    val mediaStoreId: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val displayName: String,
    /** See [ScanScope.folderPath]. */
    val folderPath: String,
    /** Not used for change detection; stored when the track is (re)parsed. */
    val durationMs: Long = 0,
)

/** A row of the `tracks` cursor joined to `folders`, with the fields used for change detection. */
data class StoredRow(
    val mediaStoreId: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val displayName: String,
    val folderPath: String,
)

sealed interface DiffEvent {
    /** In MediaStore and in scope, not yet stored. */
    data class New(val media: MediaRow) : DiffEvent

    /** Stored, but size, date, folder or name differ. */
    data class Changed(val media: MediaRow) : DiffEvent

    /** Stored, but gone from MediaStore or out of scope, on a mounted volume. */
    data class Vanished(val mediaStoreId: Long) : DiffEvent
}

data class DiffCounts(val new: Int, val changed: Int, val vanished: Int, val unchanged: Int)

/** Merge-walk of §6.2: two cursors ordered by `mediaStoreId`, constant memory. */
object ScanDiff {

    /**
     * Walks [media] and [stored], both strictly ascending by `mediaStoreId`, and reports the
     * differences to [onEvent]. Media rows outside [scope] are treated as absent. Stored rows on a
     * volume not in [mountedVolumes] are left alone, whatever MediaStore says.
     */
    suspend fun walk(
        media: Iterator<MediaRow>,
        stored: Iterator<StoredRow>,
        scope: ScanScope,
        mountedVolumes: Set<String>,
        onEvent: suspend (DiffEvent) -> Unit,
    ): DiffCounts {
        var new = 0
        var changed = 0
        var vanished = 0
        var unchanged = 0

        val inScope = media.asSequence().filter { scope.contains(it.folderPath) }.iterator()
        var m = if (inScope.hasNext()) inScope.next() else null
        var s = if (stored.hasNext()) stored.next() else null

        while (m != null || s != null) {
            val mId = m?.mediaStoreId
            val sId = s?.mediaStoreId
            when {
                s == null || (m != null && mId!! < sId!!) -> {
                    onEvent(DiffEvent.New(m!!))
                    new++
                    m = if (inScope.hasNext()) inScope.next() else null
                }
                m == null || sId!! < mId!! -> {
                    if (ScanScope.volumeOf(s.folderPath) in mountedVolumes) {
                        onEvent(DiffEvent.Vanished(s.mediaStoreId))
                        vanished++
                    }
                    s = if (stored.hasNext()) stored.next() else null
                }
                else -> {
                    if (differs(m, s)) {
                        onEvent(DiffEvent.Changed(m))
                        changed++
                    } else {
                        unchanged++
                    }
                    m = if (inScope.hasNext()) inScope.next() else null
                    s = if (stored.hasNext()) stored.next() else null
                }
            }
        }
        return DiffCounts(new, changed, vanished, unchanged)
    }

    private fun differs(m: MediaRow, s: StoredRow) =
        m.sizeBytes != s.sizeBytes || m.dateModified != s.dateModified ||
            m.displayName != s.displayName || m.folderPath != s.folderPath
}
