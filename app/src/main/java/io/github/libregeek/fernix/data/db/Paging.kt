// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Embedded

/** Rows per page of every list (spec/architecture.md §5). */
const val PAGE_SIZE = 200

/** Lightweight track row for lists; everything else is loaded from the database when needed. */
data class TrackRow(
    val id: Long,
    val mediaStoreId: Long,
    val title: String,
    val album: String,
    val artist: String,
    val durationMs: Long,
)

/** Position in a title-sorted track list: the last row of the previous page. */
data class TrackCursor(val title: String, val id: Long) {
    companion object {
        /** Sorts before every row: titles are >= "" and ids start at 1. */
        val START = TrackCursor("", 0)
    }

    fun after(row: TrackRow) = TrackCursor(row.title, row.id)
}

data class PersonRow(val valueId: Long, val name: String, val trackCount: Int)

data class KeyValueRow(val valueId: Long, val value: String, val trackCount: Int)

/** A tag key on a track, e.g. the roles a person has on it. */
data class RoleRow(val trackId: Long, val key: String)

/** A people tag key and the number of tracks a person has under it. */
data class RoleCount(val key: String, val trackCount: Int)

data class PlaylistRow(val id: Long, val name: String, val trackCount: Int)

/** A playlist entry: its [position] (the key for removing it) and the track. */
data class PlaylistEntryRow(
    val position: Int,
    @Embedded val track: TrackRow,
)
