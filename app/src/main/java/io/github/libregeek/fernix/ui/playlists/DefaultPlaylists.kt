// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.playlists

import androidx.annotation.StringRes
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TrackFilter
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.data.db.TrackSearch

/**
 * The read-only default playlists (spec/architecture.md §5). They have no database row, so they
 * are named by these negative ids wherever a playlist id is passed (back stack, ViewModels);
 * Favourites and the user's playlists use their real, positive `playlists.id`.
 */
object DefaultPlaylists {
    const val RECENTLY_ADDED = -1L
    const val ALL_SONGS = -2L
    const val NEVER_PLAYED = -3L
    const val MOST_PLAYED = -4L

    /** Shown below Favourites, in this order. */
    val ReadOnly = listOf(RECENTLY_ADDED, ALL_SONGS, NEVER_PLAYED, MOST_PLAYED)

    fun isReadOnly(id: Long) = id < 0

    @StringRes
    fun title(id: Long): Int = when (id) {
        RECENTLY_ADDED -> R.string.playlist_recently_added
        ALL_SONGS -> R.string.playlist_all_songs
        NEVER_PLAYED -> R.string.playlist_never_played
        MOST_PLAYED -> R.string.playlist_most_played
        else -> error("not a default playlist: $id")
    }

    @StringRes
    fun description(id: Long): Int = when (id) {
        RECENTLY_ADDED -> R.string.playlist_recently_added_desc
        ALL_SONGS -> R.string.playlist_all_songs_desc
        NEVER_PLAYED -> R.string.playlist_never_played_desc
        MOST_PLAYED -> R.string.playlist_most_played_desc
        else -> error("not a default playlist: $id")
    }
}

/** Loads the whole track list of any playlist id, in playlist order, to play or queue it. */
class PlaylistTracks(private val db: FernixDatabase, private val search: TrackSearch) {

    suspend fun load(id: Long): List<TrackRow> = when (id) {
        DefaultPlaylists.RECENTLY_ADDED -> db.trackDao().recentlyAdded()
        DefaultPlaylists.MOST_PLAYED -> db.trackDao().mostPlayed()
        DefaultPlaylists.ALL_SONGS -> search.all(TrackFilter())
        DefaultPlaylists.NEVER_PLAYED -> search.all(TrackFilter(neverPlayed = true))
        else -> db.playlistDao().tracks(id)
    }
}
