// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.FolderCount

/** What saving a scan scope would delete: tracks, and the playlist entries that go with them (spec/architecture.md §6). */
data class ScopeImpact(val tracks: Int, val playlistEntries: Int) {

    val isEmpty: Boolean get() = tracks == 0 && playlistEntries == 0

    companion object {
        /**
         * The tracks and playlist entries in folders outside [scope]. Folders on a volume that isn't in
         * [mountedVolumes] are left out, because the scan keeps what is on an unmounted volume.
         */
        fun of(
            trackCounts: List<FolderCount>,
            entryCounts: List<FolderCount>,
            scope: ScanScope,
            mountedVolumes: Set<String>,
        ): ScopeImpact {
            fun removed(path: String) = ScanScope.volumeOf(path) in mountedVolumes && !scope.contains(path)
            return ScopeImpact(
                tracks = trackCounts.filter { removed(it.path) }.sumOf { it.n },
                playlistEntries = entryCounts.filter { removed(it.path) }.sumOf { it.n },
            )
        }

        /** Counts per folder, not per track, so it stays fast on a large library. */
        suspend fun compute(db: FernixDatabase, scope: ScanScope, mountedVolumes: Set<String>): ScopeImpact =
            of(db.trackDao().trackCountsByFolder(), db.trackDao().playlistEntryCountsByFolder(), scope, mountedVolumes)
    }
}
