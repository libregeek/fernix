// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import io.github.libregeek.fernix.data.db.FernixDatabase

/**
 * Deleting a split rule (spec/architecture.md §5, Settings): the rule goes, then the files of the
 * tracks it was applied to are read again, so the joined value comes back unless another rule still
 * splits it. A file that can't be read is left as it is rather than emptied.
 */
class SplitUndo(private val db: FernixDatabase, private val tags: TagSource) {

    /** Blocking file reads: call from `Dispatchers.IO`. Returns how many tracks were read again. */
    suspend fun delete(ruleId: Long, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val trackIds = db.splitRuleDao().trackIds(ruleId)
        db.splitRuleDao().delete(ruleId) // cascades to split_rule_tracks
        // A new writer loads the rules that are left, so only this rule stops applying.
        val writer = LibraryWriter(db, FolderResolver(db.folderDao()))
        var done = 0
        var reread = 0
        for (chunk in trackIds.chunked(CHUNK)) {
            val ops = chunk.mapNotNull { id ->
                val track = db.trackDao().byId(id) ?: return@mapNotNull null
                val folder = db.folderDao().byId(track.folderId) ?: return@mapNotNull null
                val raw = tags.read(track.mediaStoreId) ?: return@mapNotNull null
                LibraryOp.Upsert(
                    MediaRow(
                        mediaStoreId = track.mediaStoreId,
                        sizeBytes = track.sizeBytes,
                        dateModified = track.dateModified,
                        displayName = track.displayName,
                        folderPath = folder.path,
                        durationMs = track.durationMs,
                        dateAdded = track.dateAdded,
                    ),
                    raw,
                )
            }
            writer.apply(ops)
            reread += ops.size
            done += chunk.size
            onProgress(done, trackIds.size)
        }
        // The parts that only the split had made are unused now.
        db.maintenanceDao().deleteUnusedTagValues()
        return reread
    }

    private companion object {
        const val CHUNK = 100
    }
}
