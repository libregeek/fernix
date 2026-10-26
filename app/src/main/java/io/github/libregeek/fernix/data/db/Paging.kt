// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Embedded

/** Rows per page of every list (spec/architecture.md §5). */
const val PAGE_SIZE = 200

/** Size of the *Recently Added* and *Most Played* default playlists (spec/architecture.md §5). */
const val DEFAULT_PLAYLIST_SIZE = 50

/** Lightweight track row for lists; everything else is loaded from the database when needed. */
data class TrackRow(
    val id: Long,
    val mediaStoreId: Long,
    val title: String,
    val album: String,
    val artist: String,
    val durationMs: Long,
    /** The album's id, for the row's art thumbnail (spec §7 *Thumbnails*); null for a track with no album. */
    val albumId: Long? = null,
    /** The `DATE` tag, `U+001F`-joined like the other fixed fields; shown in brackets after the album on the Tracks list. */
    val date: String = "",
    /** The folder it is in, to tell whether its volume is mounted (spec §6 *Unmounted volumes*). */
    val folderId: Long = 0,
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

/** One value of a track under one key, with its stored text. */
data class TrackKeyValue(val valueId: Long, val value: String)

/** How many rows sit in the folder with this `folders.path`. */
data class FolderCount(val path: String, val n: Int)

/** A track's id and the MediaStore id of its file. */
data class TrackFile(val id: Long, val mediaStoreId: Long)

/** One value of a tag key on the playing track; [valueId] is the person id for a people key. */
data class PlayingTag(val key: String, val valueId: Long, val value: String, val keyId: Long = 0)

/** A tag key on a track, e.g. the roles a person has on it. */
data class RoleRow(val trackId: Long, val key: String)

/** A people tag key and the number of tracks a person has under it. */
data class RoleCount(val key: String, val trackCount: Int, val keyId: Long = 0)

/** An album as the Albums list shows it, with the number of its tracks. */
data class AlbumRow(val id: Long, val album: String, val year: Int, val trackCount: Int)

data class PlaylistRow(val id: Long, val name: String, val trackCount: Int)

/** A playlist entry: its [position] (the key for removing it) and the track. */
data class PlaylistEntryRow(
    val position: Int,
    @Embedded val track: TrackRow,
)

/** A folder id and its `<volume>/<path>`. */
data class FolderIdPath(val id: Long, val path: String)
