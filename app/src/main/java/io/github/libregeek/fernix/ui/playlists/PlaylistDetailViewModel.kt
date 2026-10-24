// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.PlaylistEntity
import io.github.libregeek.fernix.data.db.PlaylistEntryRow
import io.github.libregeek.fernix.data.db.TrackCursor
import io.github.libregeek.fernix.data.db.TrackFilter
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistDetailState(
    val loading: Boolean = true,
    /** A stored playlist's name; null for a default playlist (it has a string resource) or when missing. */
    val name: String? = null,
    /** Set once loaded; false means the playlist no longer exists. */
    val exists: Boolean = false,
    val isFavourites: Boolean = false,
    val readOnly: Boolean = false,
    val entries: List<PlaylistEntryRow> = emptyList(),
    val empty: Boolean = false,
)

/**
 * Entries of any playlist, in playlist order (spec/architecture.md §5, §9). [playlistId] is a
 * stored playlist's id or one of [DefaultPlaylists]. Stored playlists page by position, All Songs
 * and Never Played by title, Recently Added and Most Played are a single page of 50. For the
 * query-backed lists `position` is just the row's index.
 */
class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val db: FernixDatabase,
    private val search: TrackSearch,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailState(readOnly = DefaultPlaylists.isReadOnly(playlistId)))
    val state: StateFlow<PlaylistDetailState> = _state

    private val tracks = PlaylistTracks(db, search)
    private var done = false
    private var busy = false

    init {
        viewModelScope.launch {
            if (DefaultPlaylists.isReadOnly(playlistId)) {
                _state.update { it.copy(exists = true) }
            } else {
                val playlist = withContext(Dispatchers.IO) { db.playlistDao().byId(playlistId) }
                _state.update {
                    it.copy(
                        exists = playlist != null,
                        loading = playlist != null,
                        name = playlist?.name,
                        isFavourites = playlist?.kind == PlaylistEntity.KIND_FAVOURITES,
                    )
                }
                if (playlist == null) return@launch
            }
            loadMore()
        }
    }

    fun loadMore() {
        if (busy || done || !_state.value.exists) return
        busy = true
        val last = _state.value.entries.lastOrNull()
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { loadPage(last) }
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update {
                val all = it.entries + page
                it.copy(loading = false, entries = all, empty = done && all.isEmpty())
            }
        }
    }

    private suspend fun loadPage(last: PlaylistEntryRow?): List<PlaylistEntryRow> {
        val next = (last?.position ?: -1) + 1
        fun indexed(rows: List<TrackRow>) =
            rows.mapIndexed { i, row -> PlaylistEntryRow(next + i, row) }
        return when (playlistId) {
            DefaultPlaylists.RECENTLY_ADDED -> indexed(db.trackDao().recentlyAdded()).also { done = true }
            DefaultPlaylists.MOST_PLAYED -> indexed(db.trackDao().mostPlayed()).also { done = true }
            DefaultPlaylists.ALL_SONGS -> indexed(titlePage(TrackFilter(), last))
            DefaultPlaylists.NEVER_PLAYED -> indexed(titlePage(TrackFilter(neverPlayed = true), last))
            else -> db.playlistDao().entriesPage(playlistId, last?.position ?: -1, PAGE_SIZE)
        }
    }

    private suspend fun titlePage(filter: TrackFilter, last: PlaylistEntryRow?) =
        search.page(filter, last?.track?.let { TrackCursor(it.title, it.id) } ?: TrackCursor.START).map { it.track }

    /** Removes an entry; only stored playlists (Favourites and the user's own) allow it. */
    fun remove(entry: PlaylistEntryRow) {
        if (_state.value.readOnly) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.playlistDao().removeEntry(playlistId, entry.position)
                db.playlistDao().touch(playlistId, System.currentTimeMillis())
            }
            _state.update {
                val rest = it.entries - entry
                it.copy(entries = rest, empty = done && rest.isEmpty())
            }
        }
    }

    /** Plays the whole playlist in order, starting at the entry at [position] (default: the first). */
    fun play(shuffle: Boolean = false, position: Int? = null) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { tracks.load(playlistId) }
            if (list.isEmpty()) return@launch
            val start = position?.let { p -> _state.value.entries.indexOfFirst { it.position == p } }
                ?.takeIf { it >= 0 } ?: 0
            playback.play(list, start, shuffle)
        }
    }

    fun enqueue(entry: PlaylistEntryRow) = playback.enqueue(listOf(entry.track))
}
