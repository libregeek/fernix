// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.PlaylistEntryRow
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistDetailState(
    val loading: Boolean = true,
    /** Null once loaded means the playlist no longer exists. */
    val name: String? = null,
    val entries: List<PlaylistEntryRow> = emptyList(),
    val empty: Boolean = false,
)

/** Playlist entries in playlist order, keyset-paged by position (spec/architecture.md §9). */
class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val db: FernixDatabase,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailState())
    val state: StateFlow<PlaylistDetailState> = _state

    private var done = false
    private var busy = false

    init {
        viewModelScope.launch {
            val playlist = withContext(Dispatchers.IO) { db.playlistDao().byId(playlistId) }
            _state.update { it.copy(loading = playlist != null, name = playlist?.name) }
            if (playlist != null) loadMore()
        }
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val last = _state.value.entries.lastOrNull()
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                db.playlistDao().entriesPage(playlistId, last?.position ?: -1, PAGE_SIZE)
            }
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update {
                val all = it.entries + page
                it.copy(loading = false, entries = all, empty = done && all.isEmpty())
            }
        }
    }

    fun remove(entry: PlaylistEntryRow) {
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
            val tracks = withContext(Dispatchers.IO) { db.playlistDao().tracks(playlistId) }
            if (tracks.isEmpty()) return@launch
            val start = position?.let { p -> _state.value.entries.indexOfFirst { it.position == p } }
                ?.takeIf { it >= 0 } ?: 0
            playback.play(tracks, start, shuffle)
        }
    }

    fun enqueue(entry: PlaylistEntryRow) = playback.enqueue(listOf(entry.track))
}
